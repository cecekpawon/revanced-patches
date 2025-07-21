package app.revanced.extension.spotify.misc.fix;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.spotify.UserAgent;

import java.io.ByteArrayInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static app.revanced.extension.spotify.misc.fix.Session.FAILED_TO_RENEW_SESSION;

class WebApp {
    private static final String OPEN_SPOTIFY_COM = "open.spotify.com";
    private static final String OPEN_SPOTIFY_COM_URL = "https://" + OPEN_SPOTIFY_COM;
    private static final String ACCOUNTS_SPOTIFY_OPEN_SPOTIFY_COM_URL = "https://accounts.spotify.com/login?allow_password=1"
            + "&continue=https%3A%2F%2Fopen.spotify.com";

    private static final int GET_SESSION_TIMEOUT_SECONDS = 10;
    private static final String JAVASCRIPT_INTERFACE_NAME = "androidInterface";
    private static final String USER_AGENT = getWebUserAgent();

    /**
     * A session obtained from the webview after logging in.
     */
    @Nullable
    static volatile Session currentSession = null;

    /**
     * Current webview in use. Any use of the object must be done on the main thread.
     */
    @SuppressLint("StaticFieldLeak")
    private static volatile WebView webView;

    interface NativeLoginHandler {
        void login();
    }

    static NativeLoginHandler nativeLoginHandler;

    static void launchLogin(Context context) {
        final Dialog dialog = newDialog(context);

        Utils.runOnBackgroundThread(() -> {
            Logger.printInfo(() -> "Launching login");

            // A session must be obtained from a login. Repeat until a session is acquired.
            boolean isAcquired = false;
            do {
                CountDownLatch onLoggedInLatch = new CountDownLatch(1);
                CountDownLatch getSessionLatch = new CountDownLatch(1);

                // Can't use Utils.getContext() here, because autofill won't work.
                // See https://stackoverflow.com/a/79182053/11213244.
                setWebView(context, ACCOUNTS_SPOTIFY_OPEN_SPOTIFY_COM_URL, new WebViewCallback() {
                    @Override
                    void onInitialized() {
                        super.onInitialized();

                        dialog.setContentView(webView);
                        dialog.show();
                    }

                    @Override
                    void onLoggedIn(String cookies) {
                        onLoggedInLatch.countDown();
                    }

                    @Override
                    void onReceivedSession(Session session) {
                        super.onReceivedSession(session);

                        getSessionLatch.countDown();
                        dialog.dismiss();

                        try {
                            nativeLoginHandler.login();
                        } catch (Exception ex) {
                            Logger.printException(() -> "nativeLoginHandler failure", ex);
                        }
                    }
                });

                try {
                    // Wait indefinitely until the user logs in.
                    onLoggedInLatch.await();
                    // Wait until the session is received, or timeout.
                    isAcquired = getSessionLatch.await(GET_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Logger.printException(() -> "Login interrupted", ex);
                    Thread.currentThread().interrupt();
                }
            } while (!isAcquired);
        });
    }

    static void renewSessionBlocking(String cookies) {
        Logger.printInfo(() -> "Renewing session with cookies: " + cookies);

        CountDownLatch getSessionLatch = new CountDownLatch(1);

        setWebView(Utils.getContext(), OPEN_SPOTIFY_COM_URL, new WebViewCallback() {
            @Override
            public void onInitialized() {
                setCookies(cookies);

                super.onInitialized();
            }

            public void onReceivedSession(Session session) {
                super.onReceivedSession(session);

                getSessionLatch.countDown();
            }
        });

        boolean isAcquired = false;
        try {
            isAcquired = getSessionLatch.await(GET_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "Session renewal interrupted", ex);
            Thread.currentThread().interrupt();
        }

        if (!isAcquired) {
            Logger.printException(() -> "Failed to retrieve session within " + GET_SESSION_TIMEOUT_SECONDS + " seconds");
            currentSession = FAILED_TO_RENEW_SESSION;
            destructWebView();
        }
    }

    static void launchSession(String cookies) {
        if (webView == null) {
            Logger.printInfo(() -> "Launching session");

            setWebView(Utils.getContext(), OPEN_SPOTIFY_COM_URL, new WebViewCallback() {
                @Override
                void onInitialized() {
                    setCookies(cookies);

                    super.onInitialized();
                }

                @Override
                void onReceivedSession(Session session) {
                }
            });
        }
    }

    /**
     * All methods are called on the main thread.
     */
    abstract static class WebViewCallback {
        void onInitialized() {
            currentSession = null; // Reset current session.
        }

        void onLoggedIn(String cookies) {
        }

        void onReceivedSession(Session session) {
            Logger.printInfo(() -> "Received session: " + session);
            currentSession = session;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void setWebView(
            Context context,
            String initialUrl,
            WebViewCallback webViewCallback
    ) {
        Utils.runOnMainThreadNowOrLater(() -> {
            destructWebView();

            webView = new WebView(context);

            WebSettings settings = webView.getSettings();
            settings.setDomStorageEnabled(true);
            settings.setJavaScriptEnabled(true);
            settings.setUserAgentString(USER_AGENT);
            settings.setAllowContentAccess(true);
            settings.setMediaPlaybackRequiresUserGesture(false); // TODO: May not be needed.

            // Make Spotify playable in the webview.
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onPermissionRequest(PermissionRequest request) {
                    for (String resource : request.getResources())
                        // Spotify uses Widevine DRM for protected media playback.
                        if (resource.equals(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                            request.grant(request.getResources());
                }
            });

            webView.setWebViewClient(new WebViewClient() {
                private final WebResourceResponse EMPTY_VIDEO_RESPONSE = new WebResourceResponse(
                        "video/mp4", "UTF-8", new ByteArrayInputStream(new byte[0])
                );

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    Uri uri = request.getUrl();

                    if (uri.getHost().equals(OPEN_SPOTIFY_COM_URL))
                        Utils.runOnMainThread(() -> webViewCallback.onLoggedIn(getCurrentCookies()));
                    else if (uri.toString().contains("spotifycdn.com/audio/")) return EMPTY_VIDEO_RESPONSE;

                    return super.shouldInterceptRequest(view, request);
                }

                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    Logger.printInfo(() -> "Page started loading: " + url);

                    if (!url.startsWith(OPEN_SPOTIFY_COM_URL)) {
                        return;
                    }

                    Logger.printInfo(() -> "Evaluating script to get session on url: " + url);
                    String getSessionScript = "Object.defineProperty(Object.prototype, \"_username\", {" +
                            "   configurable: true," +
                            "   set(username) {" +
                            "       accessToken = this._builder?.accessToken;" +
                            "       if (accessToken) {" +
                            "           " + JAVASCRIPT_INTERFACE_NAME + ".getSession(username, accessToken);" +
                            "           delete Object.prototype._username;" +
                            "       }" +
                            "       " +
                            "       Object.defineProperty(this, \"_username\", {" +
                            "           configurable: true," +
                            "           enumerable: true," +
                            "           writable: true," +
                            "           value: username" +
                            "       })" +
                            "       " +
                            "   }" +
                            "});" +
                            "if (new URLSearchParams(window.location.search).get('_authfailed') != null) {" +
                            "   " + JAVASCRIPT_INTERFACE_NAME + ".getSession(null, null);" +
                            "}";

                    view.evaluateJavascript(getSessionScript, null);
                }
            });

            webView.addJavascriptInterface(new Object() {
                @SuppressWarnings("unused")
                @JavascriptInterface
                public void getSession(String username, String accessToken) {
                    Session session = new Session(username, accessToken, getCurrentCookies());

                    Utils.runOnMainThread(() -> webViewCallback.onReceivedSession(session));
                }
            }, JAVASCRIPT_INTERFACE_NAME);

            CookieManager.getInstance().removeAllCookies((anyRemoved) -> {
                webViewCallback.onInitialized();

                Logger.printInfo(() -> "Loading URL: " + initialUrl);
                webView.loadUrl(initialUrl);
            });
        });
    }

    private static void destructWebView() {
        if (webView == null) return;
        Utils.runOnMainThreadNowOrLater(() -> {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        });
    }

    private static String getWebUserAgent() {
        String userAgentString = WebSettings.getDefaultUserAgent(Utils.getContext());
        try {
            return new UserAgent(userAgentString)
                    .withCommentReplaced("Android", "Windows NT 10.0; Win64; x64")
                    .withoutProduct("Mobile")
                    .toString();
        } catch (IllegalArgumentException ex) {
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36 Edge/137.0.0.0";
            String fallback = userAgentString;
            Logger.printException(() -> "Failed to get user agent, falling back to " + fallback, ex);
        }

        return userAgentString;
    }

    @NonNull
    private static Dialog newDialog(Context context) {
        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setCancelable(false);

        // Ensure that the keyboard does not cover the webview content.
        Window window = dialog.getWindow();
        //noinspection StatementWithEmptyBody
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(0, 0, 0, insets.getInsets(WindowInsets.Type.ime()).bottom);

                return WindowInsets.CONSUMED;
            });
        } else {
            // TODO: Implement for lower Android versions.
        }
        return dialog;
    }

    private static String getCurrentCookies() {
        CookieManager cookieManager = CookieManager.getInstance();
        return cookieManager.getCookie(OPEN_SPOTIFY_COM_URL);
    }

    private static void setCookies(@NonNull String cookies) {
        CookieManager cookieManager = CookieManager.getInstance();

        String[] cookiesList = cookies.split(";");
        for (String cookie : cookiesList) {
            cookieManager.setCookie(OPEN_SPOTIFY_COM_URL, cookie);
        }
    }
}
