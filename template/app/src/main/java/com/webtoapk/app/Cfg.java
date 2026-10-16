package com.webtoapk.app;
import android.content.Context;
import org.json.JSONObject;
import java.io.*;
class Cfg {
    static JSONObject load(Context c) {
        try {
            InputStream in = c.getAssets().open("config.json");
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return new JSONObject(o.toString("UTF-8"));
        } catch (Exception e) { return new JSONObject(); }
    }
    static String s(JSONObject o, String k) { return (o == null || o.isNull(k)) ? "" : o.optString(k, ""); }
}
