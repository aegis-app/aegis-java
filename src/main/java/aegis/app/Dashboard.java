package aegis.app;

import aegis.config.AppConfig;
import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Location;
import aegis.news.NewsFeed;
import aegis.radar.Radar;
import aegis.weather.OpenMeteo;
import aegis.weather.WeatherTables;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// ========================================
// 📊 Dashboard — Web ダッシュボード（serve）
// ========================================
// 📌 127.0.0.1 で待ち受ける簡易 Web サーバ。本文（ブリーフィング）・
// 天気表・レーダー・ニュースを表示し、JSON API（/api/briefing、
// /api/history）も出す。外部 API への負荷増幅を避けるため、各セクション
// はキャッシュ（ブリーフィング 60秒、天気 120秒、ニュース 300秒、
// レーダーは鮮度連動）を持つ
public final class Dashboard {
    private Dashboard() {}

    // 🚀 ダッシュボードを起動する（このメソッドは戻らない）
    public static void serve(AppConfig cfg, Engine eng, int port) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        // 🌐 /api/briefing は60秒キャッシュする
        final String[] briefingCache = new String[1];
        final long[] briefingCacheAt = {0};
        server.createContext("/api/briefing", ex -> {
            try {
                long nowMs = System.currentTimeMillis();
                String json;
                synchronized (briefingCache) {
                    if (briefingCache[0] != null && nowMs - briefingCacheAt[0] < 60_000) {
                        json = briefingCache[0];
                    } else {
                        List<Event> events = eng.briefingEvents();
                        json = "{\"events\":[" + events.stream().map(Event::toJson).collect(Collectors.joining(",")) + "]}";
                        briefingCache[0] = json;
                        briefingCacheAt[0] = nowMs;
                    }
                }
                respond(ex, 200, "application/json; charset=utf-8", json);
            } catch (Exception e) {
                try { respond(ex, 500, "application/json; charset=utf-8", "{\"error\":\"briefing failed\"}"); } catch (Exception ignored) {}
            }
        });
        server.createContext("/api/history", ex -> {
            List<Map<String, String>> rows = eng.store().recentHistory(30);
            StringBuilder sb = new StringBuilder("{\"history\":[");
            for (int i = 0; i < rows.size(); i++) {
                Map<String, String> r = rows.get(i);
                if (i > 0) sb.append(',');
                sb.append("{")
                        .append("\"created_at\":").append(Json.quote(r.getOrDefault("created_at", ""))).append(',')
                        .append("\"event_id\":").append(Json.quote(r.getOrDefault("event_id", ""))).append(',')
                        .append("\"kind\":").append(Json.quote(r.getOrDefault("kind", ""))).append(',')
                        .append("\"severity\":").append(Json.quote(r.getOrDefault("severity", ""))).append(',')
                        .append("\"title\":").append(Json.quote(r.getOrDefault("title", ""))).append(',')
                        .append("\"body\":").append(Json.quote(r.getOrDefault("body", "")))
                        .append("}");
            }
            sb.append("]}");
            respond(ex, 200, "application/json; charset=utf-8", sb.toString());
        });
        // 📰 ダッシュボードのブリーフィング本文も /api/briefing と同じ60秒キャッシュを使う
        final String[] dashTextCache = new String[1];
        final long[] dashTextCacheAt = {0};
        server.createContext("/", ex -> {
            if (!"/".equals(ex.getRequestURI().getPath())) { respond(ex, 404, "text/plain; charset=utf-8", "Not Found"); return; }
            String text;
            synchronized (dashTextCache) {
                long nowMs = System.currentTimeMillis();
                if (dashTextCache[0] != null && nowMs - dashTextCacheAt[0] < 60_000) {
                    text = dashTextCache[0];
                } else {
                    try {
                        text = eng.formatBriefing("AEGIS ダッシュボード");
                    } catch (Exception e) {
                        try { respond(ex, 500, "text/plain; charset=utf-8", "briefing failed"); } catch (Exception ignored) {}
                        return;
                    }
                    dashTextCache[0] = text;
                    dashTextCacheAt[0] = nowMs;
                }
            }
            String html = "<!doctype html><html lang='ja'><head><meta charset='utf-8'>"
                    + "<meta http-equiv='refresh' content='60'>"
                    + "<title>AEGIS 防災アシスタント (Java)</title>"
                    + "<style>body{font-family:system-ui,sans-serif;max-width:860px;margin:24px auto;padding:0 16px}"
                    + "pre{white-space:pre-wrap;background:#0b1220;color:#e6edf3;padding:16px;border-radius:12px}"
                    + ".radar{position:relative;display:grid;grid-template-columns:repeat(3,256px);width:768px;margin:8px 0;overflow:hidden}"
                    + ".cell{position:relative;width:256px;height:256px;background:#dfe8f0}"
                    + ".cell img{position:absolute;left:0;top:0;width:256px;height:256px}"
                    + ".radar>img.rv{position:absolute;opacity:.6;z-index:2}"
                    + ".marker{position:absolute;transform:translate(-50%,-90%);font-size:22px;z-index:5;text-shadow:0 0 3px #fff}"
                    + ".attr{color:#666;font-size:12px}.news li{margin:6px 0}"
                    + "table.wx{border-collapse:collapse;margin:4px 0 16px}"
                    + "table.wx th,table.wx td{border:1px solid #b9c6d4;padding:4px 10px;text-align:center;font-size:14px}"
                    + "table.wx th{background:#e8eef5}table.wx td.w{text-align:left}</style></head>"
                    + "<body><h1>🛡️ AEGIS (Java版) — 地震・津波・気象 自律アシスタント</h1><pre>" + esc(text) + "</pre>"
                    + weatherHtmlCached(cfg)
                    + radarHtml(cfg)
                    + newsHtml()
                    + "<p>JSON: <a href='/api/briefing'>/api/briefing</a> / <a href='/api/history'>/api/history</a>（60秒ごとに自動更新）</p></body></html>";
            respond(ex, 200, "text/html; charset=utf-8", html);
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("🛡️ AEGIS-Java dashboard: http://127.0.0.1:" + port + "/  (Ctrl+Cで停止)");
        Object lock = new Object();
        synchronized (lock) { lock.wait(); }
    }

    // ----------------------------------------
    // 🌦️ 天気表セクション
    // ----------------------------------------
    private static String weatherCache;
    private static long weatherCacheAt;

    private static synchronized String weatherHtmlCached(AppConfig cfg) {
        long now = System.currentTimeMillis();
        if (weatherCache != null && now - weatherCacheAt < 120_000) return weatherCache;
        weatherCache = weatherHtml(cfg);
        weatherCacheAt = now;
        return weatherCache;
    }

    private static String popCell(String pop) {
        return (pop == null || pop.isEmpty() || pop.equals("—")) ? "—" : esc(pop) + "%";
    }

    // 🌧️ 地点ごとの天気テーブル（時間別・週間・気象庁降水確率）。取得失敗の地点は省略する
    private static String weatherHtml(AppConfig cfg) {
        StringBuilder sb = new StringBuilder();
        for (Location loc : cfg.locations) {
            WeatherTables t;
            try { t = OpenMeteo.fetchWeatherTables(loc); } catch (Exception e) { continue; }
            if (t.hourly().isEmpty() && t.weekly().isEmpty() && t.pops().isEmpty()) continue;
            sb.append("<h2>☀ 天気（").append(esc(loc.name())).append("）</h2>");
            if (!t.hourly().isEmpty()) {
                sb.append("<h3>時間別予報</h3><table class='wx'><thead><tr><th>時刻</th><th>天気</th><th>気温</th><th>降水確率</th><th>降水量</th></tr></thead><tbody>");
                for (WeatherTables.HourRow r : t.hourly()) {
                    // 🌧️ 3時間区間の最大降水確率が代表値と異なる場合は併記する
                    String popTxt = popCell(r.pop());
                    if (!r.popMax().equals(r.pop()) && !r.popMax().equals("—")) {
                        popTxt += "（区間最大 " + esc(r.popMax()) + "%）";
                    }
                    sb.append("<tr><td>").append(esc(r.label())).append("</td><td class='w'>")
                            .append(esc(r.icon())).append(' ').append(esc(r.desc())).append("</td><td>")
                            .append(esc(r.temp())).append("℃</td><td>").append(popTxt).append("</td><td>")
                            .append(r.precip().equals("—") ? "—" : esc(r.precip()) + "mm").append("</td></tr>");
                }
                sb.append("</tbody></table>");
            }
            if (!t.weekly().isEmpty()) {
                sb.append("<h3>週間予報</h3><table class='wx'><thead><tr><th>日付</th><th>天気</th><th>最低〜最高</th><th>降水確率</th><th>降水量</th></tr></thead><tbody>");
                for (WeatherTables.DayRow r : t.weekly()) {
                    sb.append("<tr><td>").append(esc(r.date())).append("</td><td class='w'>")
                            .append(esc(r.icon())).append(' ').append(esc(r.desc())).append("</td><td>")
                            .append(esc(r.temp())).append("℃</td><td>").append(popCell(r.pop())).append("</td><td>")
                            .append(r.precip().equals("—") ? "—" : esc(r.precip()) + "mm").append("</td></tr>");
                }
                sb.append("</tbody></table>");
            }
            if (!t.pops().isEmpty()) {
                sb.append("<h3>降水確率（気象庁）</h3><table class='wx'><thead><tr><th>日付</th><th>時間帯</th><th>降水確率</th></tr></thead><tbody>");
                for (WeatherTables.PopRow r : t.pops()) {
                    sb.append("<tr><td>").append(esc(r.date())).append("</td><td>")
                            .append(esc(r.label())).append("</td><td>").append(popCell(r.pop())).append("</td></tr>");
                }
                sb.append("</tbody></table>");
            }
        }
        return sb.toString();
    }

    // ----------------------------------------
    // 🌧️ レーダーセクション
    // ----------------------------------------
    private static Radar.RainFrame radarCache;
    private static List<NewsFeed.NewsItem> newsCache;
    private static long newsCacheAt;
    private static long radarCheckedAt;

    // 🧪 フレーム時刻が変わらない場合のみキャッシュを延長する判定（テスト対象）
    public static boolean radarFrameUnchanged(long cachedFrameTime, long fetchedFrameTime) {
        return cachedFrameTime > 0 && cachedFrameTime == fetchedFrameTime;
    }

    // 💾 キャッシュの鮮度連動: 30秒ごとに最新フレームを確認し、フレーム時刻が
    // 変わらない場合のみキャッシュをそのまま延長する（取得時刻だけを更新）。
    // フレームが進んだら即座に差し替えるため、配信遅延にキャッシュが上乗せされ続けない
    private static synchronized Radar.RainFrame radarFrameCached() {
        long now = System.currentTimeMillis();
        if (radarCache != null && now - radarCheckedAt < 30_000) return radarCache;
        try {
            Radar.RainFrame f = Radar.fetchRainFrame();
            if (f != null) {
                if (!radarFrameUnchanged(radarCache == null ? 0 : radarCache.time(), f.time())) {
                    radarCache = f;
                }
                radarCheckedAt = now;
            }
        } catch (Exception ignored) {}
        return radarCache;
    }

    private static Radar.JmaFrame jmaRadarCache;
    private static long jmaRadarCacheAt;

    private static synchronized Radar.JmaFrame jmaRadarFrameCached() {
        long now = System.currentTimeMillis();
        if (jmaRadarCache != null && now - jmaRadarCacheAt < 120_000) return jmaRadarCache;
        try {
            Radar.JmaFrame f = Radar.fetchJmaFrame();
            if (f != null) {
                jmaRadarCache = f;
                jmaRadarCacheAt = now;
            }
        } catch (Exception ignored) {}
        return jmaRadarCache;
    }

    private static synchronized List<NewsFeed.NewsItem> newsCached() {
        long now = System.currentTimeMillis();
        if (newsCache != null && now - newsCacheAt < 300_000) return newsCache;
        try {
            newsCache = NewsFeed.fetchNews();
            newsCacheAt = now;
        } catch (Exception ignored) {}
        return newsCache == null ? List.of() : newsCache;
    }

    // 📡 地点ごとのレーダー（地理院タイル + RainViewer タイルを重ね、ブラウザ側で合成される）。
    // RainViewer が取得できない場合は気象庁ナウキャストへフォールバックし、両方とも
    // 失敗した場合は欄を無言で消さず、その旨を明示する
    private static String radarHtml(AppConfig cfg) {
        Radar.RainFrame f = radarFrameCached();
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>🌧 気象レーダー</h2>");
        if (f == null) {
            Radar.JmaFrame jf = jmaRadarFrameCached();
            if (jf == null) {
                sb.append("<p>レーダーを取得できませんでした（RainViewer・気象庁降水ナウキャストの両方に失敗しました）。通信状態を確認し、時間をおいて再読み込みしてください。</p>");
                return sb.toString();
            }
            String jmaTime = Instant.ofEpochSecond(jf.epochSec()).atZone(ZoneId.of("Asia/Tokyo"))
                    .format(DateTimeFormatter.ofPattern("M月d日 H:mm"));
            String jmaAge = Radar.ageText(jf.epochSec(), Instant.now().getEpochSecond());
            sb.append("<p>RainViewerを取得できないため、気象庁 降水ナウキャストを表示しています。").append(esc(jmaAge)).append("</p>");
            for (Location loc : cfg.locations) {
                sb.append("<h3>").append(esc(loc.name())).append("（").append(jmaTime).append("）</h3>");
                sb.append(jmaRadarTilesHtml(loc.lat(), loc.lon(), jf));
            }
            sb.append("<p class='attr'>レーダー: 気象庁 降水ナウキャスト / 地図: 国土地理院</p>");
            return sb.toString();
        }
        String frameTime = Instant.ofEpochSecond(f.time()).atZone(ZoneId.of("Asia/Tokyo"))
                .format(DateTimeFormatter.ofPattern("M月d日 H:mm"));
        // 🕐 フレームの相対時刻（約N分前の観測。古い場合は注記付き）
        String frameAge = Radar.ageText(f.time(), Instant.now().getEpochSecond());
        sb.append("<p>").append(esc(frameAge)).append("</p>");
        for (Location loc : cfg.locations) {
            sb.append("<h3>").append(esc(loc.name())).append("（").append(frameTime).append("）</h3>");
            sb.append(radarTilesHtml(loc.lat(), loc.lon(), f.path()));
        }
        // 🖥️ 解像度注記: RainViewer の雨タイルは z7 が上限で2倍拡大表示のため実効解像度が下がる
        sb.append("<p class='attr'>注: RainViewer の雨タイルはズーム7が上限のため2倍に拡大して表示しており、実効解像度が低下します。高解像度で確認したい場合は気象庁の降水ナウキャスト（ズーム10まで原生解像度）も参照してください。</p>");
        sb.append("<p class='attr'>レーダー: RainViewer（気象庁データ）/ 地図: 国土地理院</p>");
        return sb.toString();
    }

    // 🌦️ 気象庁ナウキャストの重ね合わせ HTML（純粋関数・テスト対象）。
    // ベース地図と同じ z8 の原生タイルを使うため拡大による解像度低下がない
    public static String jmaRadarTilesHtml(double lat, double lon, Radar.JmaFrame frame) {
        int z = 8;
        double[] frac = Radar.tileFraction(lat, lon, z);
        int cx = (int) Math.floor(frac[0]);
        int cy = (int) Math.floor(frac[1]);
        int mx = (int) Math.round((frac[0] - cx) * 256) + 256;
        int my = (int) Math.round((frac[1] - cy) * 256) + 256;
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='radar'>");
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                sb.append("<div class='cell'>")
                        .append("<img alt='' src='https://cyberjapandata.gsi.go.jp/xyz/std/").append(z).append('/').append(x).append('/').append(y).append(".png'>")
                        .append("</div>");
            }
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int left = (dx + 1) * 256;
                int top = (dy + 1) * 256;
                sb.append("<img alt='' class='rv' style='left:").append(left).append("px;top:").append(top)
                        .append("px;width:256px;height:256px' src='")
                        .append(esc(Radar.jmaTileUrl(frame, z, cx + dx, cy + dy))).append("'>");
            }
        }
        sb.append("<div class='marker' style='left:").append(mx).append("px;top:").append(my).append("px'>📍</div>");
        sb.append("</div>");
        return sb.toString();
    }

    // 📡 RainViewer の重ね合わせ HTML（純粋関数・テスト対象）。ベース地図は地理院タイル
    // z8 の 3×3 グリッド。雨タイルは RainViewer が z7 までしか提供しない（z8 は
    // 「Zoom Level Not Supported」バナー画像が返る）ため、z7 タイルを 512px に拡大し、
    // .radar への絶対配置で重ねる（マーカーより下のレイヤー）
    public static String radarTilesHtml(double lat, double lon, String framePath) {
        int z = 8;
        double[] frac = Radar.tileFraction(lat, lon, z);
        int cx = (int) Math.floor(frac[0]);
        int cy = (int) Math.floor(frac[1]);
        int ox = cx - 1;
        int oy = cy - 1;
        int mx = (int) Math.round((frac[0] - cx) * 256) + 256;
        int my = (int) Math.round((frac[1] - cy) * 256) + 256;
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='radar'>");
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                sb.append("<div class='cell'>")
                        .append("<img alt='' src='https://cyberjapandata.gsi.go.jp/xyz/std/").append(z).append('/').append(x).append('/').append(y).append(".png'>")
                        .append("</div>");
            }
        }
        // 🌧️ 雨タイル（z7）: z8 グリッド（ox..ox+2, oy..oy+2）を覆う z7 タイルは最大4枚
        int tx0 = Math.floorDiv(ox, 2);
        int tx1 = Math.floorDiv(ox + 2, 2);
        int ty0 = Math.floorDiv(oy, 2);
        int ty1 = Math.floorDiv(oy + 2, 2);
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int left = (2 * tx - ox) * 256;
                int top = (2 * ty - oy) * 256;
                sb.append("<img alt='' class='rv' style='left:").append(left).append("px;top:").append(top)
                        .append("px;width:512px;height:512px' src='https://tilecache.rainviewer.com")
                        .append(esc(framePath)).append("/256/7/").append(tx).append('/').append(ty).append("/2/1_1.png'>");
            }
        }
        sb.append("<div class='marker' style='left:").append(mx).append("px;top:").append(my).append("px'>📍</div>");
        sb.append("</div>");
        return sb.toString();
    }

    // ----------------------------------------
    // 📰 ニュースセクション
    // ----------------------------------------
    private static String newsHtml() {
        List<NewsFeed.NewsItem> items = newsCached();
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<h2>📰 防災ニュース</h2><ul class='news'>");
        for (NewsFeed.NewsItem n : items) {
            sb.append("<li>");
            if (!n.link().isEmpty()) sb.append("<a href='").append(esc(n.link())).append("'>");
            sb.append(esc(n.title()));
            if (!n.link().isEmpty()) sb.append("</a>");
            if (!n.time().isEmpty()) sb.append("（").append(esc(n.time())).append("）");
            if (!n.summary().isEmpty()) sb.append("<br>").append(esc(n.summary()));
            sb.append("</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private static void respond(HttpExchange ex, int code, String contentType, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // 🔄 HTML エスケープ。本文用に加え、属性値の中でも壊れないよう引用符も変換する
    public static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
