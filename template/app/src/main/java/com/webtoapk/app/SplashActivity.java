package com.webtoapk.app;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONObject;

public class SplashActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        JSONObject cfg = Cfg.load(this);
        JSONObject sp = cfg.optJSONObject("splash");
        if (sp == null) sp = new JSONObject();
        int bg = col(sp.optString("background", "#4f46e5"), 0xFF4F46E5);
        int fg = col(sp.optString("textColor", "#ffffff"), Color.WHITE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(bg);
        int px = (int) (120 * getResources().getDisplayMetrics().density);
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.logo);
        root.addView(iv, new LinearLayout.LayoutParams(px, px));
        if (sp.optBoolean("showName", true)) {
            TextView t = new TextView(this);
            t.setText(Cfg.s(cfg, "name"));
            t.setTextColor(fg);
            t.setTextSize(22);
            t.setTypeface(null, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            t.setPadding(24, px / 6, 24, 0);
            root.addView(t);
        }
        setContentView(root);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                startActivity(new Intent(SplashActivity.this, MainActivity.class));
                finish();
            }
        }, sp.optLong("durationMs", 2500));
    }
    private int col(String s, int d) { try { return Color.parseColor(s); } catch (Exception e) { return d; } }
}
