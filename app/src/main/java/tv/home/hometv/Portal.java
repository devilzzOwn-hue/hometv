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
    private volatile String endpoint;
    private volatile String token;
    private JSONObject live;
    private long liveAt;
    private final Map<String, String> cmdById = new HashMap<>();
    /** Stream hosts the local proxy is allowed to fetch from. */
    public final Set<String> hosts = Collections.synchronizedSet(new HashSet<String>());

    public Portal(Config cfg) { this.cfg = cfg; }

    /** Category / title names that indicate adult content. */
    static final java.util.regex.Pattern ADULT = java.util.regex.Pattern.compile(
            "(?i)(\\badult|\\bxxx\\b|porn|\\b18\\s*\\+|\\+\\s*18\\b|\\bfor\\s+adults\\b|erotic|\\bx-?rated\\b|playboy|brazzers|hustler|\\bnsfw\\b|\\bsexy?\\b|after\\s*dark|\\bhot\\s+(movies|videos|clips)\\b|\\bblue\\s+films?\\b)");
    static boolean adultName(String s) { return s != null && ADULT.matcher(s).find(); }
    private static boolean censored(JSONObject o) {
        String c = o.optString("censored", "0");
        return "1".equals(c) || "true".equalsIgnoreCase(c);
    }
    /** Adult category ids per module ("vod", "series"), learned when categories are listed. */
    private final Map<String, Set<String>> adultCats = new HashMap<>();
    public boolean isAdultCategory(String module, String id) {
        Set<String> s;
        synchronized (adultCats) { s = adultCats.get(module); }
        if (s == null) { try { categories(module); } catch (PortalException ignored) { } synchronized (adultCats) { s = adultCats.get(module); } }
        return s != null && s.contains(id);
    }

    /** Recent portal requests and replies, shown on the /debug page (MAC address never included). */
    private final java.util.LinkedList<String> log = new java.util.LinkedList<>();
    private static final String[] LOG_KEYS = {"type", "action", "cmd", "series", "movie_id", "season_id", "category", "search", "ch_id", "p"};

    private void addLog(String line) {
        synchronized (log) {
            log.addLast(new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()) + "  " + line);
            while (log.size() > 60) log.removeFirst();
        }
    }

    public String debugLog() {
        StringBuilder sb = new StringBuilder();
        synchronized (log) { for (String l : log) sb.append(l).append("\n\n"); }
        return sb.toString();
    }

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
            StringBuilder req = new StringBuilder("-> ");
            for (String k : LOG_KEYS) if (params.containsKey(k)) req.append(k).append('=').append(params.get(k)).append("  ");
            addLog(req.toString().trim());
            int code = c.getResponseCode();
            if (code >= 400) { addLog("<- HTTP " + code); throw new PortalException("HTTP " + code + " from " + url); }
            String raw = readAll(c.getInputStream());
            String action = params.get("action");
            if (!"get_all_channels".equals(action) && !"get_genres".equals(action) && !"get_categories".equals(action)) {
                addLog("<- " + (raw.length() > 600 ? raw.substring(0, 600) + "..." : raw));
            } else {
                addLog("<- (" + raw.length() + " bytes)");
            }
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
    /** Calls the portal API, logging in again once if the token has expired. Returns the "js" payload.
     *  Requests run in parallel; only logging in is serialized. */
    public Object call(Map<String, String> params, int timeoutMs) throws PortalException {
        params.put("JsHttpRequest", "1-xml");
        for (int attempt = 1; ; attempt++) {
            String tok = token;
            if (tok == null) {
                synchronized (this) { if (token == null) login(); }
                tok = token;
            }
            try {
                JSONObject d = get(endpoint, params, true, timeoutMs);
                if (d.has("js")) {
                    Object js = d.opt("js");
                    return js == JSONObject.NULL ? null : js;
                }
                throw new PortalException("Portal reply had no data");
            } catch (PortalException e) {
                if (attempt >= 2) throw e;
                synchronized (this) { if (token != null && token.equals(tok)) token = null; }
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
                    genres.put(new JSONObject().put("id", o.optString("id")).put("title", o.optString("title"))
                            .put("adult", adultName(o.optString("title")) || censored(o)));
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

            Set<String> adultGenres = new HashSet<>();
            for (int i = 0; i < genres.length(); i++) if (genres.getJSONObject(i).optBoolean("adult")) adultGenres.add(genres.getJSONObject(i).optString("id"));
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
                        .put("genre", ch.optString("tv_genre_id", ""))
                        .put("adult", censored(ch) || adultGenres.contains(ch.optString("tv_genre_id", "")) || adultName(ch.optString("name"))));
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
    public JSONArray vodCategories() throws PortalException { return categories("vod"); }

    public JSONArray categories(String module) throws PortalException {
        String mod = "series".equals(module) ? "series" : "vod";
        Object js = call(map("type", mod, "action", "get_categories"), 20000);
        JSONArray out = new JSONArray();
        Set<String> adult = new HashSet<>();
        if (js instanceof JSONArray) {
            JSONArray a = (JSONArray) js;
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                boolean ad = adultName(c.optString("title")) || censored(c);
                if (ad) adult.add(c.optString("id"));
                try { out.put(new JSONObject().put("id", c.optString("id")).put("title", c.optString("title")).put("adult", ad)); }
                catch (JSONException ignored) { }
            }
        }
        synchronized (adultCats) { adultCats.put(mod, adult); }
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
            Set<String> adultIds;
            synchronized (adultCats) { adultIds = adultCats.get(module); }
            boolean adult = censored(v) || adultName(v.optString("name"))
                    || (adultIds != null && adultIds.contains(v.optString("category_id", "")));
            items.put(new JSONObject().put("id", v.optString("id")).put("name", v.optString("name"))
                    .put("kind", module).put("is_series", isSeries && series.length() == 0).put("adult", adult)
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

    public JSONObject moduleList(String module, String category, int page) throws PortalException {
        return list("series".equals(module) ? "series" : "vod", category, page, "");
    }

    private final Map<String, Object[]> searchCache = new HashMap<>();
    private final java.util.concurrent.ExecutorService searchPool = java.util.concurrent.Executors.newFixedThreadPool(6);

    private JSONObject searchModule(String module, String q) {
        JSONObject res = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            for (int page = 1; page <= 2; page++) {
                JSONObject r = list(module, "*", page, q);
                JSONArray it = r.optJSONArray("items");
                if (it == null || it.length() == 0) break;
                for (int i = 0; i < it.length(); i++) items.put(it.opt(i));
                if (page >= r.optInt("pages", 1)) break;
            }
            res.put("ok", true);
        } catch (Exception e) {
            try { res.put("ok", false).put("error", String.valueOf(e.getMessage())); } catch (JSONException ignored) { }
        }
        try { res.put("items", items); } catch (JSONException ignored) { }
        return res;
    }

    static String norm(String s) {
        return String.valueOf(s).toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "");
    }

    /** Extra search terms so "spiderman" also finds "Spider-Man" and "kbc crorepati" finds "Crorepati". */
    static List<String> variants(String q) {
        LinkedHashSet<String> v = new LinkedHashSet<>();
        v.add(q.trim());
        String[] words = q.trim().split("\\s+");
        String longest = "";
        for (String w : words) { String n = norm(w); if (n.length() > longest.length()) longest = n; }
        // A short stem of the longest word matches hyphenated / spaced spellings.
        if (longest.length() >= 7) v.add(longest.substring(0, 6));
        else if (words.length > 1 && longest.length() >= 4) v.add(longest);
        List<String> out = new ArrayList<>(v);
        return out.size() > 2 ? out.subList(0, 2) : out;
    }

    /** Title without trailing tags such as "(Hindi-Netflix) (4K)". */
    static String cleanTitle(String name) {
        String t = String.valueOf(name).trim().replaceAll("\\s+[-\u2013]\\s+[A-Z0-9][A-Z0-9 &'.]*\\|[^()\\[\\]]*$", "").trim();
        String prev;
        do { prev = t; t = t.replaceAll("\\s*[\\(\\[][^\\(\\)\\[\\]]{1,40}[\\)\\]]\\s*$", "").trim(); } while (!t.equals(prev) && !t.isEmpty());
        return t.isEmpty() ? String.valueOf(name) : t;
    }

    /** 0 = exact title, 1 = starts with query, 2 = a word starts with it, 3 = contains it, 4 = all words present, 9 = no title match. */
    static int rank(String name, String q) {
        String t = norm(cleanTitle(name)), nq = norm(q);
        if (nq.isEmpty()) return 9;
        if (t.equals(nq)) return 0;
        if (t.startsWith(nq)) return 1;
        String spaced = " " + String.valueOf(cleanTitle(name)).toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ");
        String firstWord = q.trim().toLowerCase(Locale.US).split("[^a-z0-9]+")[0];
        if (!firstWord.isEmpty() && spaced.contains(" " + firstWord) && t.contains(nq)) return 2;
        if (t.contains(nq)) return 3;
        boolean all = true;
        for (String w : q.trim().split("\\s+")) { String nw = norm(w); if (nw.length() >= 2 && !t.contains(nw)) { all = false; break; } }
        return all ? 4 : 9;
    }

    /** True if the title contains the query once spaces, hyphens and punctuation are ignored. */
    static boolean matches(String title, String q) {
        String t = norm(title), nq = norm(q);
        if (nq.isEmpty() || t.contains(nq)) return true;
        for (String w : q.trim().split("\\s+")) {
            String nw = norm(w);
            if (nw.length() >= 3 && !t.contains(nw)) return false;
        }
        return true;
    }

    /** Searches movies (VOD) and series at the same time; results are cached for 10 minutes. */
    public JSONObject search(final String q) throws PortalException {
        final String key = q.toLowerCase(Locale.US).trim();
        synchronized (searchCache) {
            Object[] hit = searchCache.get(key);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 10 * 60 * 1000L) return (JSONObject) hit[1];
        }
        final List<String> terms = variants(q);
        List<java.util.concurrent.Future<JSONObject>> futures = new ArrayList<>();
        final List<String> futureModule = new ArrayList<>();
        final List<Boolean> futureIsVariant = new ArrayList<>();
        for (final String module : new String[]{"series", "vod"}) {
            for (int i = 0; i < terms.size(); i++) {
                final String term = terms.get(i);
                futures.add(searchPool.submit(new java.util.concurrent.Callable<JSONObject>() {
                    @Override public JSONObject call() { return searchModule(module, term); }
                }));
                futureModule.add(module);
                futureIsVariant.add(i > 0);
            }
        }
        try {
            List<JSONObject> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            boolean anyOk = false;
            String firstErr = null;
            Map<String, int[]> counts = new HashMap<>();
            Map<String, Boolean> okByModule = new HashMap<>();
            for (int f = 0; f < futures.size(); f++) {
                JSONObject r;
                try { r = futures.get(f).get(45, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception e) { continue; }
                String module = futureModule.get(f);
                boolean ok = r.optBoolean("ok");
                anyOk |= ok;
                okByModule.put(module, Boolean.TRUE.equals(okByModule.get(module)) || ok);
                if (!ok && firstErr == null && "vod".equals(module)) firstErr = r.optString("error");
                JSONArray it = r.optJSONArray("items");
                if (it == null) continue;
                for (int i = 0; i < it.length(); i++) {
                    JSONObject o = it.optJSONObject(i);
                    if (o == null) continue;
                    // Results from the broader stem search are kept only if they really match the query.
                    if (futureIsVariant.get(f) && !matches(o.optString("name"), q)) continue;
                    if (seen.add(o.optString("kind") + ":" + o.optString("id"))) {
                        found.add(o);
                        int[] c = counts.get(module);
                        if (c == null) { c = new int[1]; counts.put(module, c); }
                        c[0]++;
                    }
                }
            }
            // Best matches first: exact title, then title starting with the query, then word matches, then the rest.
            final java.util.IdentityHashMap<JSONObject, Integer> score = new java.util.IdentityHashMap<>();
            for (JSONObject o : found) {
                int sc = rank(o.optString("name"), q);
                score.put(o, sc);
                try { o.put("match", sc < 9 ? "title" : "other"); } catch (JSONException ignored) { }
            }
            Collections.sort(found, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject x, JSONObject y) { return score.get(x) - score.get(y); }
            });
            JSONArray all = new JSONArray();
            for (JSONObject o : found) all.put(o);
            if (all.length() == 0 && !anyOk) throw new PortalException(firstErr != null ? firstErr : "Search failed");
            JSONObject sources = new JSONObject();
            for (String[] m : new String[][]{{"movies", "vod"}, {"series", "series"}}) {
                int[] c = counts.get(m[1]);
                sources.put(m[0], new JSONObject().put("ok", Boolean.TRUE.equals(okByModule.get(m[1])))
                        .put("count", c == null ? 0 : c[0]).put("error", ""));
            }
            JSONObject out = new JSONObject().put("items", all).put("sources", sources).put("terms", new JSONArray(terms));
            synchronized (searchCache) {
                if (searchCache.size() > 50) searchCache.clear();
                searchCache.put(key, new Object[]{System.currentTimeMillis(), out});
            }
            return out;
        } catch (JSONException e) {
            throw new PortalException(e.getMessage());
        }
    }

    /** Seasons of a series; each season has its own cmd and a list of episode numbers. */
    private JSONArray dataOf(Object js) { return js instanceof JSONObject ? ((JSONObject) js).optJSONArray("data") : null; }

    /** Seasons of a show. Two portal layouts are supported:
     *  - season entries that carry a cmd plus a list of episode numbers (played with create_link ... series=N);
     *  - season entries with only an id; their episodes are fetched separately (see episodes()).
     *  Tries the module the show came from first, then the other one. Each entry also says which module answered. */
    public JSONArray seasons(String id, String module) throws PortalException {
        String first = "series".equals(module) ? "series" : "vod";
        String second = "series".equals(first) ? "vod" : "series";
        PortalException err = null;
        for (String mod : new String[]{first, second}) {
            JSONArray data;
            try {
                data = dataOf(call(map("type", mod, "action", "get_ordered_list", "movie_id", id,
                        "season_id", "0", "episode_id", "0", "p", "1"), 30000));
            } catch (PortalException e) { if (err == null) err = e; continue; }
            if (data == null || data.length() == 0) continue;
            JSONArray out = new JSONArray();
            for (int i = 0; i < data.length(); i++) {
                JSONObject se = data.optJSONObject(i);
                if (se == null) continue;
                String name = se.optString("name", "");
                if (name.isEmpty() || "null".equals(name)) name = "Season " + (i + 1);
                String cmd = se.optString("cmd", "");
                JSONArray eps = new JSONArray();
                JSONArray sr = se.optJSONArray("series");
                if (sr != null) for (int j = 0; j < sr.length(); j++) eps.put(String.valueOf(sr.opt(j)));
                try {
                    JSONObject o = new JSONObject().put("name", name).put("module", mod).put("season_id", se.optString("id", ""));
                    if (!cmd.isEmpty() && !"null".equals(cmd)) {
                        if (eps.length() == 0) eps.put("1");
                        o.put("cmd", cmd).put("series", eps);
                    } else {
                        o.put("lazy", true).put("series", eps);   // episodes come from episodes()
                    }
                    out.put(o);
                } catch (JSONException ignored) { }
            }
            if (out.length() > 0) return out;
        }
        if (err != null) throw err;
        return new JSONArray();
    }

    /** Episodes of one season (for portals where seasons don't carry a cmd). */
    public JSONArray episodes(String id, String module, String seasonId) throws PortalException {
        String mod = "series".equals(module) ? "series" : "vod";
        JSONArray out = new JSONArray();
        for (int page = 1; page <= 6; page++) {
            Object js = call(map("type", mod, "action", "get_ordered_list", "movie_id", id,
                    "season_id", seasonId, "episode_id", "0", "p", String.valueOf(page)), 30000);
            JSONArray data = dataOf(js);
            if (data == null || data.length() == 0) break;
            for (int i = 0; i < data.length(); i++) {
                JSONObject ep = data.optJSONObject(i);
                if (ep == null) continue;
                String num = ep.optString("series_number", "");
                if (num.isEmpty() || "null".equals(num)) num = String.valueOf(out.length() + 1);
                String name = ep.optString("name", "");
                if (name.isEmpty() || "null".equals(name)) name = "Episode " + num;
                String cmd = ep.optString("cmd", "");
                try {
                    out.put(new JSONObject().put("num", num).put("name", name).put("episode_id", ep.optString("id", ""))
                            .put("cmd", "null".equals(cmd) ? "" : cmd));
                } catch (JSONException ignored) { }
            }
            int total = ((JSONObject) js).optInt("total_items", 0);
            if (out.length() >= total) break;
        }
        return out;
    }

    // ---------------------------------------------------------------- playback
    private static final String PREFIX = "^\\s*(ffmpeg|ffrt\\d*|auto)\\s+";

    private final ThreadLocal<Object> lastLink = new ThreadLocal<>();

    /** The portal's reply to the last successful create_link on this thread (holds subtitles, if any). */
    public Object lastLinkReply() { return lastLink.get(); }

    /** Subtitle tracks the portal attached to a create_link reply: [{url, lang, label}]. */
    public JSONArray subtitles(Object reply) {
        JSONArray out = new JSONArray();
        if (!(reply instanceof JSONObject)) return out;
        JSONArray subs = ((JSONObject) reply).optJSONArray("subtitles");
        if (subs == null) return out;
        for (int i = 0; i < subs.length() && i < 12; i++) {
            Object o = subs.opt(i);
            String url = "", lang = "", label = "";
            if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                url = j.optString("file", j.optString("url", j.optString("src", "")));
                lang = j.optString("lang", j.optString("language", ""));
                label = j.optString("label", j.optString("name", j.optString("title", "")));
            } else if (o instanceof String) {
                url = (String) o;
            }
            url = abs(url, false);
            if (!url.startsWith("http")) continue;
            try {
                hosts.add(new URL(url).getAuthority());
                out.put(new JSONObject().put("url", "/sub?u=" + enc(url)).put("lang", lang)
                        .put("label", label.isEmpty() ? (lang.isEmpty() ? "Subtitles " + (i + 1) : lang.toUpperCase(Locale.US)) : label));
            } catch (Exception ignored) { }
        }
        return out;
    }

    private Object createLink(String kind, String cmd, String series) throws PortalException {
        return callLink(kind, cmd, series);
    }

    private Object callLink(String kind, String cmd, String series) throws PortalException {
        Object r = call(map("type", "live".equals(kind) ? "itv" : "vod", "action", "create_link",
                "cmd", cmd, "series", series == null ? "" : series, "forced_storage", "undefined",
                "disable_ad", "0", "download", "0", "JsHttpRequest", "1-xml"), 25000);
        lastLink.set(r);
        return r;
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
        return link(kind, cmd, series, id, "", "", "vod");
    }

    public String link(String kind, String cmd, String series, String id, String seasonId, String episodeId, String module) throws PortalException {
        Object last = null;
        if (episodeId != null && !episodeId.isEmpty()) {
            // Episode of a 3-level show: its video files are listed under movie / season / episode.
            String mod = "series".equals(module) ? "series" : "vod";
            List<String> tried = new ArrayList<>();
            if (cmd != null && !cmd.isEmpty()) {
                last = createLink("vod", cmd, "");
                tried.add(cmd);
                String url = extract(last);
                if (url != null) return remember(url);
            }
            JSONArray files = dataOf(call(map("type", mod, "action", "get_ordered_list", "movie_id", id,
                    "season_id", seasonId, "episode_id", episodeId, "p", "1"), 30000));
            for (int i = 0; files != null && i < files.length() && i < 4; i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;
                LinkedHashSet<String> fc = new LinkedHashSet<>();
                if (!f.optString("cmd", "").isEmpty()) fc.add(f.optString("cmd"));
                if (!f.optString("id", "").isEmpty()) fc.add("/media/file_" + f.optString("id") + ".mpg");
                for (String c : fc) {
                    if (tried.contains(c)) continue;
                    last = createLink("vod", c, "");
                    tried.add(c);
                    String url = extract(last);
                    if (url != null) return remember(url);
                }
            }
            throw new PortalException("The portal didn't return a playable link for this episode. Tried: " + tried
                    + ". Last reply: " + snippet(last) + ". Details: open /debug on your phone.");
        }
        List<String> tried = new ArrayList<>();
        LinkedHashSet<String> cmds = new LinkedHashSet<>();
        if (cmd != null && !cmd.trim().isEmpty()) cmds.add(cmd);
        boolean vod = !"live".equals(kind);
        if (vod && id != null && !id.isEmpty()) cmds.add("/media/" + id + ".mpg");

        for (String c : cmds) {
            last = createLink(kind, c, series);
            tried.add(c);
            String url = extract(last);
            if (url != null) return remember(url);
        }
        if (!vod) {
            String direct = String.valueOf(cmd).replaceAll(PREFIX, "").trim();
            if (direct.startsWith("http") && !direct.contains("localhost")) return remember(direct);
        }
        if (vod && id != null && !id.isEmpty()) {
            // Newer portals list a movie's video files separately; each file has its own cmd.
            Object f = call(map("type", "vod", "action", "get_ordered_list", "movie_id", id,
                    "season_id", "0", "episode_id", "0", "p", "1"), 30000);
            JSONArray files = f instanceof JSONObject ? ((JSONObject) f).optJSONArray("data") : null;
            if (files != null) {
                for (int i = 0; i < files.length() && i < 4; i++) {
                    JSONObject file = files.optJSONObject(i);
                    if (file == null) continue;
                    LinkedHashSet<String> fc = new LinkedHashSet<>();
                    if (!file.optString("cmd", "").isEmpty()) fc.add(file.optString("cmd"));
                    if (!file.optString("id", "").isEmpty()) fc.add("/media/file_" + file.optString("id") + ".mpg");
                    for (String c : fc) {
                        if (tried.contains(c)) continue;
                        last = createLink("vod", c, series);
                        tried.add(c);
                        String url = extract(last);
                        if (url != null) return remember(url);
                    }
                }
            }
        }
        throw new PortalException("The portal didn't return a playable link for this item. Tried: " + tried
                + ". Last reply: " + snippet(last) + ". Details: open /debug on your phone.");
    }

    private String remember(String url) {
        try { hosts.add(new URL(url).getAuthority()); } catch (IOException ignored) { }
        return url;
    }
}
