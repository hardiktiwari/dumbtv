package tv.dumbtv.home;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Public "what's on" lists for the apps on the home row. Each list is cached in
 * {@code feeds.json} next to {@code home.conf}. Nothing here is a private catalog:
 * Netflix's chart is the public top 10, YouTube is its trending page, Prime is the
 * public popular list, Tubi is its own featured ids, Pluto is what's on its featured
 * channels now.
 */
final class Feeds {
    static final String NETFLIX = "com.netflix.ninja";
    static final String YOUTUBE = "com.amazon.firetv.youtube";
    static final String PRIME = "com.amazon.firebat";
    static final String TUBI = "com.tubitv.ott";
    static final String PLUTO = "tv.pluto.android";

    private static final String FILE = "feeds.json";
    private static final long MAX_AGE_MS = 6L * 60L * 60L * 1000L;
    private static final String BROWSER =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    static final class Title {
        final int rank;
        final String name;
        final String poster;
        final String open;

        Title(int rank, String name, String poster, String open) {
            this.rank = rank;
            this.name = name;
            this.poster = poster;
            this.open = open;
        }
    }

    static final class Shelf {
        final String heading;
        final String source;
        final List<Title> titles;

        Shelf(String heading, String source, List<Title> titles) {
            this.heading = heading;
            this.source = source;
            this.titles = titles;
        }
    }

    static final class Saved {
        final long at;
        final Map<String, Shelf> shelves;

        Saved(long at, Map<String, Shelf> shelves) {
            this.at = at;
            this.shelves = shelves;
        }

        boolean stale() {
            return shelves.isEmpty() || System.currentTimeMillis() - at > MAX_AGE_MS;
        }

        /** A list that failed last time should be tried again without refetching the others. */
        boolean incomplete() {
            return !shelves.containsKey(NETFLIX) || !shelves.containsKey(YOUTUBE)
                    || !shelves.containsKey(PRIME) || !shelves.containsKey(TUBI)
                    || !shelves.containsKey(PLUTO);
        }
    }

    private Feeds() {}

    static boolean supports(String pkg) {
        return heading(pkg) != null;
    }

    /** Row title for a package that has a list, or null. */
    static String heading(String pkg) {
        if (NETFLIX.equals(pkg)) return "Top on Netflix";
        if (YOUTUBE.equals(pkg)) return "Top videos on YouTube";
        if (PRIME.equals(pkg)) return "Popular on Prime Video";
        if (TUBI.equals(pkg)) return "Featured on Tubi";
        if (PLUTO.equals(pkg)) return "On Pluto TV now";
        return null;
    }

    /** Shown beside the row title when the list is not published by the app itself. */
    static String source(String pkg) {
        if (NETFLIX.equals(pkg)) return "streamrank.io";
        if (PRIME.equals(pkg)) return "justwatch.com";
        return "";
    }

    static Saved load(File dir) {
        File file = new File(dir, FILE);
        if (!file.isFile()) return new Saved(0, new LinkedHashMap<>());
        try {
            byte[] bytes = readFile(file, 800_000);
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            long at = root.optLong("at", 0);
            JSONObject shelves = root.optJSONObject("shelves");
            Map<String, Shelf> map = new LinkedHashMap<>();
            if (shelves != null) {
                JSONArray names = shelves.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String pkg = names.optString(i, "");
                        Shelf shelf = shelf(pkg, shelves.optJSONObject(pkg));
                        if (shelf != null) map.put(pkg, shelf);
                    }
                }
            }
            return new Saved(at, map);
        } catch (IOException | JSONException e) {
            Log.w("dumbtv", "could not read feeds", e);
            return new Saved(0, new LinkedHashMap<>());
        }
    }

    /** Fetches any shelf that is missing or older than {@link #MAX_AGE_MS} and writes the cache. */
    static Saved refresh(File dir, Saved previous) {
        Map<String, Shelf> next = new LinkedHashMap<>();
        if (previous != null) next.putAll(previous.shelves);
        long now = System.currentTimeMillis();
        boolean due = previous == null || previous.stale();
        put(next, NETFLIX, due, () -> netflix());
        put(next, YOUTUBE, due, () -> youtube());
        put(next, PRIME, due, () -> prime());
        put(next, TUBI, due, () -> tubi());
        put(next, PLUTO, due, () -> pluto());
        Saved saved = new Saved(now, next);
        write(dir, saved);
        return saved;
    }

    static Bitmap image(String url, int width, int height) {
        if (url == null || url.isEmpty()) return null;
        try {
            byte[] bytes = getBytes(url, 500_000, BROWSER, null);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= width && o.outHeight / (o.inSampleSize * 2) >= height) {
                o.inSampleSize *= 2;
            }
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (IOException e) {
            Log.w("dumbtv", "poster failed");
            return null;
        }
    }

    private interface Fetch {
        List<Title> get() throws IOException, JSONException;
    }

    private static void put(Map<String, Shelf> shelves, String pkg, boolean due, Fetch fetch) {
        if (!due && shelves.containsKey(pkg)) return;
        Shelf old = shelves.get(pkg);
        try {
            List<Title> titles = fetch.get();
            if (titles == null || titles.isEmpty()) {
                Log.i("dumbtv", "feed " + pkg + " empty");
                return;
            }
            shelves.put(pkg, new Shelf(heading(pkg), source(pkg), titles));
            Log.i("dumbtv", "feed " + pkg + " " + titles.size());
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w("dumbtv", "feed " + pkg + " failed: " + e.getMessage());
            if (old != null) shelves.put(pkg, old);
        }
    }

    private static List<Title> netflix() throws IOException, JSONException {
        String body = get("https://streamrank.io/v1/charts?provider=netflix&country=US", 200_000);
        JSONArray charts = new JSONObject(body).getJSONObject("data").optJSONArray("charts");
        if (charts == null) return Collections.emptyList();
        List<List<Title>> groups = new ArrayList<>();
        for (int i = 0; i < charts.length(); i++) {
            JSONArray ranks = charts.getJSONObject(i).optJSONArray("rankings");
            if (ranks == null) continue;
            List<Title> group = new ArrayList<>();
            for (int j = 0; j < ranks.length() && group.size() < 4; j++) {
                JSONObject row = ranks.getJSONObject(j);
                JSONObject title = row.optJSONObject("title");
                if (title == null) continue;
                String name = title.optString("name", "").trim();
                if (name.isEmpty()) continue;
                String poster = title.optString("poster_url", "").replace("/w500/", "/w342/");
                group.add(new Title(row.optInt("rank", group.size() + 1), name, poster,
                        "https://www.netflix.com/search?q=" + Uri.encode(name)));
            }
            if (!group.isEmpty()) groups.add(group);
        }
        return mix(groups, 8);
    }

    /** YouTube's US top-videos chart. The trending page itself ships no titles to this TV. */
    private static final String YOUTUBE_CHART = "PL4fGSI1pDJn69On1f-8NAvX_CYlx7QyZc";

    private static List<Title> youtube() throws IOException {
        String html = get("https://www.youtube.com/playlist?list=" + YOUTUBE_CHART, 1_500_000, BROWSER, "text/html");
        return youtubeTitles(html);
    }

    private static List<Title> youtubeTitles(String html) {
        List<String> ids = new ArrayList<>();
        int i = 0;
        while (ids.size() < 8 && (i = html.indexOf("\"videoId\":\"", i)) >= 0) {
            int start = i + 11;
            int end = html.indexOf('"', start);
            i = Math.max(start, end);
            if (end < 0 || end - start != 11) continue;
            String id = html.substring(start, end);
            if (!ids.contains(id)) ids.add(id);
        }
        List<String> names = new ArrayList<>();
        int j = 0;
        while (names.size() < 8 && (j = html.indexOf("\"label\":\"", j)) >= 0) {
            int start = j + 9;
            int end = start;
            while (end < html.length() && end - start < 180) {
                char c = html.charAt(end);
                if (c == '\\') {
                    end += 2;
                    continue;
                }
                if (c == '"') break;
                end++;
            }
            j = Math.max(end, start + 1);
            String label = unescape(html.substring(start, Math.min(end, html.length()))).trim();
            int cut = duration(label);
            if (cut <= 2 || Character.isDigit(label.charAt(0))) continue;
            names.add(label.substring(0, cut).trim());
        }
        List<Title> titles = new ArrayList<>();
        int n = Math.min(ids.size(), names.size());
        for (int k = 0; k < n; k++) {
            String id = ids.get(k);
            titles.add(new Title(k + 1, names.get(k),
                    "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg",
                    "https://www.youtube.com/watch?v=" + id));
        }
        return titles;
    }

    /** Index where a trailing "3 minutes" / "1 hour" starts, or -1. */
    private static int duration(String label) {
        int best = -1;
        int m = label.indexOf(" minute");
        int h = label.indexOf(" hour");
        for (int at : new int[] {m, h}) {
            if (at <= 2) continue;
            int i = at - 1;
            while (i >= 0 && label.charAt(i) == ' ') i--;
            while (i >= 0 && Character.isDigit(label.charAt(i))) i--;
            if (i > 2 && label.charAt(i) == ' ' && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static List<Title> prime() throws IOException, JSONException {
        String query = "query Popular($country: Country!, $filter: TitleFilter, $first: Int!) {"
                + " popularTitles(country: $country, filter: $filter, first: $first) {"
                + " edges { node { content(country: $country, language: \"en\") {"
                + " title posterUrl } } } } }";
        JSONObject variables = new JSONObject();
        variables.put("country", "US");
        variables.put("first", 8);
        JSONObject filter = new JSONObject();
        filter.put("packages", new JSONArray().put("amp"));
        variables.put("filter", filter);
        JSONObject payload = new JSONObject();
        payload.put("query", query);
        payload.put("variables", variables);
        String body = post("https://apis.justwatch.com/graphql", payload.toString(), 200_000);
        JSONArray edges = new JSONObject(body).getJSONObject("data")
                .getJSONObject("popularTitles").optJSONArray("edges");
        List<Title> titles = new ArrayList<>();
        if (edges == null) return titles;
        for (int i = 0; i < edges.length() && titles.size() < 8; i++) {
            JSONObject content = edges.getJSONObject(i).getJSONObject("node").optJSONObject("content");
            if (content == null) continue;
            String name = content.optString("title", "").trim();
            if (name.isEmpty()) continue;
            String poster = content.optString("posterUrl", "")
                    .replace("{profile}", "s166").replace("{format}", "jpg");
            if (poster.startsWith("/")) poster = "https://images.justwatch.com" + poster;
            titles.add(new Title(titles.size() + 1, name, poster,
                    "https://www.amazon.com/gp/video/search?phrase=" + Uri.encode(name)));
        }
        return titles;
    }

    private static List<Title> tubi() throws IOException, JSONException {
        String device = UUID.randomUUID().toString();
        String startup = get("https://tubitv.com/oz/startup?device_id=" + enc(device) + "&platform=androidtv", 80_000);
        JSONObject staticBody = new JSONObject(startup).optJSONObject("static_config");
        JSONObject body = staticBody == null ? null : staticBody.optJSONObject("body");
        JSONArray ids = body == null ? null : body.optJSONArray("only_on_tubi");
        if (ids == null || ids.length() == 0) ids = body == null ? null : body.optJSONArray("tubi_presents");
        List<Title> titles = new ArrayList<>();
        if (ids == null) return titles;
        for (int i = 0; i < ids.length() && titles.size() < 8; i++) {
            String id = ids.optString(i, "");
            if (id.isEmpty()) continue;
            try {
                String one = get("https://uapi.adrise.tv/cms/content?content_id=" + enc(id)
                        + "&platform=web&device_id=" + enc(device), 80_000);
                JSONObject item = new JSONObject(one);
                String name = item.optString("title", "").trim();
                if (name.isEmpty()) continue;
                String poster = first(item.optJSONArray("landscape_images"));
                if (poster.isEmpty()) poster = first(item.optJSONArray("posterarts"));
                String kind = item.optString("detailed_type", item.optString("type", ""));
                String path = "series".equals(kind) || "s".equals(kind) ? "tv-shows" : "movies";
                titles.add(new Title(titles.size() + 1, name, poster,
                        "https://tubitv.com/" + path + "/" + id));
            } catch (IOException | JSONException e) {
                Log.w("dumbtv", "tubi title skipped");
            }
        }
        return titles;
    }

    private static List<Title> pluto() throws IOException, JSONException {
        String list = get("https://api.pluto.tv/v2/channels", 1_400_000);
        List<String[]> channels = featured(list);
        String start = utc(System.currentTimeMillis());
        String stop = utc(System.currentTimeMillis() + 60L * 60L * 1000L);
        List<Title> titles = new ArrayList<>();
        for (int i = 0; i < channels.size() && titles.size() < 6; i++) {
            String[] channel = channels.get(i);
            String one = get("https://api.pluto.tv/v2/channels/" + channel[0]
                    + "?start=" + enc(start) + "&stop=" + enc(stop), 250_000);
            JSONObject obj = new JSONObject(one);
            JSONArray timelines = obj.optJSONArray("timelines");
            if (timelines == null || timelines.length() == 0) continue;
            JSONObject slot = timelines.getJSONObject(0);
            String name = slot.optString("title", channel[2]).trim();
            JSONObject episode = slot.optJSONObject("episode");
            String poster = "";
            if (episode != null) {
                JSONObject series = episode.optJSONObject("series");
                poster = image(series, "poster16_9");
                if (poster.isEmpty()) poster = image(episode, "poster16_9");
                if (poster.isEmpty()) poster = image(episode, "thumbnail");
            }
            if (poster.isEmpty()) poster = image(obj, "thumbnail");
            titles.add(new Title(titles.size() + 1, name, poster,
                    "https://pluto.tv/live-tv/" + channel[1]));
        }
        return titles;
    }

    /** {@code _id}, slug, and name of channels marked featured. The full guide is about 1 MB. */
    private static List<String[]> featured(String json) {
        List<String[]> out = new ArrayList<>();
        int from = 0;
        while (out.size() < 6 && (from = json.indexOf("\"featured\":true", from)) >= 0) {
            int start = Math.max(0, from - 2200);
            String win = json.substring(start, from);
            int idAt = win.lastIndexOf("\"_id\":\"");
            if (idAt >= 0) {
                String id = field(win, idAt + 7);
                int slugAt = win.indexOf("\"slug\":\"", idAt);
                int nameAt = win.indexOf("\"name\":\"", idAt);
                if (slugAt >= 0 && nameAt >= 0 && id.length() > 8) {
                    out.add(new String[] {id, field(win, slugAt + 8), field(win, nameAt + 8)});
                }
            }
            from += 16;
        }
        return out;
    }

    private static String field(String s, int start) {
        int end = s.indexOf('"', start);
        return end < 0 ? "" : s.substring(start, end);
    }

    private static String image(JSONObject o, String key) {
        if (o == null) return "";
        JSONObject img = o.optJSONObject(key);
        return img == null ? "" : img.optString("path", "");
    }

    private static String first(JSONArray array) {
        if (array == null || array.length() == 0) return "";
        return array.optString(0, "");
    }

    private static List<Title> mix(List<List<Title>> groups, int max) {
        List<Title> out = new ArrayList<>();
        int n = 0;
        boolean more = true;
        while (more && out.size() < max) {
            more = false;
            for (List<Title> group : groups) {
                if (n < group.size() && out.size() < max) {
                    Title t = group.get(n);
                    out.add(new Title(out.size() + 1, t.name, t.poster, t.open));
                    more = true;
                }
            }
            n++;
        }
        return out;
    }

    private static Shelf shelf(String pkg, JSONObject o) {
        if (o == null || heading(pkg) == null) return null;
        JSONArray titles = o.optJSONArray("titles");
        if (titles == null || titles.length() == 0) return null;
        List<Title> list = new ArrayList<>();
        for (int i = 0; i < titles.length(); i++) {
            JSONObject t = titles.optJSONObject(i);
            if (t == null) continue;
            String name = t.optString("name", "").trim();
            String open = t.optString("open", "");
            if (name.isEmpty() || open.isEmpty()) continue;
            list.add(new Title(t.optInt("rank", list.size() + 1), name, t.optString("poster", ""), open));
        }
        if (list.isEmpty()) return null;
        return new Shelf(heading(pkg), source(pkg), list);
    }

    private static void write(File dir, Saved saved) {
        JSONObject shelves = new JSONObject();
        try {
            for (Map.Entry<String, Shelf> e : saved.shelves.entrySet()) {
                JSONArray titles = new JSONArray();
                for (Title t : e.getValue().titles) {
                    JSONObject o = new JSONObject();
                    o.put("rank", t.rank);
                    o.put("name", t.name);
                    o.put("poster", t.poster);
                    o.put("open", t.open);
                    titles.put(o);
                }
                JSONObject shelf = new JSONObject();
                shelf.put("titles", titles);
                shelves.put(e.getKey(), shelf);
            }
            JSONObject root = new JSONObject();
            root.put("at", saved.at);
            root.put("shelves", shelves);
            File tmp = new File(dir, FILE + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(root.toString());
            }
            File dest = new File(dir, FILE);
            if (!tmp.renameTo(dest)) Log.w("dumbtv", "could not replace feeds.json");
        } catch (IOException | JSONException e) {
            Log.w("dumbtv", "could not write feeds", e);
        }
    }

    private static String utc(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'u' && i + 4 < s.length()) {
                    try {
                        b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                        continue;
                    } catch (NumberFormatException ignored) {
                        b.append(n);
                        continue;
                    }
                }
                if (n == 'n') b.append('\n');
                else b.append(n);
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }

    private static String get(String url, int max) throws IOException {
        return get(url, max, "dumbtv-home", "application/json");
    }

    private static String get(String url, int max, String agent, String accept) throws IOException {
        return new String(getBytes(url, max, agent, accept), StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url, int max, String agent, String accept) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("User-Agent", agent);
        if (accept != null) c.setRequestProperty("Accept", accept);
        return read(c, max);
    }

    private static String post(String url, String json, int max) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", "dumbtv-home");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(bytes);
        }
        return new String(read(c, max), StandardCharsets.UTF_8);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private static byte[] read(HttpURLConnection c, int max) throws IOException {
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            return readStream(in, max);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readFile(File file, int max) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readStream(in, max);
        }
    }

    private static byte[] readStream(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) >= 0 && total < max) {
            int take = Math.min(n, max - total);
            out.write(buf, 0, take);
            total += take;
        }
        return out.toByteArray();
    }
}
