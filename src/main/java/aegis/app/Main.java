package aegis.app;

import aegis.a2s.A2sClient;
import aegis.config.AppConfig;
import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Location;
import aegis.core.Severity;
import aegis.core.Version;
import aegis.news.NewsFeed;
import aegis.notify.Notifiers;
import aegis.radar.Radar;
import aegis.store.SeenStore;
import aegis.weather.JmaForecast;
import aegis.weather.OpenMeteo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// ========================================
// ⌨️ Main — CLI エントリポイント
// ========================================
// 📌 コマンド: now（ブリーフィング）/ watch（自律監視）/ ask（質問応答）/
// serve（ダッシュボード）/ history（通知履歴）/ test（自己診断）/
// test-notify（テスト通知）/ version
public final class Main {
private Main() {}

public static void main(String[] args) throws Exception {
// 📝 文字化け防止: 標準出力を UTF-8 に固定する（ロケールが POSIX 等でも日本語が化けないように）
System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8));
System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8));
AppConfig cfg = AppConfig.load();
String cmd = args.length == 0? "now": args[0];
switch (cmd) {
case "version", "--version" -> { System.out.println("AEGIS Java版 " + Version.VERSION); return;}
case "watch" -> buildEngine(cfg).watch();
case "ask" -> {
if (args.length < 2) { System.out.println("使い方: aegis ask \"大宮の天気は？\""); return;}
StringBuilder q = new StringBuilder();
for (int i = 1; i < args.length; i++) { if (i > 1) q.append(' '); q.append(args[i]);}
System.out.println(Assistant.answer(q.toString(), cfg));
}
case "history" -> {
int limit = 20;
for (int i = 1; i < args.length - 1; i++) if ("--limit".equals(args[i])) limit = Integer.parseInt(args[i + 1]);
SeenStore store = new SeenStore(AppConfig.root().resolve(cfg.storeDir));
List<Map<String, String>> rows = store.recentHistory(limit);
if (rows.isEmpty()) { System.out.println("履歴はまだありません。"); return;}
for (Map<String, String> r: rows) {
System.out.println("[" + r.get("created_at") + "] " + r.get("severity") + " " + r.get("title") + " — " + r.get("body"));
}
}
case "serve" -> {
int port = 8765;
for (int i = 1; i < args.length - 1; i++) if ("--port".equals(args[i])) port = Integer.parseInt(args[i + 1]);
Dashboard.serve(cfg, buildEngine(cfg), port);
}
case "now" -> System.out.println(buildEngine(cfg).formatBriefing("現在の防災ブリーフィング"));
case "test" -> System.exit(runDiagnostics(cfg));
case "test-notify" -> testNotify(cfg);
default -> {
System.out.println("AEGIS-Java — 地震・津波・気象 自律アシスタント");
System.out.println("使い方: now | watch | ask \"…\" | serve [--port 8765] | history [--limit 20] | test | test-notify | version");
}
}
}

private static Engine buildEngine(AppConfig cfg) throws Exception {
SeenStore store = new SeenStore(AppConfig.root().resolve(cfg.storeDir));
return new Engine(cfg, store, Notifiers.build(cfg));
}

// ----------------------------------------
// 🔍 自己診断（test）
// ----------------------------------------
// 📝 診断1項目の結果
public record CheckResult(String name, boolean ok, String detail) {}

// 🔍 診断結果の整形（純粋関数・テスト対象）
public static String formatDiagnostics(List<CheckResult> results) {
StringBuilder sb = new StringBuilder();
sb.append("🛡️ AEGIS 自己診断 (v").append(Version.VERSION).append(")\n");
int okCount = 0;
for (CheckResult r: results) {
sb.append(r.ok()? "○ ": "× ").append(r.name());
if (r.detail()!= null &&!r.detail().isEmpty()) sb.append("（").append(r.detail()).append("）");
sb.append("\n");
if (r.ok()) okCount++;
}
sb.append(okCount == results.size()
? "結果: 全て正常です（" + okCount + "/" + results.size() + "）"
: "結果: " + (results.size() - okCount) + "件の失敗があります（" + okCount + "/" + results.size() + " 成功）");
return sb.toString();
}

// 🧪 全て成功なら 0、一部でも失敗があれば 1（純粋関数・テスト対象）
public static int diagnosticsExitCode(List<CheckResult> results) {
return results.stream().allMatch(CheckResult::ok)? 0: 1;
}

private interface Probe {
String run() throws Exception;
}

private static CheckResult probe(String name, Probe p) {
long t0 = System.nanoTime();
try {
String detail = p.run();
long ms = (System.nanoTime() - t0) / 1_000_000;
return new CheckResult(name, true, "接続OK " + ms + "ms" + (detail == null || detail.isEmpty()? "": " / " + detail));
} catch (Exception e) {
String msg = e.getMessage();
return new CheckResult(name, false, "失敗: " + (msg == null || msg.isEmpty()? e.getClass().getSimpleName(): msg));
}
}

private static int runDiagnostics(AppConfig cfg) {
List<CheckResult> results = new ArrayList<>();
Location loc0 = cfg.locations.isEmpty()? null: cfg.locations.get(0);
// 📡 地震・津波・EEW・警報・台風の情報源は A2S のみ。未設定時は A2S 対象が
// 取得できない状態として失敗扱いで明示する
if (cfg.a2sEnabled()) {
results.add(probe("A.A.S 稼働状態 (status)", () -> {
Map<String, Object> st = A2sClient.fetchStatus(cfg.a2sUrl, cfg.a2sToken);
Object counts = st.get("counts");
return "version " + Json.str(st.get("version")) + (counts == null? "": " / 保持 " + counts);
}));
results.add(probe("A.A.S 地震 (events?kind=quake)", () -> {
List<Event> v = A2sClient.fetchQuakes(cfg.a2sUrl, cfg.a2sToken, 1, cfg.locations);
return v.isEmpty()? "0件": "最新: " + v.get(0).title;
}));
results.add(probe("A.A.S 津波 (events?kind=tsunami)", () -> A2sClient.fetchTsunami(cfg.a2sUrl, cfg.a2sToken, 1).size() + "件"));
results.add(probe("A.A.S EEW (events?kind=eew)", () -> A2sClient.fetchEew(cfg.a2sUrl, cfg.a2sToken, 1, cfg.locations).size() + "件"));
results.add(probe("A.A.S 警報・注意報 (events?kind=warning)", () -> {
List<Event> v = A2sClient.fetchWarnings(cfg.a2sUrl, cfg.a2sToken, cfg.locations);
return v.isEmpty()? "発表なし": v.size() + "件発表中";
}));
results.add(probe("A.A.S 台風 (events?kind=typhoon)", () -> {
List<Event> v = A2sClient.fetchTyphoons(cfg.a2sUrl, cfg.a2sToken);
return v.isEmpty()? "台風なし": v.size() + "件";
}));
} else {
results.add(new CheckResult("A.A.S 連携 (地震・津波・EEW・警報・台風の情報源)", false,
"未設定: a2s_url / a2s_token (または A2S_URL / A2S_TOKEN) が必要です"));
}
if (loc0!= null) {
results.add(probe("気象庁 予報（" + loc0.name() + "）", () -> {
Event ev = JmaForecast.fetchForecast(loc0);
if (ev == null) throw new IllegalStateException("予報データなし");
return "取得OK";
}));
results.add(probe("Open-Meteo（" + loc0.name() + "）", () -> {
Event ev = OpenMeteo.fetchCurrent(loc0);
if (ev == null) throw new IllegalStateException("データなし");
return "取得OK";
}));
}
results.add(probe("RainViewer レーダー", () -> {
Radar.RainFrame f = Radar.fetchRainFrame();
if (f == null) throw new IllegalStateException("フレームなし");
return "最新フレームあり";
}));
results.add(probe("気象庁 降水ナウキャスト", () -> {
Radar.JmaFrame f = Radar.fetchJmaFrame();
if (f == null) throw new IllegalStateException("フレームなし");
return "最新フレームあり";
}));
results.add(probe("NHK ニュース RSS", () -> NewsFeed.fetchNews().size() + "件"));
// ⚙️ 設定の妥当性
results.add(new CheckResult("設定: 監視地点",!cfg.locations.isEmpty(), cfg.locations.size() + "地点"));
List<Notifiers.Notifier> notifiers = Notifiers.build(cfg);
boolean webhook = (cfg.discordWebhookUrl!= null &&!cfg.discordWebhookUrl.isEmpty())
|| (cfg.genericWebhookUrl!= null &&!cfg.genericWebhookUrl.isEmpty());
results.add(new CheckResult("設定: 通知先",!notifiers.isEmpty(),
notifiers.size() + "件（Webhook" + (webhook? "設定あり": "未設定・コンソール/ファイルのみ") + "）"));
boolean writable;
String wdetail;
try {
Path dir = AppConfig.root().resolve(cfg.storeDir);
Files.createDirectories(dir);
Path tmpF = dir.resolve(".aegis-write-test");
Files.writeString(tmpF, "ok", StandardCharsets.UTF_8);
Files.deleteIfExists(tmpF);
writable = true;
wdetail = "書込OK: " + dir;
} catch (Exception e) {
writable = false;
wdetail = "書込不可: " + e.getMessage();
}
results.add(new CheckResult("設定: データ保存先", writable, wdetail));
System.out.println(formatDiagnostics(results));
return diagnosticsExitCode(results);
}

// ----------------------------------------
// 🔔 テスト通知（test-notify）
// ----------------------------------------
// 明記のサンプルイベント（地震・震度5弱想定）。純粋関数・テスト対象
public static Event buildTestEvent() {
Event ev = new Event("earthquake",
"地震 最大震度5弱 テスト震源地",
"これは AEGIS のテスト通知です。本物の地震ではありません。\n震源: テスト震源地 / M6.0（想定） / 深さ約10km / 最大震度 5弱（想定）");
ev.severity = Severity.CRITICAL;
ev.maxScale = 45;
ev.source = "AEGIS テスト通知";
ev.occurredAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
ev.eventId = "test-notify-" + System.currentTimeMillis();
ev.actionGuide = "テスト通知のため、対応は不要です";
return ev;
}

private static void testNotify(AppConfig cfg) {
// 💾 重複排除ストアには記録しない（Engine.process を通さず直接ディスパッチする）
List<Notifiers.Notifier> notifiers = Notifiers.build(cfg);
Event ev = buildTestEvent();
Notifiers.dispatch(notifiers, ev);
System.out.println("テスト通知を送信しました（通知先 " + notifiers.size() + "件）。受信を確認してください。");
}
}
