package com.webtoapk.app;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.*;
import com.google.android.gms.ads.*;
import com.google.android.gms.ads.appopen.*;
import com.google.android.gms.ads.initialization.*;
import com.google.android.gms.ads.interstitial.*;
import com.google.android.gms.ads.nativead.*;
import com.google.android.gms.ads.rewarded.*;
import com.google.android.gms.ads.rewardedinterstitial.*;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final long MAX_WAIT_MS = 10000;
    private WebView web;
    private LinearLayout col;
    private View splash;
    private boolean pageDone = false, minElapsed = false, splashGone = false;
    private int oldStatus, oldNav;
    private long startTime;

    // ad unit ids (empty = that format is skipped)
    private String bannerId = "", interId = "", riId = "", rwId = "", nativeId = "", openId = "";
    private AdView bannerView;
    private InterstitialAd inter;
    private RewardedInterstitialAd ri;
    private RewardedAd rw;
    private AppOpenAd open;
    private long openLoaded;
    private boolean busy = false, bgFlag = false, coldPending = true;
    private long lastEnd = 0;
    private int cntI = 0, cntRI = 0;
    private Button rwBtn;
    private NativeAdView nativeView;
    private NativeAd nativeAd;
    private TextView nHead, nBody;
    private ImageView nIcon;
    private MediaView nMedia;
    private Button nCta;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startTime = SystemClock.elapsedRealtime();
        JSONObject cfg = Cfg.load(this);
        JSONObject sp = cfg.optJSONObject("splash");
        if (sp == null) sp = new JSONObject();
        int bg = col(sp.optString("background", "#4f46e5"), 0xFF4F46E5);
        int fg = col(sp.optString("textColor", "#ffffff"), Color.WHITE);
        long dur = sp.optLong("durationMs", 2500);

        JSONObject ad = cfg.optJSONObject("admob");
        boolean ads = ad != null && !Cfg.s(ad, "appId").isEmpty();
        if (ads) {
            bannerId = Cfg.s(ad, "banner");
            interId = Cfg.s(ad, "interstitial");
            riId = Cfg.s(ad, "rewardedInterstitial");
            rwId = Cfg.s(ad, "rewarded");
            nativeId = Cfg.s(ad, "native");
            openId = Cfg.s(ad, "appOpen");
        }

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        web = new WebView(this);
        col.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (!nativeId.isEmpty()) buildNativeView();      // sits between web and banner
        if (!bannerId.isEmpty()) {
            bannerView = new AdView(this);
            bannerView.setAdSize(AdSize.BANNER);
            bannerView.setAdUnitId(bannerId);
            col.addView(bannerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        FrameLayout root = new FrameLayout(this);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!rwId.isEmpty()) {                            // floating "Watch ad" button for the rewarded ad
            rwBtn = new Button(this);
            rwBtn.setText("▶ Watch ad");
            rwBtn.setAllCaps(false);
            rwBtn.setTextColor(fg);
            GradientDrawable g = new GradientDrawable();
            g.setColor(bg);
            g.setCornerRadius(dp(24));
            rwBtn.setBackground(g);
            rwBtn.setVisibility(View.GONE);
            rwBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showRewarded(); } });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.setMargins(0, 0, dp(12), dp(bannerId.isEmpty() ? 16 : 70));
            root.addView(rwBtn, lp);
        }
        splash = buildSplash(cfg, sp, bg, fg);
        root.addView(splash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        oldStatus = getWindow().getStatusBarColor();
        oldNav = getWindow().getNavigationBarColor();
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

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
                pageDone = true;
                hideSplashIfReady();
                if (splashGone) onPageLoadedForAds();
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

        if (ads) {
            MobileAds.initialize(this, new OnInitializationCompleteListener() {
                public void onInitializationComplete(InitializationStatus st) {}
            });
            loadInter(); loadRI(); loadRw(); loadOpen();   // each one skips itself if its id is empty
        }

        web.loadUrl(Cfg.s(cfg, "url"));    // website loads while splash is visible

        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() { public void run() { minElapsed = true; hideSplashIfReady(); } }, dur);
        h.postDelayed(new Runnable() { public void run() { pageDone = true; minElapsed = true; hideSplashIfReady(); } }, Math.max(dur, MAX_WAIT_MS));
    }

    // ---------------- splash ----------------
    private View buildSplash(JSONObject cfg, JSONObject sp, int bg, int fg) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(bg);
        l.setClickable(true);
        int px = dp(120);
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.logo);
        l.addView(iv, new LinearLayout.LayoutParams(px, px));
        if (sp.optBoolean("showName", true)) {
            TextView t = new TextView(this);
            t.setText(Cfg.s(cfg, "name"));
            t.setTextColor(fg);
            t.setTextSize(22);
            t.setTypeface(null, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            t.setPadding(24, px / 6, 24, 0);
            l.addView(t);
        }
        return l;
    }

    private void hideSplashIfReady() {
        if (splashGone || !minElapsed || !pageDone) return;
        splashGone = true;
        splash.animate().alpha(0f).setDuration(300).withEndAction(new Runnable() {
            public void run() {
                splash.setVisibility(View.GONE);
                getWindow().setStatusBarColor(oldStatus);
                getWindow().setNavigationBarColor(oldNav);
                if (bannerView != null) bannerView.loadAd(new AdRequest.Builder().build());
                loadNative();
                updateRewardBtn();
                maybeShowOpen();
            }
        }).start();
    }

    // ---------------- ads ----------------
    private AdRequest req() { return new AdRequest.Builder().build(); }

    private FullScreenContentCallback fsc(final Runnable after) {
        busy = true;
        return new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() { busy = false; lastEnd = SystemClock.elapsedRealtime(); if (after != null) after.run(); }
            @Override public void onAdFailedToShowFullScreenContent(AdError e) { busy = false; if (after != null) after.run(); }
        };
    }

    private void onPageLoadedForAds() {
        cntI++; cntRI++;
        if (inter != null && cntI >= 4) { cntI = 0; showInterstitial(); }                 // every 4th page
        else if (ri != null && cntRI >= 7) { cntRI = 0; offerRewardedInterstitial(); }    // every 7th page
    }

    // Interstitial
    private void loadInter() {
        if (interId.isEmpty()) return;
        InterstitialAd.load(this, interId, req(), new InterstitialAdLoadCallback() {
            @Override public void onAdLoaded(InterstitialAd a) { inter = a; }
            @Override public void onAdFailedToLoad(LoadAdError e) { inter = null; }
        });
    }
    private void showInterstitial() {
        if (inter == null || busy) return;
        InterstitialAd a = inter; inter = null;
        a.setFullScreenContentCallback(fsc(new Runnable() { public void run() { loadInter(); } }));
        a.show(this);
    }

    // Rewarded interstitial (asks first, user can say no)
    private void loadRI() {
        if (riId.isEmpty()) return;
        RewardedInterstitialAd.load(this, riId, req(), new RewardedInterstitialAdLoadCallback() {
            @Override public void onAdLoaded(RewardedInterstitialAd a) { ri = a; }
            @Override public void onAdFailedToLoad(LoadAdError e) { ri = null; }
        });
    }
    private void offerRewardedInterstitial() {
        if (ri == null || busy) return;
        new AlertDialog.Builder(this).setTitle("Support this app")
            .setMessage("Watch a short video to support us?")
            .setPositiveButton("Watch", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    if (ri == null || busy) return;
                    RewardedInterstitialAd a = ri; ri = null;
                    a.setFullScreenContentCallback(fsc(new Runnable() { public void run() { loadRI(); } }));
                    a.show(MainActivity.this, new OnUserEarnedRewardListener() {
                        public void onUserEarnedReward(RewardItem item) { Toast.makeText(MainActivity.this, "Thanks for your support!", Toast.LENGTH_SHORT).show(); }
                    });
                }
            }).setNegativeButton("No thanks", null).show();
    }

    // Rewarded (floating button)
    private void loadRw() {
        if (rwId.isEmpty()) return;
        RewardedAd.load(this, rwId, req(), new RewardedAdLoadCallback() {
            @Override public void onAdLoaded(RewardedAd a) { rw = a; updateRewardBtn(); }
            @Override public void onAdFailedToLoad(LoadAdError e) { rw = null; updateRewardBtn(); }
        });
    }
    private void showRewarded() {
        if (rw == null || busy) return;
        RewardedAd a = rw; rw = null; updateRewardBtn();
        a.setFullScreenContentCallback(fsc(new Runnable() { public void run() { loadRw(); } }));
        a.show(this, new OnUserEarnedRewardListener() {
            public void onUserEarnedReward(RewardItem item) { Toast.makeText(MainActivity.this, "Thanks! Reward earned.", Toast.LENGTH_SHORT).show(); }
        });
    }
    private void updateRewardBtn() {
        if (rwBtn != null) rwBtn.setVisibility(rw != null && splashGone ? View.VISIBLE : View.GONE);
    }

    // App open (cold start after splash, and when returning to the app)
private void loadOpen() {
    if (openId.isEmpty()) return;
    AppOpenAd.load(this, openId, req(), new AppOpenAd.AppOpenAdLoadCallback() {
        @Override public void onAdLoaded(AppOpenAd a) {
            open = a; openLoaded = SystemClock.elapsedRealtime();
            if (coldPending && splashGone && SystemClock.elapsedRealtime() - startTime < 20000) maybeShowOpen();
        }
        @Override public void onAdFailedToLoad(LoadAdError e) { open = null; }
    });
}
    private void maybeShowOpen() {
        if (open == null || busy || !splashGone) return;
        long now = SystemClock.elapsedRealtime();
        if (now - openLoaded > 4 * 3600 * 1000L) { open = null; loadOpen(); return; }
        if (now - lastEnd < 3000) return;
        AppOpenAd a = open; open = null; coldPending = false;
        a.setFullScreenContentCallback(fsc(new Runnable() { public void run() { loadOpen(); } }));
        a.show(this);
    }

    // Native advanced (dismissible card above the banner)
    private void buildNativeView() {
        nativeView = new NativeAdView(this);
        nativeView.setVisibility(View.GONE);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(0xFFF5F5F5);
        c.setPadding(dp(10), dp(6), dp(10), dp(8));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        nIcon = new ImageView(this);
        row.addView(nIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(8), 0, dp(8), 0);
        nHead = new TextView(this); nHead.setTextColor(0xFF111111); nHead.setTypeface(null, Typeface.BOLD); nHead.setMaxLines(1);
        nBody = new TextView(this); nBody.setTextColor(0xFF555555); nBody.setTextSize(12); nBody.setMaxLines(2);
        txt.addView(nHead); txt.addView(nBody);
        row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView badge = new TextView(this); badge.setText("Ad"); badge.setTextSize(11); badge.setTextColor(0xFF888888);
        row.addView(badge);
        TextView close = new TextView(this); close.setText("  ✕"); close.setTextSize(16); close.setTextColor(0xFF666666);
        close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { nativeView.setVisibility(View.GONE); } });
        row.addView(close);
        c.addView(row);
        nMedia = new MediaView(this);
        c.addView(nMedia, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        nCta = new Button(this);
        c.addView(nCta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        nativeView.addView(c);
        nativeView.setHeadlineView(nHead); nativeView.setBodyView(nBody); nativeView.setIconView(nIcon);
        nativeView.setMediaView(nMedia); nativeView.setCallToActionView(nCta);
        col.addView(nativeView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    private void loadNative() {
        if (nativeId.isEmpty() || nativeView == null) return;
        new AdLoader.Builder(this, nativeId).forNativeAd(new NativeAd.OnNativeAdLoadedListener() {
            @Override public void onNativeAdLoaded(NativeAd ad) {
                if (isDestroyed()) { ad.destroy(); return; }
                if (nativeAd != null) nativeAd.destroy();
                nativeAd = ad;
                nHead.setText(ad.getHeadline());
                nBody.setText(ad.getBody() != null ? ad.getBody() : "");
                if (ad.getIcon() != null) { nIcon.setImageDrawable(ad.getIcon().getDrawable()); nIcon.setVisibility(View.VISIBLE); }
                else nIcon.setVisibility(View.GONE);
                nCta.setText(ad.getCallToAction() != null ? ad.getCallToAction() : "Open");
                if (ad.getMediaContent() != null) nMedia.setMediaContent(ad.getMediaContent());
                nativeView.setNativeAd(ad);
                nativeView.setVisibility(View.VISIBLE);
            }
        }).build().loadAd(req());
    }

    // ---------------- misc ----------------
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private int col(String s, int d) { try { return Color.parseColor(s); } catch (Exception e) { return d; } }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onStop() { super.onStop(); bgFlag = true; }
    @Override protected void onResume() {
        super.onResume(); web.onResume();
        if (bgFlag) { bgFlag = false; maybeShowOpen(); }
    }
    @Override protected void onDestroy() {
        if (nativeAd != null) nativeAd.destroy();
        if (bannerView != null) bannerView.destroy();
        super.onDestroy();
    }
}
