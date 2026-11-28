package aegis.test;

import aegis.app.Dashboard;
import aegis.app.Engine;
import aegis.app.Main;
import aegis.config.AppConfig;
import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Location;
import aegis.core.Scale;
import aegis.core.Severity;
import aegis.net.Http;
import aegis.notify.Notifiers;
import aegis.store.SeenStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static aegis.test.TestKit.check;
import static aegis.test.TestKit.contains;
import static aegis.test.TestKit.notContains;

// ========================================
// 🧪 AegisTest — 基盤まわり（core / config / net / store / notify / app）の単体テスト
// ========================================
// 📌 JUnit 不要・ネットワーク不要。実行: java -cp out aegis.test.AegisTest
// A2S 変換・パーサ・天気・ニュース・レーダーの検証は A2sTest が担う
public final class AegisTest {
    private AegisTest() {}

    public static void main(String[] args) throws Exception {
        testScale();
        testEventAndJson();
        testConfig();
        testRetry();
        testStore();
        testNotify();
        testEngine();
        testMainAndDashboard();
        System.out.println("AegisTest: " + TestKit.passed + " checks passed");
    }

    // ----------------------------------------
    // 🌏 震度換算・重要度
    // ----------------------------------------
    private static void testScale() {
        check(Scale.toStr(10L).equals("1"), "scale 1");
        check(Scale.toStr(20L).equals("2"), "scale 2");
        check(Scale.toStr(30L).equals("3"), "scale 3");
        check(Scale.toStr(40L).equals("4"), "scale 4");
        check(Scale.toStr(45L).equals("5弱"), "scale 5弱");
        check(Scale.toStr(50L).equals("5強"), "scale 5強");
        check(Scale.toStr(55L).equals("6弱"), "scale 6弱");
        check(Scale.toStr(60L).equals("6強"), "scale 6強");
        check(Scale.toStr(70L).equals("7"), "scale 7");
        check(Scale.toStr(-1L).equals("不明"), "scale -1 unknown");
        check(Scale.toStr(0L).equals("不明"), "scale 0 unknown");
        check(Scale.toSeverity(45L, 5.0) == Severity.CRITICAL, "severity by scale CRITICAL");
        check(Scale.toSeverity(30L, 6.0) == Severity.CRITICAL, "severity by magnitude CRITICAL");
        check(Scale.toSeverity(40L, 4.0) == Severity.HIGH, "severity HIGH");
        check(Scale.toSeverity(30L, 3.0) == Severity.MEDIUM, "severity MEDIUM");
        check(Scale.toSeverity(20L, 3.0) == Severity.LOW, "severity LOW");
        check(Scale.toSeverity(10L, 2.0) == Severity.INFO, "severity INFO");
        check(Severity.fromName("HIGH") == Severity.HIGH, "severity fromName");
        check(Severity.fromName("critical") == Severity.CRITICAL, "severity fromName case-insensitive");
        check(Severity.fromName("???") == Severity.MEDIUM, "severity fromName unknown -> MEDIUM");
    }

    // ----------------------------------------
    // 📨 Event と JSON
    // ----------------------------------------
    private static void testEventAndJson() {
        Event ev = new Event("earthquake", "テスト地震", "本文");
        ev.severity = Severity.HIGH;
        String eid = ev.ensureId();
        check(eid!= null && eid.length() == 24, "event id 24 chars");
        Event ev2 = new Event("earthquake", "テスト地震", "本文");
        check(ev2.ensureId().equals(eid), "event id deterministic");
        contains(ev.formatMessage(), "テスト地震", "event format title");
        contains(ev.formatMessage(), "HIGH", "event format severity");
        ev.actionGuide = "ガイド";
        ev.source = "出典X";
        ev.occurredAt = "2026/11/28 09:00:00";
        contains(ev.formatMessage(), "▶ 行動ガイド: ガイド", "event format guide");
        contains(ev.formatMessage(), "(出典: 出典X / 2026/11/28 09:00:00)", "event format source");
        contains(ev.toJson(), "\"severity\":\"HIGH\"", "event json severity");
        contains(ev.toJson(), "\"event_id\":\"" + eid + "\"", "event json id");

        Object parsed = Json.parse("{\"a\":1,\"b\":[true,null,\"x\\u9707\"],\"c\":{\"d\":2.5}}");
        Map<String, Object> m = Json.obj(parsed);
        check(Json.dbl(m.get("a"), 0) == 1.0, "json number");
        check(Json.arr(m.get("b")).size() == 3, "json array size");
        check(Json.arr(m.get("b")).get(0) == Boolean.TRUE, "json boolean");
        check(Json.arr(m.get("b")).get(1) == null, "json null");
        check(Json.str(Json.arr(m.get("b")).get(2)).equals("x震"), "json unicode escape");
        check(Json.dbl(Json.obj(m.get("c")).get("d"), 0) == 2.5, "json nested double");
        check(Json.parse("[]") instanceof List, "json empty array");
        check(Json.obj(Json.parse("{}")).isEmpty(), "json empty object");
        check(Json.str(null).isEmpty(), "json str null");
        check(Json.str(null, "def").equals("def"), "json str fallback");
        check(Json.dbl("3.5", 0) == 3.5, "json dbl from string");
        check(Json.dbl("abc", 7) == 7, "json dbl fallback");
        check(Json.bool("true"), "json bool from string");
        check(Json.quote("a\"b\n").equals("\"a\\\"b\\n\""), "json quote escape");
        check(Json.escape("a" + (char) 1 + "b").contains("\\u0001"), "json escape control char");
    }

    // ----------------------------------------
    // ⚙️ 設定
    // ----------------------------------------
    private static void testConfig() {
        check(AppConfig.clampMin(3, 5) == 5, "clampMin below");
        check(AppConfig.clampMin(10, 5) == 10, "clampMin above");
        String[] r1 = AppConfig.resolveA2s("http://cfg", "cfgTok", "", "");
        check(r1[0].equals("http://cfg") && r1[1].equals("cfgTok"), "resolveA2s config only");
        String[] r2 = AppConfig.resolveA2s("http://cfg", "cfgTok", "http://env", "envTok");
        check(r2[0].equals("http://env") && r2[1].equals("envTok"), "resolveA2s env wins");
        String[] r3 = AppConfig.resolveA2s("", "", null, null);
        check(r3[0].isEmpty() && r3[1].isEmpty(), "resolveA2s empty");
        AppConfig cfg = new AppConfig();
        check(!cfg.a2sEnabled(), "a2s disabled by default");
        cfg.a2sUrl = "http://x";
        check(!cfg.a2sEnabled(), "a2s disabled without token");
        cfg.a2sToken = "t";
        check(cfg.a2sEnabled(), "a2s enabled with url+token");
        Location loc = Location.fromMap(Json.obj(Json.parse(
        "{\"name\":\"東京\",\"lat\":35.6,\"lon\":139.7,\"jma_pref_code\":\"130000\",\"jma_area_name\":\"東京地方\",\"city\":\"千代田区\"}")));
        check(loc.name().equals("東京") && loc.city().equals("千代田区"), "location fromMap");
    }

    // ----------------------------------------
    // 🔁 HTTP リトライ
    // ----------------------------------------
    private static void testRetry() throws Exception {
        long[] saved = Http.retryDelaysMs;
        Http.retryDelaysMs = new long[]{0, 0};
        try {
            check(Http.withRetry(() -> "ok").equals("ok"), "retry immediate success");
            AtomicInteger n = new AtomicInteger();
            String v = Http.withRetry(() -> {
                if (n.incrementAndGet() < 3) throw new Http.HttpStatusException(503, "http://x");
                return "recovered";
            });
            check(v.equals("recovered") && n.get() == 3, "retry 5xx then success");
            AtomicInteger n4 = new AtomicInteger();
            boolean threw4 = false;
            try {
                Http.withRetry(() -> { n4.incrementAndGet(); throw new Http.HttpStatusException(404, "http://x");});
            } catch (Http.HttpStatusException e) { threw4 = e.status == 404;}
            check(threw4 && n4.get() == 1, "retry 4xx immediate fail");
            AtomicInteger nIo = new AtomicInteger();
            String v2 = Http.withRetry(() -> {
                if (nIo.incrementAndGet() < 2) throw new java.io.IOException("net down");
                return "io-ok";
            });
            check(v2.equals("io-ok"), "retry io error then success");
            AtomicInteger nAll = new AtomicInteger();
            boolean threwAll = false;
            try {
                Http.withRetry(() -> { nAll.incrementAndGet(); throw new Http.HttpStatusException(500, "http://x");});
            } catch (Http.HttpStatusException e) { threwAll = true;}
            check(threwAll && nAll.get() == 3, "retry exhausts 3 attempts");
        } finally {
            Http.retryDelaysMs = saved;
        }
    }

    // ----------------------------------------
    // 📜 Store（重複排除・履歴・剪定）
    // ----------------------------------------
    private static void testStore() throws Exception {
        Path tmp = Files.createTempDirectory("aegis-test-store");
        SeenStore store = new SeenStore(tmp);
        check(!store.isSeen("abc"), "store not seen");
        store.markSeen("abc", "earthquake", "t", "HIGH");
        check(store.isSeen("abc"), "store seen");
        store.addHistory("abc", "earthquake", "t", "b", "HIGH");
        check(store.recentHistory(5).size() == 1, "store history 1");
        check(store.recentHistory(5).get(0).get("title").equals("t"), "store history fields");
        SeenStore store2 = new SeenStore(tmp);
        check(store2.isSeen("abc"), "store persisted across instances");
        check(store2.recentHistory(5).size() == 1, "store history persisted");

        // 🚨 EEW の通知済み最大震度
        check(store.eewSeenMaxScale("eew-x") == null, "eew seen none -> null");
        store.markSeen("eew-x", "eew", "t", "HIGH");
        check(store.eewSeenMaxScale("eew-x") == -1, "eew seen base only -> -1");
        store.markSeen("eew-x@s30", "eew", "t", "MEDIUM");
        store.markSeen("eew-x@s45", "eew", "t", "CRITICAL");
        check(store.eewSeenMaxScale("eew-x") == 45, "eew seen max scale");

        // 📅 日付パースと剪定判定
        check(SeenStore.parseDate("2026-11-28")!= null, "parseDate ok");
        check(SeenStore.parseDate("2026-13-40") == null, "parseDate invalid");
        check(SeenStore.parseDate("") == null, "parseDate empty");
        check(SeenStore.seenExpired("2026-10-01", LocalDate.of(2026, 11, 28)), "seen expired old");
        check(!SeenStore.seenExpired("2026-11-20", LocalDate.of(2026, 11, 28)), "seen not expired recent");
        check(!SeenStore.seenExpired("garbage", LocalDate.of(2026, 11, 28)), "seen not expired unparsable");

        // 📅 古い行は読み込み時に剪定される
        Path tmp2 = Files.createTempDirectory("aegis-test-prune");
        String old = "old-id\tearthquake\tt\tHIGH\t" + LocalDate.now().minusDays(60).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "\n";
        Files.writeString(tmp2.resolve("seen.tsv"), old);
        SeenStore pruned = new SeenStore(tmp2);
        check(!pruned.isSeen("old-id"), "store prunes expired seen on load");
        // 📅 旧形式（記録日なし）は移行として保持される
        Path tmp3 = Files.createTempDirectory("aegis-test-legacy");
        Files.writeString(tmp3.resolve("seen.tsv"), "legacy-id\tearthquake\tt\tHIGH\n");
        SeenStore legacy = new SeenStore(tmp3);
        check(legacy.isSeen("legacy-id"), "store keeps legacy rows");
    }

    // ----------------------------------------
    // 🔔 通知
    // ----------------------------------------
    private static void testNotify() {
        check(Notifiers.detectOs("Windows 11").equals("windows"), "detectOs windows");
        check(Notifiers.detectOs("Mac OS X").equals("mac"), "detectOs mac");
        check(Notifiers.detectOs("Darwin").equals("mac"), "detectOs darwin");
        check(Notifiers.detectOs("Linux").equals("linux"), "detectOs linux");
        check(Notifiers.detectOs("FreeBSD").equals("other"), "detectOs other");
        check(Notifiers.detectOs(null).equals("other"), "detectOs null");
        Event ev = new Event("earthquake", "地震タイトル", "行1\n行2 行3");
        ev.severity = Severity.HIGH;
        check(Notifiers.desktopTitle(ev).equals("🟠 地震タイトル"), "desktop title");
        check(Notifiers.desktopBody(ev).equals("行1 行2 行3"), "desktop body one-line");
        Event longEv = new Event("earthquake", "t", "x".repeat(250));
        check(Notifiers.desktopBody(longEv).length() == 201, "desktop body truncated to 200+ellipsis");
        check(Notifiers.desktopCommand("windows", "t", "b", false).get(0).equals("powershell"), "desktop cmd windows");
        check(Notifiers.desktopCommand("mac", "t", "b", false).get(0).equals("osascript"), "desktop cmd mac");
        check(Notifiers.desktopCommand("linux", "t", "b", true).equals(List.of("notify-send", "t", "b")), "desktop cmd linux with notify-send");
        check(Notifiers.desktopCommand("linux", "t", "b", false) == null, "desktop cmd linux without notify-send");
        check(Notifiers.desktopCommand("other", "t", "b", true) == null, "desktop cmd other null");
        check(Notifiers.desktopCommand(null, "t", "b", true) == null, "desktop cmd null os");
        contains(Notifiers.psToastScript("a<b", "c'd"), "a&lt;b", "ps toast xml escaped");
        contains(Notifiers.psToastScript("a<b", "c'd"), "c&apos;d", "ps toast apostrophe xml-escaped");
        contains(Notifiers.psToastScript("t", "b"), "ToastNotificationManager", "ps toast manager");
        check(Notifiers.appleScript("t\"x", "b").contains("\\\""), "applescript escape");
        check(Notifiers.xmlEscape("<a&b>").equals("&lt;a&amp;b&gt;"), "xmlEscape");
        check(Notifiers.asEscape("a\\b").equals("a\\\\b"), "asEscape");
        AppConfig cfg = new AppConfig();
        check(Notifiers.build(cfg).size() == 2, "notifiers default 2 (console+file)");
        cfg.discordWebhookUrl = "https://example.invalid/wh";
        cfg.genericWebhookUrl = "https://example.invalid/gen";
        cfg.desktopNotify = true;
        check(Notifiers.build(cfg).size() == 5, "notifiers all 5");
    }

    // ----------------------------------------
    // 🔁 Engine（重複排除・EEW 上方修正・フィルタ・間隔計算）
    // ----------------------------------------
    private static Event quakeEvent(String id, int scale, Severity sev, String occurredAt) {
        Event ev = new Event("earthquake", "地震 " + id, "本文");
        ev.eventId = id;
        ev.maxScale = scale;
        ev.severity = sev;
        ev.occurredAt = occurredAt;
        return ev;
    }

    private static Event eewEvent(String id, int scale, Severity sev, String occurredAt) {
        Event ev = new Event("eew", "EEW " + id, "本文");
        ev.eventId = id;
        ev.maxScale = scale;
        ev.severity = sev;
        ev.occurredAt = occurredAt;
        return ev;
    }

    private static void testEngine() throws Exception {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
        String old = LocalDateTime.now().minusHours(2).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
        check(Engine.due(100, 80, 20), "engine due reached");
        check(!Engine.due(100, 81, 20), "engine due not reached");
        check(Engine.nextSleepSeconds(100, 90, 95, 0, 20, 10, 600) == 5, "engine next sleep");
        check(Engine.nextSleepSeconds(1000, 90, 95, 0, 20, 10, 600) == 1, "engine next sleep min 1");

        Event fresh = quakeEvent("q-fresh", 45, Severity.CRITICAL, now);
        check(!Engine.isStale(fresh), "isStale fresh false");
        check(Engine.isStale(quakeEvent("q-old", 45, Severity.CRITICAL, old)), "isStale 2h old true");
        check(!Engine.isStale(quakeEvent("q-bad", 45, Severity.CRITICAL, "not-a-date")), "isStale unparsable false");
        Event warnEv = new Event("weather_warning", "警報", "本文");
        warnEv.occurredAt = old;
        check(!Engine.isStale(warnEv), "isStale warning kind excluded");

        check(Engine.isEewTracked(eewEvent("e1", 30, Severity.MEDIUM, now)), "isEewTracked normal");
        check(!Engine.isEewTracked(eewEvent("e1-cancel", 30, Severity.MEDIUM, now)), "isEewTracked cancel excluded");

        // 📜 重複排除: 同じ地震は2回通知しない
        AppConfig cfg = new AppConfig();
        SeenStore store = new SeenStore(Files.createTempDirectory("aegis-test-engine"));
        Engine eng = new Engine(cfg, store, List.of());
        check(eng.process(List.of(quakeEvent("q1", 45, Severity.CRITICAL, now))).size() == 1, "process notifies first");
        check(eng.process(List.of(quakeEvent("q1", 45, Severity.CRITICAL, now))).isEmpty(), "process dedups second");
        // 🌏 quake_min_scale（既定30）未満は通知しないが記録はする
        check(eng.process(List.of(quakeEvent("q2", 20, Severity.CRITICAL, now))).isEmpty(), "process quake_min_scale filter");
        check(store.isSeen("q2"), "process filtered quake still recorded");
        // 🚦 重要度フィルタ（既定 MEDIUM）未満は通知しない
        check(eng.process(List.of(quakeEvent("q3", 45, Severity.LOW, now))).isEmpty(), "process severity filter");
        // 🚨 古い地震は記録のみ
        check(eng.process(List.of(quakeEvent("q4", 45, Severity.CRITICAL, old))).isEmpty(), "process stale record only");
        check(store.isSeen("q4"), "process stale recorded");

        // 🚨 EEW: 推定震度が上がった続報だけ再通知する
        SeenStore store2 = new SeenStore(Files.createTempDirectory("aegis-test-engine2"));
        Engine eng2 = new Engine(cfg, store2, List.of());
        check(eng2.process(List.of(eewEvent("eew-a", 30, Severity.MEDIUM, now))).size() == 1, "eew first notified");
        check(eng2.process(List.of(eewEvent("eew-a", 30, Severity.MEDIUM, now))).isEmpty(), "eew same scale not renotified");
        check(eng2.process(List.of(eewEvent("eew-a", 45, Severity.CRITICAL, now))).size() == 1, "eew upward revision renotified");
        check(eng2.process(List.of(eewEvent("eew-a", 40, Severity.HIGH, now))).isEmpty(), "eew downward not renotified");
        // 🚨 EEW はフィルタで落ちても記録を保留し、強い続報を取りこぼさない
        check(eng2.process(List.of(eewEvent("eew-b", 10, Severity.LOW, now))).isEmpty(), "eew weak filtered");
        check(store2.eewSeenMaxScale("eew-b") == null, "eew weak not recorded (deferred)");
        check(eng2.process(List.of(eewEvent("eew-b", 50, Severity.CRITICAL, now))).size() == 1, "eew strong follow-up notified");
        // 🚨 取消報は別IDで通常判定
        check(eng2.process(List.of(eewEvent("eew-a-cancel", 45, Severity.MEDIUM, now))).size() == 1, "eew cancel notified");

        // 📌 まとめ
        List<String> lines = Engine.summarize(List.of(
        quakeEvent("q9", 45, Severity.CRITICAL, now),
            new Event("weather_warning", "気象 大雨警報（高崎）", "b")));
        check(lines.get(0).equals("📌 まとめ"), "summarize header");
        contains(String.join("\n", lines), "気象 大雨警報（高崎）", "summarize warning");
        contains(String.join("\n", lines), "地震 q9", "summarize quake");
    }

    // ----------------------------------------
    // ⌨️ Main（診断整形・テスト通知）と Dashboard（エスケープ・タイル）
    // ----------------------------------------
    private static void testMainAndDashboard() {
        List<Main.CheckResult> all = List.of(
        new Main.CheckResult("項目A", true, "詳細"),
            new Main.CheckResult("項目B", true, ""));
        String text = Main.formatDiagnostics(all);
        contains(text, "○ 項目A（詳細）", "diagnostics ok line");
        contains(text, "結果: 全て正常です（2/2）", "diagnostics all ok");
        check(Main.diagnosticsExitCode(all) == 0, "diagnostics exit 0");
        List<Main.CheckResult> some = List.of(
        new Main.CheckResult("項目A", true, ""),
            new Main.CheckResult("項目B", false, "失敗理由"));
        contains(Main.formatDiagnostics(some), "× 項目B（失敗理由）", "diagnostics ng line");
        contains(Main.formatDiagnostics(some), "1件の失敗があります（1/2 成功）", "diagnostics partial");
        check(Main.diagnosticsExitCode(some) == 1, "diagnostics exit 1");
        Event testEv = Main.buildTestEvent();
        contains(testEv.title, "", "test event marked");
        check(testEv.severity == Severity.CRITICAL && testEv.maxScale == 45, "test event fields");

        check(Dashboard.esc("<a href='x'>&\"").equals("&lt;a href=&#39;x&#39;&gt;&amp;&quot;"), "dashboard esc");
        String tiles = Dashboard.radarTilesHtml(36.3219, 139.0023, "/v2/2026");
        contains(tiles, "cyberjapandata.gsi.go.jp/xyz/std/8/", "radar tiles base z8");
        contains(tiles, "tilecache.rainviewer.com/v2/2026/256/7/", "radar tiles rain z7");
        contains(tiles, "width:512px", "radar tiles rain enlarged");
        contains(tiles, "marker", "radar tiles marker");
        var frame = new aegis.radar.Radar.JmaFrame("20261128000000", "20261128000500", 0);
        String jmaTiles = Dashboard.jmaRadarTilesHtml(36.3219, 139.0023, frame);
        contains(jmaTiles, "nowc/20261128000000/none/20261128000500/surf/hrpns/8/", "jma radar tiles url");
        check(Dashboard.radarFrameUnchanged(100, 100), "radar frame unchanged");
        check(!Dashboard.radarFrameUnchanged(100, 101), "radar frame changed");
        check(!Dashboard.radarFrameUnchanged(0, 100), "radar frame no cache");
    }
}
