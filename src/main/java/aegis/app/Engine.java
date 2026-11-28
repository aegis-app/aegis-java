package aegis.app;

import aegis.a2s.A2sClient;
import aegis.a2s.A2sStream;
import aegis.config.AppConfig;
import aegis.core.Event;
import aegis.core.Severity;
import aegis.news.NewsFeed;
import aegis.notify.Notifiers;
import aegis.store.SeenStore;
import aegis.weather.JmaForecast;
import aegis.weather.OpenMeteo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// ========================================
// 🔁 Engine — 自律監視ループの中核
// ========================================
// 📌 取得（A2S REST ポーリング + SSE プッシュ、気象庁予報、Open-Meteo、
// ニュース）を束ね、重複排除・重要度フィルタを経て通知する。地震・津波・
// EEW・気象警報/注意報・台風の取得は A2S のみ（フォールバックなし）。
// A2S 未設定時はそれらの情報は出ない（仕様）が、例外はすべてここで握る
// ため監視ループや他機能には影響しない
public final class Engine {
    private final AppConfig cfg;                    // 📝 設定
    private final SeenStore store;                  // 📝 永続化
    private final List<Notifiers.Notifier> notifiers; // 📝 通知先
    private final Severity minSev;                  // 📝 通知する最低重要度
    private int failures = 0;                       // 📝 ループの連続失敗回数（バックオフ用）
    private String lastBriefingKey = "";            // 📝 定時ブリーフィングの二重実行防止キー

    public Engine(AppConfig cfg, SeenStore store, List<Notifiers.Notifier> notifiers) {
        this.cfg = cfg;
        this.store = store;
        this.notifiers = notifiers;
        this.minSev = Severity.fromName(cfg.notifyMinSeverity);
    }

    public SeenStore store() { return store; }
    public AppConfig config() { return cfg; }

    // 🌐 並列取得用の共有プール（独立した取得は同時に走らせ、表示順に結合する）。
    // デーモンスレッドにする: ワンショット実行（now 等）の終了がプールの生存に引っ張られないように
    private static final ExecutorService FETCH_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "aegis-fetch");
        t.setDaemon(true);
        return t;
    });

    // 🌐 失敗した Future は捨て、成功した取得分だけを表示順に結合する
    private static List<Event> joinAll(List<Future<List<Event>>> futures) {
        List<Event> out = new ArrayList<>();
        for (Future<List<Event>> f : futures) {
            try { out.addAll(f.get()); } catch (Exception ignored) {}
        }
        return out;
    }

    // ----------------------------------------
    // 📝 取得チェック（種別ごと）
    // ----------------------------------------
    public List<Event> checkQuakes() {
        if (!cfg.a2sEnabled()) return List.of();
        List<Future<List<Event>>> fs = new ArrayList<>();
        fs.add(FETCH_POOL.submit(() -> { try { return A2sClient.fetchQuakes(cfg.a2sUrl, cfg.a2sToken, 10, cfg.locations); } catch (Exception e) { System.err.println("[engine] A2S fetchQuakes failed: " + e.getMessage()); return List.of(); } }));
        fs.add(FETCH_POOL.submit(() -> { try { return A2sClient.fetchTsunami(cfg.a2sUrl, cfg.a2sToken, 5); } catch (Exception e) { System.err.println("[engine] A2S fetchTsunami failed: " + e.getMessage()); return List.of(); } }));
        // 🚨 EEW の取得窓は 15 件。続報・取消報が他地域の速報に埋もれて取りこぼされないよう広めに取る
        fs.add(FETCH_POOL.submit(() -> { try { return A2sClient.fetchEew(cfg.a2sUrl, cfg.a2sToken, EEW_FETCH_LIMIT, cfg.locations); } catch (Exception e) { System.err.println("[engine] A2S fetchEew failed: " + e.getMessage()); return List.of(); } }));
        return joinAll(fs);
    }

    // 🚨 EEW の取得窓
    public static final int EEW_FETCH_LIMIT = 15;

    // 🚨 EEW のみ単独チェック（高頻度ポーリング用）。取得元は A2S のみ
    public List<Event> checkEew() {
        if (!cfg.a2sEnabled()) return List.of();
        try { return A2sClient.fetchEew(cfg.a2sUrl, cfg.a2sToken, EEW_FETCH_LIMIT, cfg.locations); }
        catch (Exception e) { System.err.println("[engine] A2S fetchEew failed: " + e.getMessage()); return List.of(); }
    }

    // ⚠️ 気象警報・注意報のチェック。取得元は A2S のみ
    public List<Event> checkWeather() {
        if (!cfg.a2sEnabled()) return List.of();
        try { return A2sClient.fetchWarnings(cfg.a2sUrl, cfg.a2sToken, cfg.locations); }
        catch (Exception e) { System.err.println("[engine] A2S fetchWarnings failed: " + e.getMessage()); return List.of(); }
    }

    // 📋 ブリーフィング用のイベント一式（天気予報＋A2S の地震・津波・警報・台風＋ニュース）
    public List<Event> briefingEvents() {
        List<Future<List<Event>>> fs = new ArrayList<>();
        for (aegis.core.Location loc : cfg.locations) {
            fs.add(FETCH_POOL.submit(() -> { List<Event> l = new ArrayList<>(); try { Event ev = JmaForecast.fetchForecast(loc); if (ev != null) l.add(ev); } catch (Exception e) { System.err.println("[engine] briefing forecast failed: " + e.getMessage()); } return l; }));
            fs.add(FETCH_POOL.submit(() -> { List<Event> l = new ArrayList<>(); try { Event ev = OpenMeteo.fetchCurrent(loc); if (ev != null) l.add(ev); } catch (Exception e) { System.err.println("[engine] briefing open-meteo failed: " + e.getMessage()); } return l; }));
        }
        // 📡 地震・津波・警報・台風は A2S の /v1/events から取る。A2S 未設定時は
        // セクションごと落とす（天気等の他セクションは出す）
        if (cfg.a2sEnabled()) {
            fs.add(FETCH_POOL.submit(() -> { try { List<Event> q = A2sClient.fetchQuakes(cfg.a2sUrl, cfg.a2sToken, 3, cfg.locations); return new ArrayList<>(q.subList(0, Math.min(3, q.size()))); } catch (Exception ignored) { return List.of(); } }));
            fs.add(FETCH_POOL.submit(() -> { try { return A2sClient.fetchTsunami(cfg.a2sUrl, cfg.a2sToken, 1); } catch (Exception ignored) { return List.of(); } }));
            fs.add(FETCH_POOL.submit(() -> { try { return A2sClient.fetchWarnings(cfg.a2sUrl, cfg.a2sToken, cfg.locations); } catch (Exception ignored) { return List.of(); } }));
            fs.add(FETCH_POOL.submit(() -> { try { return A2sClient.fetchTyphoons(cfg.a2sUrl, cfg.a2sToken); } catch (Exception ignored) { return List.of(); } }));
        }
        // 📰 ニュースはブリーフィング専用。失敗したらセクションごと落とす（監視通知には混ぜない）
        fs.add(FETCH_POOL.submit(() -> { try { Event ev = NewsFeed.fetchNewsEvent(); return ev == null ? List.of() : List.of(ev); } catch (Exception ignored) { return List.of(); } }));
        return joinAll(fs);
    }

    // 🌊 一括で把握するための冒頭まとめ（警報・津波・最新地震・天気を数行に凝縮）
    public static List<String> summarize(List<Event> events) {
        List<String> lines = new ArrayList<>();
        lines.add("📌 まとめ");
        List<String> warns = new ArrayList<>();
        String tsunamiTitle = null;
        String quakeTitle = null;
        List<Event> forecasts = new ArrayList<>();
        for (Event ev : events) {
            switch (ev.kind) {
                case "weather_warning" -> { if (warns.size() < 4) warns.add(ev.title); }
                case "tsunami" -> { if (tsunamiTitle == null) tsunamiTitle = ev.title; }
                case "earthquake" -> { if (quakeTitle == null) quakeTitle = ev.title; }
                case "weather_forecast" -> forecasts.add(ev);
                default -> {}
            }
        }
        // 🌧️ 2ソースの役割固定: 概況・降水確率は気象庁を正、現在値・時間別は Open-Meteo を正とする
        lines.add("出典の役割: 概況・降水確率=気象庁 / 警報=A.A.S（気象庁） / 現在値・時間別=Open-Meteo");
        lines.add(warns.isEmpty() ? "⚠ 警報・注意報: 発表なし" : "⚠ 警報・注意報: " + String.join(" / ", warns));
        lines.add("🌊 津波: " + (tsunamiTitle != null ? tsunamiTitle : "警報・注意報なし"));
        if (quakeTitle != null) lines.add("🌏 最新の地震: " + quakeTitle);
        for (int i = 0; i < Math.min(4, forecasts.size()); i++) {
            Event ev = forecasts.get(i);
            String first = ev.body == null ? "" : ev.body.split("\n")[0];
            lines.add("☀ " + ev.title + ": " + first);
        }
        return lines;
    }

    // 📋 ブリーフィング本文を組み立てる（now コマンドと定時ブリーフィング用）
    public String formatBriefing(String title) {
        List<Event> events = briefingEvents();
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        StringBuilder sb = new StringBuilder();
        sb.append("🛡️ ").append(title).append(" — ").append(now).append("\n").append("=".repeat(60)).append("\n");
        if (events.isEmpty()) {
            sb.append("現在、取得できた情報がありません（通信状態を確認してください）。\n");
        } else {
            for (String line : summarize(events)) sb.append(line).append("\n");
            sb.append("-".repeat(60)).append("\n");
        }
        for (Event ev : events) {
            sb.append(ev.formatMessage()).append("\n").append("-".repeat(60)).append("\n");
        }
        return sb.toString();
    }

    // ----------------------------------------
    // 🔔 通知判定（重複排除・フィルタ・記録）
    // ----------------------------------------
    public List<Event> process(List<Event> events) { return process(events, false); }

    // 🚨 ポーリングと A2S SSE のプッシュ受信が同じ EEW を同時に処理しても二重通知に
    // ならないよう、監視経路の process はこのロックで直列化する
    private final Object processLock = new Object();

    public List<Event> processLocked(List<Event> events) {
        synchronized (processLock) { return process(events); }
    }

    // 🚨 EEW（取消報を除く）は「eventId＋推定最大震度」で重複排除する対象か
    public static boolean isEewTracked(Event ev) {
        return ev.kind.equals("eew") && !ev.eventId.endsWith("-cancel");
    }

    // 📜 重複排除と履歴への記録
    private void record(Event ev) {
        store.markSeen(ev.eventId, ev.kind, ev.title, ev.severity.label);
        // 🚨 EEW は推定震度つきのキーも記録し、通知済みの最大推定震度を後から引けるようにする
        if (isEewTracked(ev)) {
            store.markSeen(ev.eventId + "@s" + ev.maxScale, ev.kind, ev.title, ev.severity.label);
        }
        store.addHistory(ev.eventId, ev.kind, ev.title, ev.body, ev.severity.label);
    }

    public List<Event> process(List<Event> events, boolean force) {
        List<Event> notified = new ArrayList<>();
        for (Event ev : events) {
            ev.ensureId();
            // 🚨 EEW の seen 判定は「eventId＋推定最大震度」で行う: 通知済みの最大推定震度
            // 以下の続報は再通知せず、推定震度が上がった続報のみ再通知する。取消報は別IDで判定する
            if (!force && isEewTracked(ev)) {
                Integer prevMax = store.eewSeenMaxScale(ev.eventId);
                if (prevMax != null && ev.maxScale <= prevMax) continue;
            } else if (store.isSeen(ev.eventId)) continue;
            // 🚨 EEW は続報で推定震度が上がることがある（続報は同一 eventId）。フィルタで
            // 落ちた EEW を seen に入れてしまうと、後から届く強い続報まで重複排除で消えて
            // しまうため、EEW は通知が確定するまで記録を保留する。他種別は従来どおり先に記録する
            boolean deferRecord = !force && ev.kind.equals("eew");
            if (!deferRecord) record(ev);
            // 🚨 監視開始直後の通知フラッド防止: 発生から1時間を超えた地震・津波・EEW は記録のみ
            if (!force && isStale(ev)) {
                // 🚨 古い EEW は今後新しくなることはないので、ここで記録して確定させる
                if (deferRecord) record(ev);
                continue;
            }
            // 🌏 設定 quake_min_scale 未満の地震は通知しない（記録はする）
            if (!force && ev.kind.equals("earthquake") && ev.maxScale >= 0 && ev.maxScale < cfg.quakeMinScale) continue;
            if (!force && ev.severity.level < minSev.level) continue;
            if (deferRecord) record(ev);
            Notifiers.dispatch(notifiers, ev);
            notified.add(ev);
        }
        return notified;
    }

    // 🚨 地震・津波・EEW について、発生時刻が1時間を超えているなら true（パースできない場合は false）
    public static boolean isStale(Event ev) {
        if (!ev.kind.equals("earthquake") && !ev.kind.equals("tsunami") && !ev.kind.equals("eew")) return false;
        String s = ev.occurredAt == null ? "" : ev.occurredAt;
        if (s.length() < 19) return false;
        try {
            LocalDateTime t;
            if (s.charAt(4) == '/') {
                t = LocalDateTime.parse(s.substring(0, 19), DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
            } else {
                t = LocalDateTime.parse(s.substring(0, 19), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            }
            return t.isBefore(LocalDateTime.now().minusHours(1));
        } catch (Exception e) {
            return false;
        }
    }

    // ----------------------------------------
    // 🔁 自律ループ
    // ----------------------------------------
    // 🧪 ポーリング間隔の到来判定（純粋関数・テスト対象）
    public static boolean due(long nowSec, long lastSec, long intervalSec) {
        return nowSec - lastSec >= intervalSec;
    }

    // 🧪 次にいずれかのチェックが到来するまでの秒数（純粋関数・テスト対象）。最小1秒
    public static long nextSleepSeconds(long nowSec, long lastQuake, long lastEew, long lastWeather,
                                        long qInterval, long eInterval, long wInterval) {
        long next = Math.min(lastQuake + qInterval,
                Math.min(lastEew + eInterval, lastWeather + wInterval));
        return Math.max(1, next - nowSec);
    }

    public void watch() throws InterruptedException {
        long qInterval = cfg.pollSecondsQuake;
        long eInterval = Math.max(1, cfg.pollSecondsEew);
        long wInterval = cfg.pollSecondsWeather;
        System.out.println("🛡️ AEGIS-Java watch 開始: 地震/津波 " + qInterval + "秒間隔, EEW " + eInterval + "秒間隔, 気象 " + wInterval + "秒間隔, 閾値 " + minSev.label + "以上");
        System.out.print("   監視地点: ");
        for (int i = 0; i < cfg.locations.size(); i++) { if (i > 0) System.out.print(", "); System.out.print(cfg.locations.get(i).name()); }
        System.out.println(" / 朝 " + cfg.morningBriefing + "・夜 " + cfg.eveningBriefing + " に自動ブリーフィング");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("\n🛡️ AEGIS-Java watch を停止しました")));
        // 📡 A2S: 地震・津波・EEW・気象警報/注意報・台風の唯一の情報源。設定で URL と
        // トークンが指定されたときだけ SSE で常時接続し、REST ポーリングと同じ process
        // 経路（重複排除込み）へ流す。未設定のときは A2S 対象の情報は出ない（仕様）が、
        // 天気予報など A2S 対象外の監視は通常どおり続く
        if (cfg.a2sEnabled()) {
            A2sStream a2sStream = new A2sStream(cfg.a2sUrl, cfg.a2sToken, cfg.locations, this::processLocked);
            a2sStream.start();
            Runtime.getRuntime().addShutdownHook(new Thread(a2sStream::stop));
            System.out.println("   A.A.S 連携: 有効 (" + cfg.a2sUrl + " が地震・津波・EEW・警報・台風の唯一の情報源)");
        } else {
            System.out.println("   A.A.S 連携: 未設定のため地震・津波・EEW・警報・台風は取得しません (a2s_url / a2s_token または A2S_URL / A2S_TOKEN を設定してください)");
        }
        long lastQuake = 0;
        long lastEew = 0;
        long lastWeather = 0;
        while (true) {
            try {
                long nowT = System.currentTimeMillis() / 1000;
                if (due(nowT, lastQuake, qInterval)) {
                    // 🚨 地震/津波チェックは EEW も含むため、EEW の最終チェック時刻も一緒に進める
                    processLocked(checkQuakes());
                    lastQuake = nowT;
                    lastEew = nowT;
                } else if (due(nowT, lastEew, eInterval)) {
                    // 🚨 EEW だけ高頻度にチェックして速報性を上げる
                    processLocked(checkEew());
                    lastEew = nowT;
                }
                if (due(nowT, lastWeather, wInterval)) {
                    processLocked(checkWeather());
                    lastWeather = nowT;
                }
                String hm = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                String key = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd ")) + hm;
                if ((hm.equals(cfg.morningBriefing) || hm.equals(cfg.eveningBriefing)) && !key.equals(lastBriefingKey)) {
                    lastBriefingKey = key;
                    String title = hm.equals(cfg.morningBriefing) ? "朝の防災ブリーフィング" : "夜の防災ブリーフィング";
                    System.out.println(formatBriefing(title));
                }
                failures = 0;
                long sleepSec = nextSleepSeconds(System.currentTimeMillis() / 1000,
                        lastQuake, lastEew, lastWeather, qInterval, eInterval, wInterval);
                Thread.sleep(sleepSec * 1000L);
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception e) {
                failures++;
                long backoff = Math.min(300, qInterval * (1L << Math.min(failures, 4)));
                System.err.println("[engine] loop error (" + failures + "): " + e.getMessage() + " — " + backoff + "秒後に再試行");
                Thread.sleep(backoff * 1000L);
            }
        }
    }
}
