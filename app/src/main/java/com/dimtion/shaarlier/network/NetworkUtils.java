package com.dimtion.shaarlier.network;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;
import android.webkit.URLUtil;

import androidx.annotation.NonNull;

import com.dimtion.shaarlier.models.ShaarliAccount;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public abstract class NetworkUtils {
    protected static final int TIME_OUT = 60_000; // Better for mobile connections
    public static String lastTitleFetchError = null;

    private final static String LOGGER_NAME = NetworkUtils.class.getSimpleName();

    private static final String[] DESCRIPTION_SELECTORS = {
            "meta[property=og:description]",
            "meta[name=description]",
            "meta[name=twitter:description]",
            "meta[name=mastodon:description]",
    };
private static final String[] YOUTUBE_URL_PREFIXES = {
        "https://www.youtube.com/watch",
        "https://youtube.com/watch",
        "https://m.youtube.com/watch",
        "https://youtu.be/",
        "https://www.youtube.com/shorts/",
        "https://youtube.com/shorts/",
        "https://m.youtube.com/shorts/",
};

private static boolean isYoutubeUrl(@NonNull String url) {
    for (String prefix : YOUTUBE_URL_PREFIXES) {
        if (url.startsWith(prefix)) {
            return true;
        }
    }
    return false;
}
    /**
     * Check if a string is an url
     * TODO : unit test on this, I'm not quite sure it is perfect...
     */
    public static boolean isUrl(String url) {
        return URLUtil.isValidUrl(url) && !"http://".equals(url);
    }

    /**
     * Change something which is close to a url to something that is really one
     */
    public static String toUrl(String givenUrl) {
        String finalUrl = givenUrl;
        String protocol = "http://";  // Default value
        if ("".equals(givenUrl)) {
            return givenUrl;  // Edge case, maybe need some discussion
        }

        if (!finalUrl.endsWith("/")) {
            finalUrl += '/';
        }

        if (!(finalUrl.startsWith("http://") || finalUrl.startsWith("https://"))) {
            finalUrl = protocol + finalUrl;
        }

        return finalUrl;
    }

    /**
     * Method to test the network connection
     *
     * @return true if the device is connected to the network
     */
    public static boolean testNetwork(@NonNull Activity parentActivity) {
        ConnectivityManager connMgr = (ConnectivityManager) parentActivity.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        return (networkInfo != null && networkInfo.isConnected());
    }
private static String[] loadFromYoutubeOEmbed(@NonNull String url) {
    String title = "";
    String description = "";
    try {
        String oembedUrl = "https://www.youtube.com/oembed?url="
                + java.net.URLEncoder.encode(url, "UTF-8")
                + "&format=json";

        Log.i(LOGGER_NAME, "Loading YouTube oEmbed: " + oembedUrl);

        String json = Jsoup.connect(oembedUrl)
                .ignoreContentType(true)
                .timeout(TIME_OUT)
                .execute()
                .body();

        org.json.JSONObject obj = new org.json.JSONObject(json);
        title = obj.optString("title", "");
        // oEmbed 沒有 description 欄位，這裡用作者名稱代替，格式可自行調整
        String author = obj.optString("author_name", "");
        description = author.isEmpty() ? "" : ("YouTube - " + author);
    } catch (final Exception e) {
        Log.e(LOGGER_NAME, "Failed to load YouTube oEmbed: " + e);
        lastTitleFetchError = e.getClass().getSimpleName() + ": " + e.getMessage();
    }
    return new String[]{title, description};
}
    /**
     * Static method to load the title of a web page
     *
     * @param url the url of the web page
     * @return "" if there is an error, the page title in other cases
     */
    public static String[] loadTitleAndDescription(@NonNull String url) {
    // 新增：YouTube 網址優先用 oEmbed，速度快很多
    if (isYoutubeUrl(url)) {
        String[] oembedResult = loadFromYoutubeOEmbed(url);
        if (!"".equals(oembedResult[0])) {
            return oembedResult; // oEmbed 成功，直接回傳
        }
        // oEmbed 失敗（例如影片被下架），往下 fallback 用原本的方法繼續嘗試
        Log.w(LOGGER_NAME, "YouTube oEmbed failed, falling back to normal scraping");
    }

    String title = "";
    String description = "";
    final Document pageResp;
    try {
        Log.i(LOGGER_NAME, "Loading url: " + url);
        lastTitleFetchError = null; // 重置
        pageResp = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                .cookie("CONSENT", "YES+")   // avoids YouTube's EU cookie-consent redirect
                .timeout(TIME_OUT)
                .followRedirects(true)
                .execute()
                .parse();
        title = pageResp.title();
    } catch (final Exception e) {
        Log.e(LOGGER_NAME, "Failed to load title: " + e);
        lastTitleFetchError = e.getClass().getSimpleName() + ": " + e.getMessage();
        
        return new String[]{title, description};
    }

        // Many ways to get the description
        for (String selector : NetworkUtils.DESCRIPTION_SELECTORS) {
            try {
                description = pageResp.head().select(selector).first().attr("content");
            } catch (final Exception e) {
                Log.e(LOGGER_NAME, "Failed to load description: " + e);
            }
            if (!"".equals(description)) {
                break;
            }
        }
        return new String[]{title, description};
    }

    /**
     * Select the correct network manager based on the passed account
     */
    public static NetworkManager getNetworkManager(ShaarliAccount account) {
        switch (account.getAuthMethod()) {
            case ShaarliAccount.AUTH_METHOD_MOCK:
                Log.i(LOGGER_NAME, "Selected MockNetworkManager (forced)");
                return new MockNetworkManager();
            case ShaarliAccount.AUTH_METHOD_PASSWORD:
                Log.i(LOGGER_NAME, "Selected PasswordNetworkManager (forced)");
                return new PasswordNetworkManager(account);
            case ShaarliAccount.AUTH_METHOD_RESTAPI:
                Log.i(LOGGER_NAME, "Selected RestAPiNetworkManager (forced)");
                return new RestAPINetworkManager(account);
            case ShaarliAccount.AUTH_METHOD_AUTO:
                if (1 == 0) { // Enabled only for debugging purposes
                    Log.i(LOGGER_NAME, "Selected MockNetworkManager (auto)");
                    return new MockNetworkManager();
                }

                if (account.getRestAPIKey() != null && account.getRestAPIKey().length() > 0) {
                    Log.i(LOGGER_NAME, "Selected RestAPiNetworkManager (auto)");
                    return new RestAPINetworkManager(account);
                } else {
                    Log.i(LOGGER_NAME, "Selected PasswordNetworkManager (auto)");
                    return new PasswordNetworkManager(account);
                }
            default:
                throw new RuntimeException("Invalid shaarli auth method");
        }
    }
}
