package aegis.a2s;

import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Location;
import aegis.core.Version;
import aegis.net.ProxyEnv;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// ========================================
// 📡 A2sStream — A2S の SSE 常時接続
// ========================================
// 📌 A2S の GET /v1/stream（SSE）へ常時接続し、新規・更新イベントをプッシュで
// 受信する。受信したイベントは A2sConvert で変換し、REST ポーリング取得分と
// 同一視キーで Engine の重複排除にそのまま乗せる。警報の解除イベントは
// この SSE でのみ受け取る
// ⚠️ 再生ガード: 接続直後は保持イベントが古い順に再生され、最後に event: ready
// が届く。ready より前の再生イベントは初期同期として重複判定の状態へマージ
// するだけで、通知経路へは絶対に流さない（再接続のたびに過去イベントが
// 再通知されるのを防ぐ）
// 🔁 切断時は指数バックオフ（5秒→10秒→30秒→60秒上限、接続成功でリセット）で
// 自動再接続する。再接続時の再生で取りこぼしは回収され、重複は key + updatedAt
// で判定する。25秒ごとの heartbeat コメント行は SSE パーサが読み捨てる。
// 接続・変換の例外はすべてここで握り、監視ループを落とさない
public final class A2sStream {
    // 🔌 再接続までの待機（ミリ秒）。失敗が続く場合は配列を順に使い、尽きたら最後の値で頭打ちにする
    static final long[] RECONNECT_DELAYS_MS = {5000L, 10000L, 30000L, 60000L};

    // 🧪 再接続待機の決定（テスト対象）
    public static long reconnectDelayMs(int failures) {
        int idx = Math.min(Math.max(failures, 0), RECONNECT_DELAYS_MS.length - 1);
        return RECONNECT_DELAYS_MS[idx];
    }

    // ----------------------------------------
    // 📡 SSE フレーム解析
    // ----------------------------------------
    // 📌 テキストの断片を取り込み、空行で完成したフレームを返す。
    // コメント行（heartbeat）は読み捨て、id: / retry: 等は使わない
    public static final class SseParser {
        public record Frame(String type, String data) {}

        private final StringBuilder buffer = new StringBuilder(); // 📝 未処理のテキスト断片
        private String eventType = "";                            // 📝 現在のフレームの event: 行
        private final List<String> dataLines = new ArrayList<>(); // 📝 現在のフレームの data: 行群

        public List<Frame> push(String chunk) {
            buffer.append(chunk);
            List<Frame> frames = new ArrayList<>();
            int idx;
            while ((idx = buffer.indexOf("\n")) >= 0) {
                String line = buffer.substring(0, idx);
                buffer.delete(0, idx + 1);
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                Frame f = line(line);
                if (f != null) frames.add(f);
            }
            return frames;
        }

        private Frame line(String line) {
            if (line.isEmpty()) {
                if (dataLines.isEmpty()) {
                    eventType = "";
                    return null;
                }
                Frame f = new Frame(eventType.isEmpty() ? "message" : eventType, String.join("\n", dataLines));
                eventType = "";
                dataLines.clear();
                return f;
            }
            if (line.startsWith(":")) return null; // heartbeat 等のコメント行
            if (line.startsWith("event:")) {
                eventType = line.substring(6).trim();
                return null;
            }
            if (line.startsWith("data:")) {
                String v = line.substring(5);
                dataLines.add(v.startsWith(" ") ? v.substring(1) : v);
                return null;
            }
            return null; // id: / retry: 等は使わない
        }
    }

    private final HttpClient http;              // 📝 HTTP クライアント（差し替え可能）
    private final String baseUrl;               // 📡 A2S のベース URL（末尾スラッシュ除去済み）
    private final String token;                 // 📡 Bearer トークン（設定から受け取るだけ。表示しない）
    private final List<Location> locations;     // 📝 変換に使う監視地点
    private final Consumer<List<Event>> handler; // 📝 変換済みイベントの受け渡し先
    private final Consumer<String> log;         // 📝 ログ出力先
    private final ScheduledExecutorService scheduler; // 📝 再接続の予約用
    private final AtomicBoolean reconnectPending = new AtomicBoolean(); // 📝 再接続予約の多重防止
    // 🔁 重複判定の状態: key → 最後に取り込んだ updatedAt。再接続時の再生で同じ
    // 内容が再び届いても、key + updatedAt が同じなら取り込まない
    private final Map<String, String> seenUpdatedAt = new ConcurrentHashMap<>();
    private volatile boolean running = false;   // 📝 稼働中フラグ
    private volatile InputStream currentBody;   // 📝 現在のストリーム（停止時に閉じる）
    private volatile Thread worker;             // 📝 接続スレッド
    private int failures = 0;                   // 📝 連続失敗回数（synchronized メソッド内でのみ触る）

    public A2sStream(String baseUrl, String token, List<Location> locations,
                     Consumer<List<Event>> handler) {
        this(ProxyEnv.newClient(Duration.ofSeconds(15)), baseUrl, token, locations, handler,
                msg -> System.err.println(msg));
    }

    // 📋 差し替え用（HttpClient とログ出力先を注入できる）
    public A2sStream(HttpClient http, String baseUrl, String token, List<Location> locations,
                     Consumer<List<Event>> handler, Consumer<String> log) {
        this.http = http;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token;
        this.locations = locations == null ? List.of() : locations;
        this.handler = handler;
        this.log = log;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aegis-a2s-stream");
            t.setDaemon(true);
            return t;
        });
    }

    // 🧪 重複判定（テスト対象）: key + updatedAt が未取り込みなら記録して true を返す。
    // 同じ内容の再送（再接続時の再生など）は false
    public boolean markIfNew(String key, String updatedAt) {
        if (key == null || key.isEmpty()) return false;
        String prev = seenUpdatedAt.put(key, updatedAt == null ? "" : updatedAt);
        return prev == null || !prev.equals(updatedAt == null ? "" : updatedAt);
    }

    // 🧪 受信イベントの処理（テスト対象）: 検証 → 重複判定 →（再生でなければ）変換して
    // 通知経路へ。再生イベントは状態へマージするだけで通知しない。例外は握って空を返す
    public List<Event> handleEventJson(Object parsed, boolean replay) {
        try {
            Map<String, Object> e = Json.obj(parsed);
            if (!A2sConvert.isA2sEvent(e)) return List.of();
            if (!markIfNew(Json.str(e.get("key")), Json.str(e.get("updatedAt")))) return List.of();
            if (replay) return List.of(); // 📌 初期同期: 状態にマージするだけ
            return A2sConvert.convertEvent(e, locations);
        } catch (Exception ex) {
            log.accept("[a2s] イベントの取り込みに失敗しました: " + ex.getMessage());
            return List.of();
        }
    }

    // 🧪 SSE フレームの処理（テスト対象）: ready で再生終了を記録し、quake / eew /
    // tsunami / warning / typhoon フレームだけを取り込む。戻り値はこのフレーム時点での
    // 再生中フラグ（ready を処理したフレームでは false）
    public boolean handleFrame(SseParser.Frame frame, boolean replay) {
        if (frame.type().equals("ready")) return false;
        // ⚠️ warning の解除イベント（released=true）はこのストリームでのみ届く。
        // 変換側（A2sConvert.warningToEvents）が通知せず世代だけを進める
        if (!frame.type().equals("quake") && !frame.type().equals("eew") && !frame.type().equals("tsunami")
                && !frame.type().equals("warning") && !frame.type().equals("typhoon")) {
            return replay;
        }
        Object parsed;
        try {
            parsed = Json.parse(frame.data());
        } catch (Exception e) {
            return replay;
        }
        List<Event> evs = handleEventJson(parsed, replay);
        if (!evs.isEmpty()) {
            try {
                handler.accept(evs);
            } catch (Exception e) {
                log.accept("[a2s] 受信処理でエラー: " + e.getMessage());
            }
        }
        return replay;
    }

    // 🧪 接続成功で失敗カウントをリセットする（テスト対象）
    public synchronized void noteConnected() {
        failures = 0;
    }

    // 🧪 失敗を記録し、次回再接続までの待機を返す（テスト対象）
    public synchronized long noteFailureAndDelay() {
        return reconnectDelayMs(failures++);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::connectLoop, "aegis-a2s-connect");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    public synchronized void stop() {
        running = false;
        InputStream body = currentBody;
        currentBody = null;
        if (body != null) {
            try { body.close(); } catch (Exception ignored) {}
        }
        scheduler.shutdownNow();
    }

    // 🔌 1回の接続を張り、ストリームを読み切る。再接続の予約は切断検知時に行う
    private void connectLoop() {
        while (running) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/stream"))
                        .header("User-Agent", "AEGIS-Java/" + Version.VERSION + " (autonomous disaster assistant; +local)")
                        .header("Accept", "text/event-stream")
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
                HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) {
                    try { res.body().close(); } catch (Exception ignored) {}
                    throw new IllegalStateException("HTTP " + res.statusCode());
                }
                noteConnected();
                log.accept("[a2s] A2S ストリームに接続しました (地震・津波・EEW・警報・台風 プッシュ受信)");
                readStream(res.body());
                if (running) log.accept("[a2s] A2S ストリームが終了しました");
            } catch (Exception e) {
                if (running) log.accept("[a2s] A2S ストリームから切断されました: " + e.getMessage());
            }
            if (!running) return;
            scheduleReconnect();
            // 🔌 再接続はスケジューラ側で行うため、このスレッドは待機して終わる
            return;
        }
    }

    // 📖 ストリームを行単位で読み、SSE パーサへ流す。ready が届くまでは接続時の再生分として扱う
    private void readStream(InputStream body) throws Exception {
        currentBody = body;
        SseParser parser = new SseParser();
        boolean replay = true;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                for (SseParser.Frame f : parser.push(line + "\n")) {
                    replay = handleFrame(f, replay);
                }
            }
        } finally {
            currentBody = null;
        }
    }

    // 🔌 再接続の予約。多重予約は1回分に畳む
    private void scheduleReconnect() {
        if (!running) return;
        if (!reconnectPending.compareAndSet(false, true)) return;
        long delay = noteFailureAndDelay();
        log.accept("[a2s] A2S ストリームへ " + (delay / 1000) + " 秒後に再接続します");
        scheduler.schedule(() -> {
            reconnectPending.set(false);
            if (running) {
                Thread t = new Thread(this::connectLoop, "aegis-a2s-connect");
                t.setDaemon(true);
                worker = t;
                t.start();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
}
