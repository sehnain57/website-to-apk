package com.webtoapk.app;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.LinearLayout;
import com.google.android.gms.ads.*;
import com.google.android.gms.ads.initialization.*;
import com.google.android.gms.ads.interstitial.*;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView web;
    private InterstitialAd inter;
    private String interId = "";
    private int loads = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        JSONObject cfg = Cfg.load(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception e) {}
                return true;
            }
            @Override public void onPageFinished(WebView v, String u) {
                CookieManager.getInstance().flush();
                loads++;
                showInterstitial();
            }
        });
        web.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                try {
                    String name = URLUtil.guessFileName(url, cd, mime);
                    DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
                    rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    rq.addRequestHeader("User-Agent", ua);
                    rq.setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_DOWNLOADS, name);
                    ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(rq);
                } catch (Exception e) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception x) {}
                }
            }
        });

        // AdMob: only active when an App ID was supplied at build time
        JSONObject ad = cfg.optJSONObject("admob");
        if (ad != null && !Cfg.s(ad, "appId").isEmpty()) {
            MobileAds.initialize(this, new OnInitializationCompleteListener() {
                public void onInitializationComplete(InitializationStatus st) {}
            });
            String banner = Cfg.s(ad, "banner");
            if (!banner.isEmpty()) {
                AdView av = new AdView(this);
                av.setAdSize(AdSize.BANNER);
                av.setAdUnitId(banner);
                root.addView(av, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                av.loadAd(new AdRequest.Builder().build());
            }
            interId = Cfg.s(ad, "interstitial");
            if (!interId.isEmpty()) loadInterstitial();
        }
        web.loadUrl(Cfg.s(cfg, "url"));
    }

    private void loadInterstitial() {
        InterstitialAd.load(this, interId, new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
            @Override public void onAdLoaded(InterstitialAd a) { inter = a; }
            @Override public void onAdFailedToLoad(LoadAdError e) { inter = null; }
        });
    }

    private void showInterstitial() {
        if (inter != null && loads >= 4) {   // after every 4th page load
            inter.show(this);
            inter = null; loads = 0;
            loadInterstitial();
        }
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
}
