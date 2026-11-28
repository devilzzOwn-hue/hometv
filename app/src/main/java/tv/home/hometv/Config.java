package tv.home.hometv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.TimeZone;

/** Portal login details, entered once on the Settings screen and stored on the Fire Stick. */
public class Config {
    public static final String[] KEYS = {"portal_url", "mac", "serial", "device_id", "device_id2", "model", "timezone"};

    private final SharedPreferences sp;

    public Config(Context ctx) {
        sp = ctx.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    public String get(String key) {
        String v = sp.getString(key, "");
        if (v == null || v.isEmpty()) {
            if ("model".equals(key)) return "MAG424";
            if ("timezone".equals(key)) return TimeZone.getDefault().getID();
            if ("device_id2".equals(key)) return sp.getString("device_id", "");
            return "";
        }
        return v;
    }

    /** Let phones / laptops on the same Wi-Fi open the full app (not just Settings). */
    public boolean allowLan() { return sp.getBoolean("allow_lan", true); }

    public boolean singleStream() {
        return sp.getBoolean("single_stream", true);
    }

    public boolean isConfigured() {
        return !get("portal_url").isEmpty() && !get("mac").isEmpty();
    }

    public String pin() { return sp.getString("parental_pin", ""); }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        for (String k : KEYS) o.put(k, sp.getString(k, ""));
        o.put("model", get("model"));
        o.put("timezone", get("timezone"));
        o.put("single_stream", singleStream());
        o.put("allow_lan", allowLan());
        o.put("configured", isConfigured());
        o.put("pin_set", !pin().isEmpty());
        return o;
    }

    public void save(JSONObject o) {
        SharedPreferences.Editor e = sp.edit();
        for (String k : KEYS) {
            if (o.has(k)) e.putString(k, o.optString(k, "").trim());
        }
        if (o.has("single_stream")) e.putBoolean("single_stream", o.optBoolean("single_stream", true));
        if (o.has("allow_lan")) e.putBoolean("allow_lan", o.optBoolean("allow_lan", true));
        String pin = o.optString("parental_pin", "").trim();
        if (pin.matches("\\d{4,8}")) e.putString("parental_pin", pin);   // blank = keep the current PIN
        e.commit();
    }
}
