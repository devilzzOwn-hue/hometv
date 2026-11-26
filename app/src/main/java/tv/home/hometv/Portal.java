package tv.home.hometv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Client for Stalker / Ministra IPTV portals, logging in the way a MAG box (and StbEmu) does. */
public class Portal {
    public static final String UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 "
            + "(KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3";
    private static final long CACHE_MS = 6L * 3600 * 1000;

    public static class PortalException extends Exception {
        public PortalException(String m) { super(m); }
    }

    private final Config cfg;
    private String endpoint;
    private String token;
    private JSONObject live;
    private long liveAt;
    private final Map<String, String> cmdById = new HashMap<>();
    /** Stream hosts the local proxy is allowed to fetch from. */
    public final Set<String> hosts = Collections.synchronizedSet(new HashSet<String>());

    public Portal(Config cfg) { this.cfg = cfg; }

    public synchronized void reset() {
        endpoint = null; token = null; live = null; liveAt = 0; cmdById.clear();
    }

    // ---------------------------------------------------------------- helpers
    private String base() {
        String b = cfg.get("portal_url").trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b.replaceAll("/(stalker_portal/)?c$", "");
    }

    private String mac() { return cfg.get("mac").trim().toUpperCase(Locale.US); }

    static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (UnsupportedEncodingException e) { return ""; }
    }

    static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(sep); sb.append(s); }
        return sb.toString();
    }

    private String abs(String path, boolean logo) {
        if (path == null || path.isEmpty() || "null".equals(path)) return "";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        try {
            URL u = new URL(base());
            String root = u.getProtocol() + "://" + u.getAuthority();
            if (path.startsWith("/")) return root + path;
            return root + (logo ? "/stalker_portal/misc/logos/320/" : "/") + path;
        } catch (IOException e) {
            return "";
        }
    }

    private JSONObject get(String url, Map<String, String> params, boolean auth, int timeoutMs) throws PortalException {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url + "?" + q).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("X-User-Agent", "Model: " + cfg.get("model") + "; Link: Ethernet");
            c.setRequestProperty("Referer", base() + "/c/");
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Cookie", "mac=" + enc(mac()) + "; stb_lang=en; timezone=" + enc(cfg.get("timezone")));
            if (auth && token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();
            if (code >= 400) throw new PortalException("HTTP " + code + " from " + url);
            String raw = readAll(c.getInputStream());
            Object v;
            try { v = new JSONTokener(raw).nextValue(); }
            catch (JSONException e) { v = null; }
            if (!(v instanceof JSONObject)) {
                String snip = raw.length() > 160 ? raw.substring(0, 160) : raw;
                throw new PortalException("Unexpected reply from " + url + ": " + snip);
            }
            return (JSONObject) v;
        } catch (IOException e) {
            throw new PortalException("Can't reach " + url + ": " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------------------------------------------------------- login
    public synchronized void login() throws PortalException {
        if (!cfg.isConfigured()) throw new PortalException("Not set up yet. Open Settings and enter your portal URL and MAC address.");
        String b = base();
        LinkedHashSet<String> cands = new LinkedHashSet<>();
        if (endpoint != null) cands.add(endpoint);
        cands.add(b + "/portal.php");
        cands.add(b + "/server/load.php");
        cands.add(b + "/stalker_portal/server/load.php");
        List<String> errors = new ArrayList<>();
        for (String ep : cands) {
            String tok;
            try {
                JSONObject d = get(ep, map("type", "stb", "action", "handshake", "token", "",
                        "prehash", "0", "JsHttpRequest", "1-xml"), false, 25000);
                JSONObject js = d.optJSONObject("js");
                tok = js != null ? js.optString("token", "") : "";
            } catch (PortalException e) {
                errors.add(e.getMessage());
                continue;
            }
            if (tok.isEmpty()) { errors.add(ep + ": no token in handshake reply"); continue; }
            endpoint = ep;
            token = tok;
            profile();
            return;
        }
        throw new PortalException("Could not log in to the portal.\n" + join(errors, "\n"));
    }

    private void profile() throws PortalException {
        String metrics;
        try {
            metrics = new JSONObject().put("mac", mac()).put("sn", cfg.get("serial")).put("type", "STB")
                    .put("model", cfg.get("model")).put("uid", "").put("random", "").toString();
        } catch (JSONException e) { metrics = "{}"; }
        Map<String, String> p = map(
                "type", "stb", "action", "get_profile", "hd", "1",
                "ver", "ImageDescription: 0.2.18-r23-250; ImageDate: Wed Aug 29 10:49:53 EEST 2018; PORTAL version: 5.6.2; "
                        + "API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c",
                "num_banks", "2", "sn", cfg.get("serial"), "stb_type", cfg.get("model"), "client_type", "STB",
                "image_version", "218", "video_out", "hdmi", "device_id", cfg.get("device_id"),
                "device_id2", cfg.get("device_id2"), "signature", "", "auth_second_step", "1",
                "hw_version", "1.7-BD-00", "not_valid_token", "0", "metrics", metrics,
                "hw_version_2", "", "timestamp", String.valueOf(System.currentTimeMillis() / 1000),
                "api_signature", "262", "prehash", "", "JsHttpRequest", "1-xml");
        JSONObject d = get(endpoint, p, true, 25000);
        JSONObject js = d.optJSONObject("js");
        if (js != null && !js.optString("block_msg", "").isEmpty()) {
            token = null;
            throw new PortalException("Portal says: " + js.optString("block_msg").replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim());
        }
    }

    /** Calls the portal API, logging in again once if the token has expired. Returns the "js" payload. */
    public synchronized Object call(Map<String, String> params, int timeoutMs) throws PortalException {
        params.put("JsHttpRequest", "1-xml");
        for (int attempt = 1; ; attempt++) {
            if (token == null) login();
            try {
                JSONObject d = get(endpoint, params, true, timeoutMs);
                if (d.has("js")) {
                    Object js = d.opt("js");
                    return js == JSONObject.NULL ? null : js;
                }
                throw new PortalException("Portal reply had no data");
            } catch (PortalException e) {
                if (attempt >= 2) throw e;
                token = null;
            }
        }
    }

    private List<JSONObject> paged(Map<String, String> params) throws PortalException {
        List<JSONObject> out = new ArrayList<>();
        for (int page = 1; page <= 400; page++) {
            Map<String, String> p = new LinkedHashMap<>(params);
            p.put("p", String.valueOf(page));
            Object js = call(p, 30000);
            if (!(js instanceof JSONObject)) break;
            JSONArray data = ((JSONObject) js).optJSONArray("data");
            if (data == null || data.length() == 0) break;
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o != null) out.add(o);
            }
            if (out.size() >= ((JSONObject) js).optInt("total_items", 0)) break;
        }
        return out;
    }

    // ---------------------------------------------------------------- live TV
    public synchronized JSONObject liveListing(boolean refresh) throws PortalException {
        if (live != null && !refresh && System.currentTimeMillis() - liveAt < CACHE_MS) return live;
        try {
            JSONArray genres = new JSONArray();
            Object g = call(map("type", "itv", "action", "get_genres"), 30000);
            if (g instanceof JSONArray) {
                JSONArray ga = (JSONArray) g;
                for (int i = 0; i < ga.length(); i++) {
                    JSONObject o = ga.optJSONObject(i);
                    if (o == null || "*".equals(o.optString("id"))) continue;
                    genres.put(new JSONObject().put("id", o.optString("id")).put("title", o.optString("title")));
                }
            }
            List<JSONObject> data = new ArrayList<>();
            Object js = call(map("type", "itv", "action", "get_all_channels"), 120000);
            JSONArray arr = js instanceof JSONObject ? ((JSONObject) js).optJSONArray("data")
                    : js instanceof JSONArray ? (JSONArray) js : null;
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) data.add(o);
            }
            if (data.isEmpty()) data = paged(map("type", "itv", "action", "get_ordered_list",
                    "genre", "*", "fav", "0", "sortby", "number"));

            Map<String, String> cmds = new HashMap<>();
            List<JSONObject> channels = new ArrayList<>();
            for (JSONObject ch : data) {
                String cmd = ch.optString("cmd", "");
                if (cmd.isEmpty()) continue;
                String id = ch.optString("id");
                int num;
                try { num = Integer.parseInt(ch.optString("number", "0").trim()); }
                catch (NumberFormatException e) { num = 0; }
                cmds.put(id, cmd);
                channels.add(new JSONObject().put("id", id).put("name", ch.optString("name").trim())
                        .put("number", num).put("logo", abs(ch.optString("logo", ""), true))
                        .put("genre", ch.optString("tv_genre_id", "")));
            }
            Collections.sort(channels, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    int na = a.optInt("number"), nb = b.optInt("number");
                    if (na == 0) na = Integer.MAX_VALUE;
                    if (nb == 0) nb = Integer.MAX_VALUE;
                    if (na != nb) return na < nb ? -1 : 1;
                    return a.optString("name").compareToIgnoreCase(b.optString("name"));
                }
            });
            JSONArray chArr = new JSONArray();
            for (JSONObject c : channels) chArr.put(c);
            cmdById.clear();
            cmdById.putAll(cmds);
            live = new JSONObject().put("genres", genres).put("channels", chArr)
                    .put("loaded_at", System.currentTimeMillis() / 1000);
            liveAt = System.currentTimeMillis();
            return live;
        } catch (JSONException e) {
            throw new PortalException("Couldn't read the channel list: " + e.getMessage());
        }
    }

    public synchronized String cmdFor(String id) throws PortalException {
        if (!cmdById.containsKey(id)) liveListing(true);
        String c = cmdById.get(id);
        if (c == null) throw new PortalException("Unknown channel");
        return c;
    }

    public JSONArray epg(String id) throws PortalException {
        Object js = call(map("type", "itv", "action", "get_short_epg", "ch_id", id, "size", "4"), 20000);
        JSONArray out = new JSONArray();
        if (!(js instanceof JSONArray)) return out;
        JSONArray a = (JSONArray) js;
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null) continue;
            try {
                out.put(new JSONObject().put("title", e.optString("name")).put("desc", e.optString("descr"))
                        .put("start", e.optLong("start_timestamp")).put("end", e.optLong("stop_timestamp")));
            } catch (JSONException ignored) { }
        }
        return out;
    }

    // ---------------------------------------------------------------- movies & series
    public JSONArray vodCategories() throws PortalException {
        Object js = call(map("type", "vod", "action", "get_categories"), 20000);
        JSONArray out = new JSONArray();
        if (!(js instanceof JSONArray)) return out;
        JSONArray a = (JSONArray) js;
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            if (c == null) continue;
            try { out.put(new JSONObject().put("id", c.optString("id")).put("title", c.optString("title"))); }
            catch (JSONException ignored) { }
        }
        return out;
    }

    private JSONArray parseItems(JSONObject o, String module) throws JSONException {
        JSONArray items = new JSONArray();
        JSONArray data = o.optJSONArray("data");
        if (data == null) return items;
        for (int i = 0; i < data.length(); i++) {
            JSONObject v = data.optJSONObject(i);
            if (v == null) continue;
            JSONArray series = new JSONArray();
            JSONArray s = v.optJSONArray("series");
            if (s != null) for (int j = 0; j < s.length(); j++) series.put(String.valueOf(s.opt(j)));
            String year = v.optString("year", "");
            String rating = v.optString("rating_imdb", "");
            String poster = v.optString("screenshot_uri", "");
            if (poster.isEmpty() || "null".equals(poster)) poster = v.optString("cover_big", v.optString("cover", ""));
            boolean isSeries = "series".equals(module) || "1".equals(v.optString("is_series", "0"))
                    || "true".equals(v.optString("is_series", ""));
            items.put(new JSONObject().put("id", v.optString("id")).put("name", v.optString("name"))
                    .put("kind", module).put("is_series", isSeries && series.length() == 0)
                    .put("year", "null".equals(year) ? "" : year)
                    .put("rating", "null".equals(rating) ? "" : rating)
                    .put("desc", v.optString("description", ""))
                    .put("poster", abs(poster, false))
                    .put("cmd", v.optString("cmd", "")).put("series", series));
        }
        return items;
    }

    private JSONObject list(String module, String category, int page, String search) throws PortalException {
        Map<String, String> p = map("type", module, "action", "get_ordered_list",
                "category", category == null || category.isEmpty() ? "*" : category,
                "genre", "*", "sortby", "added", "fav", "0", "p", String.valueOf(page));
        if (search != null && !search.isEmpty()) p.put("search", search);
        Object js = call(p, 30000);
        JSONObject o = js instanceof JSONObject ? (JSONObject) js : new JSONObject();
        try {
            JSONArray items = parseItems(o, module);
            int total = o.optInt("total_items", items.length());
            int per = Math.max(1, o.optInt("max_page_items", 14));
            int pages = total > 0 ? Math.max(1, (total + per - 1) / per) : 1;
            return new JSONObject().put("items", items).put("page", page).put("total", total).put("pages", pages);
        } catch (JSONException e) {
            throw new PortalException("Couldn't read the list: " + e.getMessage());
        }
    }

    public JSONObject vodList(String category, int page, String search) throws PortalException {
        return list("vod", category, page, search);
    }

    /** Searches movies and series (both the VOD module and, where the portal has one, the Series module). */
    public JSONObject search(String q) throws PortalException {
        JSONArray all = new JSONArray();
        Set<String> seen = new HashSet<>();
        PortalException first = null;
        for (String module : new String[]{"vod", "series"}) {
            for (int page = 1; page <= 3; page++) {
                JSONObject r;
                try { r = list(module, "*", page, q); }
                catch (PortalException e) { if (first == null && "vod".equals(module)) first = e; break; }
                JSONArray items = r.optJSONArray("items");
                if (items == null || items.length() == 0) break;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    String key = module + ":" + it.optString("id");
                    if (seen.add(key)) all.put(it);
                }
                if (page >= r.optInt("pages", 1)) break;
            }
        }
        if (all.length() == 0 && first != null) throw first;
        try { return new JSONObject().put("items", all); }
        catch (JSONException e) { throw new PortalException(e.getMessage()); }
    }

    /** Seasons of a series; each season has its own cmd and a list of episode numbers. */
    public JSONArray seasons(String id, String module) throws PortalException {
        Object js = call(map("type", "series".equals(module) ? "series" : "vod", "action", "get_ordered_list",
                "movie_id", id, "season_id", "0", "episode_id", "0", "p", "1"), 30000);
        JSONArray out = new JSONArray();
        JSONArray data = js instanceof JSONObject ? ((JSONObject) js).optJSONArray("data") : null;
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject se = data.optJSONObject(i);
            if (se == null || se.optString("cmd", "").isEmpty()) continue;
            JSONArray eps = new JSONArray();
            JSONArray s = se.optJSONArray("series");
            if (s != null) for (int j = 0; j < s.length(); j++) eps.put(String.valueOf(s.opt(j)));
            if (eps.length() == 0) eps.put("1");
            try { out.put(new JSONObject().put("name", se.optString("name", "Season " + (i + 1))).put("cmd", se.optString("cmd")).put("series", eps)); }
            catch (JSONException ignored) { }
        }
        return out;
    }

    // ---------------------------------------------------------------- playback
    private static final String PREFIX = "^\\s*(ffmpeg|ffrt\\d*|auto)\\s+";

    private Object createLink(String kind, String cmd, String series) throws PortalException {
        return call(map("type", "live".equals(kind) ? "itv" : "vod", "action", "create_link",
                "cmd", cmd, "series", series == null ? "" : series, "forced_storage", "undefined",
                "disable_ad", "0", "download", "0", "JsHttpRequest", "1-xml"), 25000);
    }

    private static String extract(Object js) {
        if (!(js instanceof JSONObject)) return null;
        String url = ((JSONObject) js).optString("cmd", "").replaceAll(PREFIX, "").trim();
        return url.startsWith("http") ? url : null;
    }

    private static String snippet(Object js) {
        String t = String.valueOf(js);
        return t.length() > 220 ? t.substring(0, 220) + "…" : t;
    }

    public String link(String kind, String cmd, String series, String id) throws PortalException {
        Object js = createLink(kind, cmd, series);
        String url = extract(js);
        Object last = js;
        if (url == null) {
            String direct = String.valueOf(cmd).replaceAll(PREFIX, "").trim();
            if (direct.startsWith("http") && !direct.contains("localhost")) url = direct;
        }
        if (url == null && "vod".equals(kind) && id != null && !id.isEmpty()) {
            // Newer portals list a movie's video files separately; each file has its own cmd.
            Object f = call(map("type", "vod", "action", "get_ordered_list", "movie_id", id,
                    "season_id", "0", "episode_id", "0", "p", "1"), 30000);
            JSONArray files = f instanceof JSONObject ? ((JSONObject) f).optJSONArray("data") : null;
            if (files != null) {
                for (int i = 0; i < files.length() && url == null; i++) {
                    JSONObject file = files.optJSONObject(i);
                    if (file == null || file.optString("cmd", "").isEmpty()) continue;
                    last = createLink("vod", file.optString("cmd"), series);
                    url = extract(last);
                }
            } else {
                last = f;
            }
        }
        if (url == null) {
            throw new PortalException("The portal didn't return a playable link for this item. Portal replied: " + snippet(last));
        }
        try { hosts.add(new URL(url).getAuthority()); } catch (IOException ignored) { }
        return url;
    }
}
