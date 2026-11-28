package aegis.a2s;

import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Location;
import aegis.core.Version;
import aegis.net.Http;
import aegis.net.ProxyEnv;
import aegis.parse.QuakeParser;
import aegis.parse.TsunamiParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// ========================================
// 📡 A2sClient — A2S（Aegis Alert System）への REST クライアント
// ========================================
// 📌 A2S は地震・津波・EEW を P2P / Wolfx から、気象警報・注意報と台風を
// 気象庁から取り込み・正規化して配信する中継ハブ（aegis-app/aegis-alert-system）。
// 本クラスはその REST 取得（GET /v1/events、GET /v1/status）を担い、SSE の
// 常時接続は A2sStream が担う
// 📌 認証は Bearer トークン。トークンは呼び出し側が設定（AppConfig）から
// 渡したものだけを使い、ここでは保持も表示もしない
// 📌 公開契約: GET /v1/events（kind / since / limit）、GET /v1/stream（SSE）、
// GET /v1/status。since= には最後に受けた updatedAt を渡すと差分だけが返る
public final class A2sClient {
    private A2sClient() {}

    private static final HttpClient HTTP = ProxyEnv.newClient(Duration.ofSeconds(15));
    private static final String UA = "AEGIS-Java/" + Version.VERSION + " (autonomous disaster assistant; +local)";

    // 🧪 イベント一覧の URL を組み立てる（テスト対象）。末尾スラッシュは除き、
    // kind / since / limit は指定があるときだけ付ける
    public static String eventsUrl(String baseUrl, String kind, String since, int limit) {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        StringBuilder sb = new StringBuilder(base).append("/v1/events");
        List<String> qs = new ArrayList<>();
        if (kind != null && !kind.isEmpty()) qs.add("kind=" + kind);
        if (since != null && !since.isEmpty()) qs.add("since=" + since);
        if (limit > 0) qs.add("limit=" + limit);
        if (!qs.isEmpty()) sb.append("?").append(String.join("&", qs));
        return sb.toString();
    }

    // 🧪 稼働状態の URL を組み立てる（テスト対象）
    public static String statusUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return base + "/v1/status";
    }

    // 🧪 REST 応答（{events: [...]}）から検証済みイベントだけを取り出す
    // （テスト対象）。SSE の再生で回収しきれない差分の回収に使う
    public static List<Map<String, Object>> parseEventsResponse(Object data) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(data).get("events"))) {
            Map<String, Object> e = Json.obj(o);
            if (A2sConvert.isA2sEvent(e)) out.add(e);
        }
        return out;
    }

    // 🌐 認証つきで JSON を取得する。リトライ規則は Http と同じ
    // （ネットワークエラーと HTTP 5xx のみ最大3回、4xx は即失敗）
    static Object fetchJsonAuthorized(String url, String token) throws Exception {
        return Http.withRetry(() -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) throw new Http.HttpStatusException(res.statusCode(), url);
            return Json.parse(res.body());
        });
    }

    // 🌐 A2S から直近イベントを取得し、検証済みイベントの一覧を返す
    public static List<Map<String, Object>> fetchEvents(String baseUrl, String token, String kind, String since, int limit) throws Exception {
        return parseEventsResponse(fetchJsonAuthorized(eventsUrl(baseUrl, kind, since, limit), token));
    }

    // 🌐 A2S の稼働状態（GET /v1/status）を取得する。自己診断用
    public static Map<String, Object> fetchStatus(String baseUrl, String token) throws Exception {
        return Json.obj(fetchJsonAuthorized(statusUrl(baseUrl), token));
    }

    // 🔄 種別を指定して取得し、Event へ変換まで済ませる（ポーリング経路の入口）。
    // 地震は複数電文のマージが要るため、item へ戻してからまとめてパーサに通す
    private static List<Event> fetchKind(String baseUrl, String token, String kind, int limit,
                                         List<Location> locations) throws Exception {
        List<Map<String, Object>> evs = fetchEvents(baseUrl, token, kind, null, limit);
        if (locations == null) locations = List.of();
        List<Event> out = new ArrayList<>();
        switch (kind) {
            case "quake" -> {
                List<Object> items = new ArrayList<>();
                for (Map<String, Object> e : evs) items.add(A2sConvert.quakeToItem(e));
                out.addAll(QuakeParser.parse(items, locations));
            }
            case "tsunami" -> {
                List<Object> items = new ArrayList<>();
                for (Map<String, Object> e : evs) items.add(A2sConvert.tsunamiToItem(e));
                out.addAll(TsunamiParser.parse(items));
            }
            case "eew", "warning", "typhoon" -> {
                for (Map<String, Object> e : evs) out.addAll(A2sConvert.convertEvent(e, locations));
            }
            default -> { }
        }
        return out;
    }

    // 🌏 地震の直近イベント（履歴参照もこの経路）
    public static List<Event> fetchQuakes(String baseUrl, String token, int limit, List<Location> locations) throws Exception {
        return fetchKind(baseUrl, token, "quake", limit, locations);
    }

    // 🚨 EEW の直近イベント
    public static List<Event> fetchEew(String baseUrl, String token, int limit, List<Location> locations) throws Exception {
        return fetchKind(baseUrl, token, "eew", limit, locations);
    }

    // 🌊 津波の直近イベント
    public static List<Event> fetchTsunami(String baseUrl, String token, int limit) throws Exception {
        return fetchKind(baseUrl, token, "tsunami", limit, List.of());
    }

    // ⚠️ 気象警報・注意報の現在発表中の区域分。A2S の保持上限（種別 cap 300）まで取る。
    // 解除は一覧に載らないため、解除→再発表の世代管理は SSE の解除イベントと、この
    // 取得で有効集合からコードが消えたことの観測の両方で進む
    public static final int WARNING_FETCH_LIMIT = 300;

    public static List<Event> fetchWarnings(String baseUrl, String token, List<Location> locations) throws Exception {
        return fetchKind(baseUrl, token, "warning", WARNING_FETCH_LIMIT, locations);
    }

    // 🌀 台風の直近イベント（台風ごとに1件）
    public static List<Event> fetchTyphoons(String baseUrl, String token) throws Exception {
        return fetchKind(baseUrl, token, "typhoon", 10, List.of());
    }
}
