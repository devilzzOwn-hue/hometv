package tv.home.hometv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny web server running inside the app. The WebView UI talks to it at http://127.0.0.1:PORT,
 * it talks to the IPTV portal, and it relays video streams to the player (browsers can't fetch
 * IPTV streams directly). Devices on the home Wi-Fi may only reach the Setup page.
 */
public class LocalServer {
    private static final String TAG = "HomeTV";
    public final int port;
    private final Context ctx;
    private final Config cfg;
    private final Portal portal;
    private final File prefsFile;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicInteger streamGen = new AtomicInteger();
    private final Object prefsLock = new Object();
    private ServerSocket ss;

    public LocalServer(Context ctx, int port) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
        this.cfg = new Config(this.ctx);
        this.portal = new Portal(cfg);
        this.prefsFile = new File(this.ctx.getFilesDir(), "prefs.json");
    }

    /** Starts listening; returns once the socket is bound (or after 3 s). */
    public void start() {
        final CountDownLatch bound = new CountDownLatch(1);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(port));
                    bound.countDown();
                    if (cfg.isConfigured()) {
                        pool.execute(new Runnable() { @Override public void run() {
                            try { portal.liveListing(false); } catch (Exception e) { Log.w(TAG, "warm-up: " + e.getMessage()); }
                        }});
                    }
                    while (!ss.isClosed()) {
                        final Socket s = ss.accept();
                        pool.execute(new Runnable() { @Override public void run() { handle(s); } });
                    }
                } catch (IOException e) {
                    Log.e(TAG, "server stopped: " + e);
                    bound.countDown();
                }
            }
        }, "hometv-server");
        t.setDaemon(true);
        t.start();
        try { bound.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    // ------------------------------------------------------------------ HTTP plumbing
    private static class Req {
        String method, path, query;
        Map<String, String> headers = new HashMap<>();
        Map<String, String> q = new HashMap<>();
        byte[] body = new byte[0];
        boolean local;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), "ISO-8859-1");
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null) { s.close(); return; }
            String[] parts = line.split(" ");
            if (parts.length < 2) { s.close(); return; }
            Req r = new Req();
            r.method = parts[0];
            String target = parts[1];
            int qi = target.indexOf('?');
            r.path = qi >= 0 ? target.substring(0, qi) : target;
            r.query = qi >= 0 ? target.substring(qi + 1) : "";
            for (String kv : r.query.split("&")) {
                if (kv.isEmpty()) continue;
                int e = kv.indexOf('=');
                if (e < 0) r.q.put(dec(kv), "");
                else r.q.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
            }
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.US), h.substring(c + 1).trim());
            }
            String cl = r.headers.get("content-length");
            if (cl != null) {
                int n = Math.min(Integer.parseInt(cl.trim()), 1_000_000);
                byte[] body = new byte[n];
                int off = 0;
                while (off < n) { int k = in.read(body, off, n - off); if (k < 0) break; off += k; }
                r.body = body;
            }
            r.local = s.getInetAddress().isLoopbackAddress();
            s.setSoTimeout(0);
            route(r, out);
            out.flush();
        } catch (Exception e) {
            Log.d(TAG, "request ended: " + e);
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static void send(OutputStream out, int code, String ctype, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 404 ? "Not Found" : code == 403 ? "Forbidden" : "Error") + "\r\n"
                + "Content-Type: " + ctype + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ISO-8859-1"));
        out.write(body);
    }

    private static void json(OutputStream out, int code, Object o) throws IOException {
        send(out, code, "application/json; charset=utf-8", o.toString().getBytes("UTF-8"));
    }

    private static void error(OutputStream out, int code, String msg) throws IOException {
        try { json(out, code, new JSONObject().put("error", msg)); }
        catch (JSONException e) { send(out, code, "text/plain", msg.getBytes("UTF-8")); }
    }

    private void asset(OutputStream out, String name) throws IOException {
        if (name.contains("..")) { error(out, 404, "Not found"); return; }
        InputStream in;
        try { in = ctx.getAssets().open("web/" + name); }
        catch (IOException e) { error(out, 404, "Not found"); return; }
        byte[] body = readBytes(in);
        String ct = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".png") ? "image/png" : name.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
        send(out, 200, ct, body);
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------ routes
    private void route(Req r, OutputStream out) throws IOException {
        String p = r.path;
        boolean lanAllowed = p.equals("/setup") || p.equals("/api/config") || p.equals("/debug");
        if (!r.local && !lanAllowed) { error(out, 403, "Only the Setup page is available from other devices"); return; }
        try {
            if (p.equals("/") || p.equals("/index.html")) { asset(out, "index.html"); return; }
            if (p.equals("/setup")) { asset(out, "setup.html"); return; }
            if (p.startsWith("/static/")) { asset(out, p.substring("/static/".length())); return; }
            if (p.equals("/api/config")) { config(r, out); return; }
            if (p.equals("/debug")) {
                String body = "Home TV diagnostics: last portal requests (newest at the bottom)\n"
                        + "Portal: " + cfg.get("portal_url") + "   Model: " + cfg.get("model") + "\n\n" + portal.debugLog();
                send(out, 200, "text/plain; charset=utf-8", body.getBytes("UTF-8"));
                return;
            }
            if (p.equals("/api/live")) { json(out, 200, portal.liveListing("1".equals(r.q.get("refresh")))); return; }
            if (p.equals("/api/epg")) { json(out, 200, new JSONObject().put("programs", portal.epg(str(r.q.get("id"))))); return; }
            if (p.equals("/api/vod/categories")) { json(out, 200, new JSONObject().put("categories", portal.vodCategories())); return; }
            if (p.equals("/api/vod/list")) {
                int page = 1;
                try { page = Math.max(1, Integer.parseInt(str(r.q.get("page")))); } catch (NumberFormatException ignored) { }
                json(out, 200, portal.vodList(str(r.q.get("category")), page, str(r.q.get("search")).trim()));
                return;
            }
            if (p.equals("/api/search")) { json(out, 200, portal.search(str(r.q.get("q")).trim())); return; }
            if (p.equals("/api/seasons")) { json(out, 200, new JSONObject().put("seasons", portal.seasons(str(r.q.get("id")), str(r.q.get("module"))))); return; }
            if (p.equals("/api/play")) {
                String url = "live".equals(str(r.q.get("kind")))
                        ? portal.link("live", portal.cmdFor(str(r.q.get("id"))), "", "")
                        : portal.link("vod", str(r.q.get("cmd")), str(r.q.get("series")), str(r.q.get("id")));
                JSONArray subs = "live".equals(str(r.q.get("kind"))) ? new JSONArray() : portal.subtitles(portal.lastLinkReply());
                json(out, 200, new JSONObject().put("type", streamType(url)).put("url", proxied(url)).put("subtitles", subs));
                return;
            }
            if (p.equals("/api/prefs")) { prefs(r, out); return; }
            if (p.equals("/sub")) { subtitle(str(r.q.get("u")), out); return; }
            if (p.equals("/proxy")) { proxy(str(r.q.get("u")), r.headers.get("range"), out); return; }
            error(out, 404, "Not found");
        } catch (Portal.PortalException e) {
            error(out, 502, e.getMessage());
        } catch (JSONException e) {
            error(out, 500, "Internal error: " + e.getMessage());
        }
    }

    private static String str(String s) { return s == null ? "" : s; }

    private void config(Req r, OutputStream out) throws IOException, JSONException {
        if ("POST".equals(r.method)) {
            JSONObject in;
            try { in = new JSONObject(new String(r.body, "UTF-8")); }
            catch (JSONException e) { error(out, 400, "Bad request"); return; }
            cfg.save(in);
            portal.reset();
            JSONObject res = new JSONObject();
            try {
                JSONObject live = portal.liveListing(true);
                res.put("ok", true).put("channels", live.getJSONArray("channels").length());
            } catch (Portal.PortalException e) {
                res.put("ok", false).put("error", e.getMessage());
            }
            json(out, 200, res);
            return;
        }
        JSONObject o = cfg.toJson();
        String ip = lanIp();
        o.put("setup_url", ip == null ? "" : "http://" + ip + ":" + port + "/setup");
        json(out, 200, o);
    }

    private void prefs(Req r, OutputStream out) throws IOException, JSONException {
        synchronized (prefsLock) {
            if ("POST".equals(r.method)) {
                JSONObject in;
                try { in = new JSONObject(new String(r.body, "UTF-8")); }
                catch (JSONException e) { error(out, 400, "Bad request"); return; }
                JSONArray vf = new JSONArray();
                JSONArray inVf = in.optJSONArray("vod_favorites");
                if (inVf != null) for (int i = 0; i < inVf.length() && i < 500; i++) {
                    JSONObject o = inVf.optJSONObject(i);
                    if (o != null) vf.put(o);
                }
                JSONObject clean = new JSONObject()
                        .put("favorites", limit(in.optJSONArray("favorites"), 2000))
                        .put("recent", limit(in.optJSONArray("recent"), 50))
                        .put("vod_favorites", vf);
                FileOutputStream fo = new FileOutputStream(prefsFile);
                fo.write(clean.toString().getBytes("UTF-8"));
                fo.close();
                json(out, 200, clean);
                return;
            }
            JSONObject p = new JSONObject();
            if (prefsFile.exists()) {
                try { p = new JSONObject(new String(readBytes(new FileInputStream(prefsFile)), "UTF-8")); }
                catch (JSONException ignored) { }
            }
            json(out, 200, new JSONObject()
                    .put("favorites", p.optJSONArray("favorites") != null ? p.optJSONArray("favorites") : new JSONArray())
                    .put("recent", p.optJSONArray("recent") != null ? p.optJSONArray("recent") : new JSONArray())
                    .put("vod_favorites", p.optJSONArray("vod_favorites") != null ? p.optJSONArray("vod_favorites") : new JSONArray()));
        }
    }

    private static JSONArray limit(JSONArray a, int max) {
        JSONArray o = new JSONArray();
        if (a != null) for (int i = 0; i < a.length() && i < max; i++) o.put(String.valueOf(a.opt(i)));
        return o;
    }

    // ------------------------------------------------------------------ streams
    static String streamType(String url) {
        String low = url.toLowerCase(Locale.US);
        String path;
        try { path = new URL(low).getPath(); } catch (IOException e) { path = low; }
        if (path.endsWith(".m3u8") || low.contains("extension=m3u8") || path.contains("/hls/")) return "hls";
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") || path.endsWith(".webm")
                || path.endsWith(".mkv") || path.endsWith(".avi")) return "file";
        return "ts";
    }

    static String proxied(String url) { return "/proxy?u=" + Portal.enc(url); }

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    private String rewriteM3u8(String text, URL base) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) { sb.append(line); }
            else if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(line);
                StringBuffer b = new StringBuffer();
                while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement("URI=\"" + proxify(base, m.group(1)) + "\""));
                m.appendTail(b);
                sb.append(b);
            } else {
                sb.append(proxify(base, t));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String proxify(URL base, String u) {
        try {
            URL abs = new URL(base, u.trim());
            portal.hosts.add(abs.getAuthority());
            return proxied(abs.toString());
        } catch (IOException e) {
            return u;
        }
    }

    private void proxy(String target, String range, OutputStream out) throws IOException {
        URL url;
        try { url = new URL(target); } catch (IOException e) { error(out, 400, "Bad stream URL"); return; }
        String proto = url.getProtocol();
        if (!("http".equals(proto) || "https".equals(proto)) || !portal.hosts.contains(url.getAuthority())) {
            error(out, 403, "Stream host not allowed");
            return;
        }
        HttpURLConnection c = null;
        try {
            int code = 0;
            for (int hop = 0; hop < 6; hop++) {
                c = (HttpURLConnection) url.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", Portal.UA);
                c.setRequestProperty("Accept", "*/*");
                if (range != null) c.setRequestProperty("Range", range);
                code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(url, c.getHeaderField("Location"));
                    portal.hosts.add(url.getAuthority());
                    c.disconnect();
                    continue;
                }
                break;
            }
            if (code >= 400 || code == 0) { error(out, 502, "Stream server replied HTTP " + code); return; }
            String ctype = c.getContentType() == null ? "" : c.getContentType();
            InputStream in = c.getInputStream();
            if (url.getPath().toLowerCase(Locale.US).endsWith(".m3u8") || ctype.toLowerCase(Locale.US).contains("mpegurl")) {
                String text = new String(readBytes(in), "UTF-8");
                send(out, 200, "application/vnd.apple.mpegurl", rewriteM3u8(text, url).getBytes("UTF-8"));
                return;
            }
            StringBuilder head = new StringBuilder("HTTP/1.1 " + code + (code == 206 ? " Partial Content" : " OK") + "\r\n");
            for (String hname : new String[]{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"}) {
                String v = c.getHeaderField(hname);
                if (v != null) head.append(hname).append(": ").append(v).append("\r\n");
            }
            head.append("Access-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(head.toString().getBytes("ISO-8859-1"));
            int myGen = -1;
            if (cfg.singleStream() && c.getHeaderField("Content-Length") == null) {
                myGen = streamGen.incrementAndGet(); // a new live stream stops the previous one
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (myGen != -1 && streamGen.get() != myGen) break;
                out.write(buf, 0, n);
                out.flush();
            }
            in.close();
        } catch (IOException e) {
            Log.d(TAG, "stream ended: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Fetches a subtitle file and serves it as WebVTT (SRT is converted). */
    private void subtitle(String target, OutputStream out) throws IOException {
        URL url;
        try { url = new URL(target); } catch (IOException e) { error(out, 400, "Bad subtitle URL"); return; }
        if (!portal.hosts.contains(url.getAuthority())) { error(out, 403, "Subtitle host not allowed"); return; }
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", Portal.UA);
        try {
            if (c.getResponseCode() >= 400) { error(out, 502, "Subtitle server replied HTTP " + c.getResponseCode()); return; }
            byte[] raw = readBytes(c.getInputStream());
            if (raw.length > 5_000_000) { error(out, 413, "Subtitle file too large"); return; }
            String text = new String(raw, "UTF-8");
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            if (!text.startsWith("WEBVTT")) {
                text = "WEBVTT\n\n" + text.replaceAll("(\\d{1,2}:\\d{2}:\\d{2}),(\\d{3})", "$1.$2");
            }
            send(out, 200, "text/vtt; charset=utf-8", text.getBytes("UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ misc
    static String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
