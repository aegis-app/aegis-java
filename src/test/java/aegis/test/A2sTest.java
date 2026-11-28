package aegis.test;

import aegis.a2s.A2sClient;
import aegis.a2s.A2sConvert;
import aegis.a2s.A2sStream;
import aegis.a2s.WarningTracker;
import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Location;
import aegis.core.Severity;
import aegis.news.NewsFeed;
import aegis.parse.EewParser;
import aegis.parse.QuakeParser;
import aegis.parse.TsunamiParser;
import aegis.radar.Radar;
import aegis.weather.JmaForecast;
import aegis.weather.OpenMeteo;
import aegis.weather.WeatherTables;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static aegis.test.TestKit.check;
import static aegis.test.TestKit.contains;
import static aegis.test.TestKit.notContains;

// ========================================
// 🧪 A2sTest — A2S 変換・パーサ・SSE・天気・ニュース・レーダーの単体テスト
// ========================================
// 📌 JUnit 不要・ネットワーク不要。実行: java -cp out aegis.test.A2sTest
public final class A2sTest {
    private A2sTest() {}

    private static final Location TAKASAKI = new Location("高崎", 36.3219, 139.0023, "100000", "南部", "高崎市");
    private static final Location OMIYA = new Location("大宮", 35.9064, 139.6239, "110000", "南部", "さいたま市");
    private static final List<Location> LOCS = List.of(TAKASAKI, OMIYA);

    public static void main(String[] args) throws Exception {
        testValidation();
        testQuakeConvertAndParse();
        testQuakeMerge();
        testEew();
        testTsunami();
        testWarning();
        testTyphoon();
        testClientUrls();
        testStream();
        testJmaForecast();
        testOpenMeteo();
        testNews();
        testRadar();
        System.out.println("A2sTest: " + TestKit.passed + " checks passed");
    }

    private static Map<String, Object> obj(String json) {
        return Json.obj(Json.parse(json));
    }

    // ----------------------------------------
    // 🔍 A2S イベントの検証
    // ----------------------------------------
    private static final String QUAKE_JSON = """
{"key":"551:20261128090000","kind":"quake","time":"2026/11/28 09:00:00","updatedAt":"2026-11-28T09:01:00+09:00",
"telegramId":"20261128090000","announcedTime":"2026/11/28 09:01:00",
"issueType":"DetailScale","issueCorrect":"None",
"hypocenter":"テスト地方","magnitude":5.5,"depthKm":10,"lat":36.0,"lon":139.0,
"maxScale":45,"tsunamiKind":"None",
"points":[{"pref":"群馬県","name":"高崎市","scale":40,"isArea":false},
{"pref":"群馬県","name":"前橋市","scale":45,"isArea":false}],
"prefScales":[{"pref":"群馬県","scale":45}]}
""";

    private static final String EEW_JSON = """
{"key":"eew:20261128090000:2","baseKey":"eew:20261128090000","kind":"eew",
"time":"2026/11/28 09:00:10","originTime":"2026/11/28 09:00:00",
"hypocenter":"テスト沖","estMagnitude":6.0,"estScale":45,"depthKm":20,"lat":36.0,"lon":140.0,
"isWarn":true,"cancel":false,"isFinal":false,"isAssumption":false,"serial":2,
"eventId":"20261128090000",
"areaScales":[{"pref":"群馬県","name":"群馬県南部","scale":45,"scaleTo":99}],
"areaArrivals":[{"pref":"群馬県","arrivalTime":"2026/11/28 09:01:00"}]}
""";

    private static final String TSUNAMI_JSON = """
{"key":"552:20261128090500","kind":"tsunami","time":"2026/11/28 09:05:00",
"grade":"Warning","cancelled":false,
"areas":[{"name":"宮城県沿岸","grade":"Warning","maxHeightLabel":"3m","maxHeightValue":3.0,
"arrivalLabel":"2026/11/28 09:30","firstHeightCondition":""}]}
""";

    private static void testValidation() {
        check(A2sConvert.isA2sEvent(obj(QUAKE_JSON)), "validate quake ok");
        check(A2sConvert.isA2sEvent(obj(EEW_JSON)), "validate eew ok");
        check(A2sConvert.isA2sEvent(obj(TSUNAMI_JSON)), "validate tsunami ok");
        check(!A2sConvert.isA2sEvent(obj("{\"key\":\"x\",\"kind\":\"quake\",\"time\":\"t\"}")), "validate quake missing fields");
        check(!A2sConvert.isA2sEvent(obj("{\"key\":\"x\",\"kind\":\"unknown\"}")), "validate unknown kind");
        check(!A2sConvert.isA2sEvent(obj("{\"kind\":\"quake\"}")), "validate missing key");
        check(!A2sConvert.isA2sEvent(Map.of()), "validate empty map");
        check(!A2sConvert.isA2sEvent(null), "validate null");
        check(A2sConvert.convertEvent(obj("{\"key\":\"x\",\"kind\":\"unknown\"}"), LOCS).isEmpty(), "convert invalid -> empty");
    }

    // ----------------------------------------
    // 🌏 地震の変換と解析
    // ----------------------------------------
    private static void testQuakeConvertAndParse() {
        List<Event> evs = A2sConvert.convertEvent(obj(QUAKE_JSON), LOCS);
        check(evs.size() == 1, "quake convert 1 event");
        Event ev = evs.get(0);
        check(ev.eventId.equals("20261128090000"), "quake eventId is telegramId");
        check(ev.title.equals("地震 最大震度5弱 テスト地方"), "quake title");
        check(ev.severity == Severity.CRITICAL, "quake severity CRITICAL");
        check(ev.maxScale == 45, "quake maxScale");
        contains(ev.body, "深さ約10km", "quake depth");
        contains(ev.body, "津波の心配なし", "quake tsunami none");
        contains(ev.body, "高崎市の震度 4（実測", "quake city measured scale");
        contains(ev.body, "大宮: 実測なし", "quake unmeasured location noted");
        contains(ev.body, "群馬県内の最大震度 5弱", "quake pref max");
        contains(ev.body, "観測点情報（実測）: 全2地点", "quake observation points");
        contains(ev.body, "発表: 2026/11/28 09:01:00（確定・震度詳細情報）", "quake issue note");
        check(ev.source.equals("A.A.S（P2P地震情報・気象庁）"), "quake source label");

        // 🌏 深さ 0 = ごく浅い、深さ欠落 = 不明
        String shallow = QUAKE_JSON.replace("\"depthKm\":10", "\"depthKm\":0");
        contains(A2sConvert.convertEvent(obj(shallow), LOCS).get(0).body, "深さごく浅い", "quake depth 0 shallow");
        String noDepth = QUAKE_JSON.replace("\"depthKm\":10,", "");
        contains(A2sConvert.convertEvent(obj(noDepth), LOCS).get(0).body, "深さ不明", "quake depth missing unknown");
        // 🌏 津波の未知値は「心配なし」にしない
        String checking = QUAKE_JSON.replace("\"tsunamiKind\":\"None\"", "\"tsunamiKind\":\"Checking\"");
        contains(A2sConvert.convertEvent(obj(checking), LOCS).get(0).body, "津波の有無は確認中", "quake tsunami checking safe side");
        // 🌏 国外地震の原表記とコメント原文
        String foreign = QUAKE_JSON.replace("\"tsunamiKind\":\"None\"",
        "\"tsunamiKind\":\"None\",\"originTime\":\"Nov 28, 2026 00:00 UTC\",\"tsunamiText\":\"津波の心配はありません\"");
        Event fev = A2sConvert.convertEvent(obj(foreign), LOCS).get(0);
        contains(fev.body, "発生（原表記）: Nov 28, 2026 00:00 UTC", "quake foreign originTime");
        contains(fev.body, "津波コメント: 津波の心配はありません", "quake tsunamiText");

        // 📍 観測点ノートと市区町村照合の単体検証
        check(QuakeParser.observationPointsNote(List.of()).equals("観測点情報なし（観測点別の実測値は未発表）"), "observation note empty");
        check(QuakeParser.cityMatchesAddr("さいたま市", "さいたま市大宮区"), "city match prefix");
        check(QuakeParser.cityMatchesAddr("高崎市", "高崎市"), "city match exact");
        check(!QuakeParser.cityMatchesAddr("高崎市", "前橋市"), "city mismatch");
        check(QuakeParser.typeRank("DetailScale") == 4 && QuakeParser.typeRank("ScalePrompt") == 1, "quake type rank");
        check(QuakeParser.issueLabel("DetailScale", "None").equals("確定"), "issue label final");
        check(QuakeParser.issueLabel("DetailScale", "訂正").equals("訂正"), "issue label correction");
        check(QuakeParser.issueLabel("ScalePrompt", "").equals("速報"), "issue label prompt");
    }

    // ----------------------------------------
    // 🌏 地震のマージ（段階発表の統合）
    // ----------------------------------------
    private static String quakeItem(String id, String issueTime, String type, int depth, int maxScale, String eqTime) {
        return "{\"id\":\"" + id + "\",\"time\":\"" + issueTime + "\","
            + "\"issue\":{\"time\":\"" + issueTime + "\",\"type\":\"" + type + "\",\"correct\":\"None\"},"
            + "\"earthquake\":{\"time\":\"" + eqTime + "\",\"maxScale\":" + maxScale + ",\"domesticTsunami\":\"None\","
            + "\"hypocenter\":{\"name\":\"テスト地方\",\"magnitude\":5.0,\"depth\":" + depth + ",\"latitude\":36.0,\"longitude\":139.0}},"
            + "\"points\":[{\"pref\":\"群馬県\",\"addr\":\"前橋市\",\"scale\":" + maxScale + ",\"isArea\":false}]}";
    }

    private static void testQuakeMerge() {
        String eqTime = "2026/11/20 21:46:00";
        // 📝 実例ベース: 震度速報（深さ -1 のセンチネル）→ 震源報・詳細報（深さ 0 = ごく浅い）
        Object data = Json.parse("[" + quakeItem("a", "2026/11/20 21:47:00", "ScalePrompt", -1, 40, eqTime) + ","
            + quakeItem("b", "2026/11/20 21:49:00", "DetailScale", 0, 40, eqTime) + "]");
        List<Event> evs = QuakeParser.parse(data, LOCS);
        check(evs.size() == 1, "merge groups into one");
        contains(evs.get(0).body, "全2報を統合", "merge count note");
        contains(evs.get(0).body, "深さごく浅い", "merge depth 0 wins over sentinel -1");
        notContains(evs.get(0).body, "深さ約0km", "merge never says depth 0km");
        check(evs.get(0).eventId.equals("b"), "merge primary is DetailScale telegram");
        // 📝 速報単独では深さ不明＋推定情報の注記
        Object promptOnly = Json.parse("[" + quakeItem("a", "2026/11/20 21:47:00", "ScalePrompt", -1, 40, eqTime) + "]");
        Event pev = QuakeParser.parse(promptOnly, LOCS).get(0);
        contains(pev.body, "深さ不明", "prompt only depth unknown");
        contains(pev.body, "推定情報: 震度速報の段階では", "prompt estimation note");
        // 📝 別の発生時刻は別グループ
        Object two = Json.parse("[" + quakeItem("a", "2026/11/20 21:47:00", "DetailScale", 10, 40, eqTime) + ","
            + quakeItem("c", "2026/11/20 22:47:00", "DetailScale", 10, 30, "2026/11/20 22:46:00") + "]");
        check(QuakeParser.parse(two, LOCS).size() == 2, "different origin times stay separate");
    }

    // ----------------------------------------
    // 🚨 EEW
    // ----------------------------------------
    private static void testEew() {
        List<Event> evs = A2sConvert.convertEvent(obj(EEW_JSON), LOCS);
        check(evs.size() == 1, "eew convert 1 event");
        Event ev = evs.get(0);
        check(ev.title.equals("緊急地震速報（警報） テスト沖"), "eew warn title");
        check(ev.isWarn && ev.serial == 2, "eew warn flag and serial");
        contains(ev.body, "推定震源: テスト沖", "eew body estimation header");
        contains(ev.body, "第2報", "eew serial in body");
        contains(ev.body, "地域別予想震度（推定）: 群馬県南部 5弱程度以上", "eew area scale with scaleTo 99");
        contains(ev.body, "高崎: 予想震度5弱（推定）", "eew location forecast");
        contains(ev.body, "高崎の主要動到達予想 9:01", "eew arrival note");
        check(ev.eventId.startsWith("eew-2026/11/28 09:00:00@360,1400"), "eew coord identity key");
        check(ev.actionGuide.equals("強い揺れに備え、頭を守り安全な場所へ移動する"), "eew guide strong");
        check(ev.source.equals("A.A.S（緊急地震速報（警報））"), "eew source warn");

        // 📝 予報（isWarn=false）
        String forecast = EEW_JSON.replace("\"isWarn\":true", "\"isWarn\":false");
        Event fev = A2sConvert.convertEvent(obj(forecast), LOCS).get(0);
        check(fev.title.startsWith("緊急地震速報（予報）"), "eew forecast title");
        check(fev.source.equals("A.A.S（緊急地震速報（予報））"), "eew source forecast");
        // 📝 最終報・仮定震源
        String fin = EEW_JSON.replace("\"isFinal\":false", "\"isFinal\":true").replace("\"isAssumption\":false", "\"isAssumption\":true");
        Event finev = A2sConvert.convertEvent(obj(fin), LOCS).get(0);
        contains(finev.body, "最終報", "eew final note");
        contains(finev.body, "仮定震源要素による推定", "eew assumption note");
        check(finev.isFinal && finev.isAssumption, "eew final/assumption flags");
        // 📝 取消報: 別ID（-cancel）・重要度 MEDIUM・ガイドなし
        String cancel = EEW_JSON.replace("\"cancel\":false", "\"cancel\":true");
        Event cev = A2sConvert.convertEvent(obj(cancel), LOCS).get(0);
        check(cev.title.startsWith("緊急地震速報（取消）"), "eew cancel title");
        check(cev.eventId.endsWith("-cancel"), "eew cancel separate id");
        check(cev.severity == Severity.MEDIUM, "eew cancel severity MEDIUM");
        check(cev.actionGuide.isEmpty(), "eew cancel no guide");

        // 🧪 行動ガイドの境界
        check(EewParser.actionGuide(45, false).contains("強い揺れ"), "guide scale 45");
        check(EewParser.actionGuide(40, false).contains("やや強い"), "guide scale 40");
        check(EewParser.actionGuide(30, false).equals("揺れに注意する"), "guide scale 30");
        check(EewParser.actionGuide(-1, true).contains("強い揺れ"), "guide unknown warn");
        check(EewParser.actionGuide(-1, false).equals("揺れに注意する"), "guide unknown forecast");
        // 🧪 座標キー・都道府県名・距離
        check(EewParser.coordKey(Json.obj(Json.parse("{\"latitude\":36.32,\"longitude\":139.00}"))).equals("@363,1390"), "coord key rounding");
        check(EewParser.coordKey(Json.obj(Json.parse("{\"latitude\":-200,\"longitude\":-200}"))).isEmpty(), "coord key out of range");
        check(EewParser.prefNameForCode("100000").equals("群馬県"), "pref name gunma");
        check(EewParser.prefNameForCode("011000").equals("北海道"), "pref name hokkaido subcode");
        check(EewParser.prefNameForCode("999999").isEmpty(), "pref name unknown");
        double d = EewParser.haversineKm(36.3219, 139.0023, 35.9064, 139.6239);
        check(d > 60 && d < 80, "haversine takasaki-omiya ~72km");
        // 🧪 推定最大震度のフォールバック（areas の最大値）
        Map<String, Object> item = obj("{\"earthquake\":{},\"areas\":[{\"scale\":30},{\"scale\":45}]}");
        check(EewParser.estScale(item, Json.obj(item.get("earthquake"))) == 45, "estScale falls back to areas max");
    }

    // ----------------------------------------
    // 🌊 津波
    // ----------------------------------------
    private static void testTsunami() {
        List<Event> evs = A2sConvert.convertEvent(obj(TSUNAMI_JSON), LOCS);
        check(evs.size() == 1, "tsunami convert 1 event");
        Event ev = evs.get(0);
        check(ev.eventId.equals("20261128090500"), "tsunami eventId strips 552 prefix");
        check(ev.title.startsWith("津波警報（"), "tsunami warning title");
        check(ev.severity == Severity.CRITICAL, "tsunami warning CRITICAL");
        contains(ev.body, "宮城県沿岸（予想 3m）（第1波到達予想 09:30）", "tsunami area detail");
        // 📝 グレード別の重要度
        String major = TSUNAMI_JSON.replace("\"grade\":\"Warning\"", "\"grade\":\"MajorWarning\"")
            .replace("\"grade\":\"Warning\",\"maxHeightLabel\"", "\"grade\":\"MajorWarning\",\"maxHeightLabel\"");
        check(A2sConvert.convertEvent(obj(major), LOCS).get(0).title.startsWith("大津波警報"), "tsunami major title");
        String watch = TSUNAMI_JSON.replace("\"grade\":\"Warning\"", "\"grade\":\"Watch\"")
            .replace("\"grade\":\"Warning\",\"maxHeightLabel\"", "\"grade\":\"Watch\",\"maxHeightLabel\"");
        check(A2sConvert.convertEvent(obj(watch), LOCS).get(0).severity == Severity.HIGH, "tsunami watch HIGH");
        // 📝 解除
        String cancelled = TSUNAMI_JSON.replace("\"cancelled\":false", "\"cancelled\":true");
        Event cev = A2sConvert.convertEvent(obj(cancelled), LOCS).get(0);
        check(cev.title.startsWith("津波警報・注意報 解除"), "tsunami cancelled title");
        check(cev.severity == Severity.MEDIUM, "tsunami cancelled MEDIUM");
        // 🧪 地域テキスト: グレード別グループ化と「ほかN区域」
        Map<String, Object> item = obj("{\"areas\":["
            + "{\"name\":\"A沿岸\",\"grade\":\"Warning\"},{\"name\":\"B沿岸\",\"grade\":\"Warning\"},"
            + "{\"name\":\"C沿岸\",\"grade\":\"Warning\"},{\"name\":\"D沿岸\",\"grade\":\"Warning\"},"
            + "{\"name\":\"E沿岸\",\"grade\":\"Warning\"},{\"name\":\"F沿岸\",\"grade\":\"Watch\"}]}");
        String txt = TsunamiParser.areaText(item);
        contains(txt, "津波警報: A沿岸、B沿岸、C沿岸、D沿岸 ほか1区域", "tsunami area grouping cap");
        contains(txt, "津波注意報: F沿岸", "tsunami area watch group");
        check(TsunamiParser.areaText(obj("{\"areas\":[]}")).equals("全国"), "tsunami area empty -> 全国");
    }

    // ----------------------------------------
    // ⚠️ 警報・注意報（世代管理を含む）
    // ----------------------------------------
    private static String warningJson(String office, String areaCode, String areaName, String warnings, boolean released) {
        return "{\"key\":\"warning:" + office + ":" + areaCode + "\",\"kind\":\"warning\","
            + "\"officeCode\":\"" + office + "\",\"areaCode\":\"" + areaCode + "\",\"areaName\":\"" + areaName + "\","
            + "\"maxSeverity\":3,\"headline\":\"見出し文\",\"reportDatetime\":\"2026/11/28 09:00\","
            + "\"time\":\"2026/11/28 09:00:00\",\"released\":" + released + ",\"warnings\":[" + warnings + "]}";
    }

    private static void testWarning() {
        // 📝 テスト専用の官署コードで世代管理の干渉を避ける
        Location testLoc = new Location("テスト地", 36.0, 139.0, "999990", "南部", "テスト市");
        List<Location> locs = List.of(testLoc);
        String w = "{\"code\":\"03\",\"name\":\"大雨警報\",\"status\":\"発表\",\"severity\":3}";
        List<Event> evs = A2sConvert.convertEvent(obj(warningJson("999990", "999910", "南部", w, false)), locs);
        check(evs.size() == 1, "warning convert 1 event");
        Event ev = evs.get(0);
        check(ev.title.equals("気象 大雨警報（テスト地）"), "warning title");
        check(ev.severity == Severity.HIGH, "warning severity from a2s 3 -> HIGH");
        check(ev.eventId.equals("warn-999990-03-g0"), "warning eventId generation 0");
        contains(ev.body, "テスト地周辺に大雨警報が発表されています。見出し文", "warning body headline");
        check(ev.actionGuide.contains("川や崖に近づかない"), "warning guide rain");
        // 📝 継続中は同じ世代（同じ eventId）で再通知されない形になる
        List<Event> again = A2sConvert.convertEvent(obj(warningJson("999990", "999910", "南部", w, false)), locs);
        check(again.get(0).eventId.equals("warn-999990-03-g0"), "warning same generation while active");
        // 📝 解除イベントは通知せず世代だけ進める → 再発表は g1 で別イベントになる
        check(A2sConvert.convertEvent(obj(warningJson("999990", "999910", "南部", "", true)), locs).isEmpty(), "warning release no event");
        List<Event> repub = A2sConvert.convertEvent(obj(warningJson("999990", "999910", "南部", w, false)), locs);
        check(repub.get(0).eventId.equals("warn-999990-03-g1"), "warning republication generation 1");
        // 📝 status が解除のエントリは発表中として数えない
        String rel = "{\"code\":\"03\",\"name\":\"大雨警報\",\"status\":\"解除\",\"severity\":3}";
        check(A2sConvert.convertEvent(obj(warningJson("999990", "999910", "南部", rel, false)), locs).isEmpty(), "warning released status excluded");
        // 📝 特別警報は CRITICAL、注意報は MEDIUM
        String special = "{\"code\":\"33\",\"name\":\"大雨特別警報\",\"status\":\"発表\",\"severity\":4}";
        check(A2sConvert.convertEvent(obj(warningJson("999990", "999910", "南部", special, false)), locs).get(0).severity == Severity.CRITICAL, "warning special CRITICAL");
        String advisory = "{\"code\":\"10\",\"name\":\"大雨注意報\",\"status\":\"発表\",\"severity\":2}";
        check(A2sConvert.convertEvent(obj(warningJson("999990", "999910", "南部", advisory, false)), locs).get(0).severity == Severity.MEDIUM, "warning advisory MEDIUM");
        // 📝 区域名が一致しない地点には出さない／未解決表記なら官署全域に出す
        String otherArea = warningJson("999990", "999920", "北部", w, false);
        check(A2sConvert.convertEvent(obj(otherArea), locs).isEmpty(), "warning other area excluded");
        String unresolved = warningJson("999990", "999920", "区域 999920", w, false);
        check(A2sConvert.convertEvent(obj(unresolved), locs).size() == 1, "warning unresolved area falls back to office-wide");
        // 📝 官署が違う地点には出さない
        check(A2sConvert.convertEvent(obj(warningJson("888880", "888810", "南部", w, false)), locs).isEmpty(), "warning other office excluded");
        // 🧪 重要度の写像（severity なしはコード・名称から）
        check(A2sConvert.warningSeverity(obj("{\"code\":\"33\",\"name\":\"大雨特別警報\"}")) == Severity.CRITICAL, "warning severity by code special");
        check(A2sConvert.warningSeverity(obj("{\"code\":\"05\",\"name\":\"暴風警報\"}")) == Severity.HIGH, "warning severity by name warning");
        check(A2sConvert.warningSeverity(obj("{\"code\":\"14\",\"name\":\"雷注意報\"}")) == Severity.MEDIUM, "warning severity by name advisory");
        check(A2sConvert.warningGuide("暴風警報").contains("飛来物"), "warning guide wind");
        check(A2sConvert.warningGuide("大雪警報").contains("路面凍結"), "warning guide snow");
        check(A2sConvert.warningGuide("雷注意報").isEmpty(), "warning guide none");
        // 🧪 世代管理そのもの
        Map<String, Integer> g0 = WarningTracker.advanceGenerations("test|area", Set.of("03", "05"));
        check(g0.get("03") == 0 && g0.get("05") == 0, "tracker initial generations");
        Map<String, Integer> g1 = WarningTracker.advanceGenerations("test|area", Set.of("03"));
        check(g1.get("03") == 0, "tracker surviving code keeps generation");
        Map<String, Integer> g2 = WarningTracker.advanceGenerations("test|area", Set.of("03", "05"));
        check(g2.get("05") == 1, "tracker reappeared code generation +1");
    }

    // ----------------------------------------
    // 🌀 台風
    // ----------------------------------------
    private static void testTyphoon() {
        String json = "{\"key\":\"typhoon:15\",\"kind\":\"typhoon\",\"time\":\"2026/11/28 09:00\","
            + "\"lines\":[\"台風第15号\",\"中心気圧 990hPa\"]}";
        List<Event> evs = A2sConvert.convertEvent(obj(json), LOCS);
        check(evs.size() == 1, "typhoon convert 1 event");
        Event ev = evs.get(0);
        check(ev.eventId.equals("a2s-typhoon:15"), "typhoon eventId from key");
        contains(ev.body, "台風第15号\n中心気圧 990hPa", "typhoon lines joined");
        contains(ev.body, "詳細は気象庁の台風情報を確認してください。", "typhoon footer");
        check(ev.severity == Severity.HIGH, "typhoon severity HIGH");
        check(A2sConvert.typhoonToEvent(obj("{\"key\":\"typhoon:99\",\"lines\":[]}")) == null, "typhoon empty lines -> null");
    }

    // ----------------------------------------
    // 🌐 REST クライアントの URL と応答解析
    // ----------------------------------------
    private static void testClientUrls() {
        check(A2sClient.eventsUrl("http://h:8730/", "quake", null, 0).equals("http://h:8730/v1/events?kind=quake"), "eventsUrl kind");
        check(A2sClient.eventsUrl("http://h:8730", null, "2026-11-28T00:00:00Z", 50)
            .equals("http://h:8730/v1/events?since=2026-11-28T00:00:00Z&limit=50"), "eventsUrl since+limit");
        check(A2sClient.eventsUrl("http://h:8730", null, null, 0).equals("http://h:8730/v1/events"), "eventsUrl bare");
        check(A2sClient.statusUrl("http://h:8730/").equals("http://h:8730/v1/status"), "statusUrl");
        Object resp = Json.parse("{\"events\":[" + QUAKE_JSON + ",{\"key\":\"bad\",\"kind\":\"quake\"}]}");
        check(A2sClient.parseEventsResponse(resp).size() == 1, "parseEventsResponse filters invalid");
        check(A2sClient.parseEventsResponse(Json.parse("{}")).isEmpty(), "parseEventsResponse empty");
    }

    // ----------------------------------------
    // 📡 SSE（パーサ・再生ガード・重複判定・再接続）
    // ----------------------------------------
    private static void testStream() {
        A2sStream.SseParser p = new A2sStream.SseParser();
        List<A2sStream.SseParser.Frame> frames = p.push("event: quake\ndata: {\"a\":1}\n\n");
        check(frames.size() == 1 && frames.get(0).type().equals("quake"), "sse basic frame");
        check(frames.get(0).data().equals("{\"a\":1}"), "sse frame data");
        check(p.push(": heartbeat\n\n").isEmpty(), "sse comment ignored");
        List<A2sStream.SseParser.Frame> multi = p.push("data: line1\ndata: line2\n\n");
        check(multi.size() == 1 && multi.get(0).data().equals("line1\nline2"), "sse multi data lines");
        check(multi.get(0).type().equals("message"), "sse default type message");
        // 📝 チャンク分割されても組み立てられる
        A2sStream.SseParser p2 = new A2sStream.SseParser();
        check(p2.push("event: ee").isEmpty(), "sse partial no frame");
        List<A2sStream.SseParser.Frame> joined = p2.push("w\ndata: x\n\n");
        check(joined.size() == 1 && joined.get(0).type().equals("eew"), "sse chunked assembly");

        check(A2sStream.reconnectDelayMs(0) == 5000, "reconnect delay 0");
        check(A2sStream.reconnectDelayMs(1) == 10000, "reconnect delay 1");
        check(A2sStream.reconnectDelayMs(2) == 30000, "reconnect delay 2");
        check(A2sStream.reconnectDelayMs(99) == 60000, "reconnect delay capped");

        List<Event> received = new ArrayList<>();
        A2sStream stream = new A2sStream("http://127.0.0.1:1", "token", LOCS, received::addAll);
        check(stream.markIfNew("k1", "u1"), "markIfNew first");
        check(!stream.markIfNew("k1", "u1"), "markIfNew same content dup");
        check(stream.markIfNew("k1", "u2"), "markIfNew updated content");
        check(!stream.markIfNew("", "u1"), "markIfNew empty key");
        // 📝 再生イベントは状態へマージするだけで通知しない
        Object quakeObj = Json.parse(QUAKE_JSON);
        check(stream.handleEventJson(quakeObj, true).isEmpty(), "stream replay not notified");
        check(stream.handleEventJson(quakeObj, false).isEmpty(), "stream replayed content deduped after ready");
        // 📝 新しい更新は変換されて返る
        String upd = QUAKE_JSON.replace("\"maxScale\":45", "\"maxScale\":50")
                .replace("09:01:00+09:00", "09:02:00+09:00");
        check(stream.handleEventJson(Json.parse(upd), false).size() == 1, "stream new update converted");
        // 📝 不正なイベントは握って空
        check(stream.handleEventJson(Json.parse("{\"kind\":\"quake\"}"), false).isEmpty(), "stream invalid swallowed");
        // 📝 フレーム処理: ready で再生終了、対象外種別は素通り
        boolean replay = stream.handleFrame(new A2sStream.SseParser.Frame("ready", ""), true);
        check(!replay, "stream ready ends replay");
        check(stream.handleFrame(new A2sStream.SseParser.Frame("master", "{}"), false) == false, "stream ignores other types");
        check(stream.noteFailureAndDelay() == 5000, "stream failure delay 1st");
        check(stream.noteFailureAndDelay() == 10000, "stream failure delay 2nd");
        stream.noteConnected();
        check(stream.noteFailureAndDelay() == 5000, "stream failure delay resets after connect");
    }

    // ----------------------------------------
    // 🌦️ 気象庁予報
    // ----------------------------------------
    private static final String FORECAST_JSON = """
[{"publishingOffice":"前橋地方気象台","reportDatetime":"2026-11-28T05:00:00+09:00","timeSeries":[
{"timeDefines":["2026-11-28T05:00:00+09:00","2026-11-29T00:00:00+09:00"],"areas":[
{"area":{"name":"南部","code":"100010"},"weathers":["晴れ","曇り"],"winds":["北西の風","南の風"]},
{"area":{"name":"北部","code":"100020"},"weathers":["曇り","雨"],"winds":["北の風","北の風"]}]},
{"timeDefines":["2026-11-28T06:00:00+09:00","2026-11-28T12:00:00+09:00","2026-11-28T18:00:00+09:00","2026-11-29T00:00:00+09:00"],"areas":[
{"area":{"name":"南部","code":"100010"},"pops":["10","20","30","40"]}]},
{"timeDefines":["2026-11-28T09:00:00+09:00","2026-11-29T09:00:00+09:00"],"areas":[
{"area":{"name":"前橋","code":"100010"},"temps":["5","15","6","16"]}]}
]}]
""";

    private static void testJmaForecast() {
        Object data = Json.parse(FORECAST_JSON);
        check(JmaForecast.forecastAreaCode(data, "南部").equals("100010"), "forecast area code");
        check(JmaForecast.forecastAreaCode(data, "存在しない").isEmpty(), "forecast area code missing");
        Event ev = JmaForecast.parseForecast(data, TAKASAKI);
        check(ev!= null, "forecast parsed");
        contains(ev.body, "高崎: 今日 晴れ", "forecast today weather");
        contains(ev.body, "明日 曇り", "forecast tomorrow weather");
        contains(ev.body, "降水確率:", "forecast pops present");
        contains(ev.body, "気温（前橋）: 今日 5〜15℃ / 明日 6〜16℃", "forecast temps by date");
        contains(ev.body, "出典: 概況・降水確率・気温は気象庁", "forecast source role note");
        check(ev.source.equals("気象庁（前橋地方気象台）"), "forecast source office");
        // 📝 区域が一致しない地点は先頭区域＋注記
        Location nowhere = new Location("未知", 0, 0, "100000", "西部");
        Event fev = JmaForecast.parseForecast(data, nowhere);
        contains(fev.body, "注: 指定区域（西部）と一致しないため", "forecast fallback note");
        // 🕐 降水確率の時刻フィルタ: 2026-11-28 13:00 時点では 06〜12時枠（終了12時）は経過済み
        String pops = JmaForecast.popsText(data, "100010", LocalDateTime.of(2026, 11, 28, 13, 0));
        notContains(pops, "6〜12時", "pops expired slot filtered");
        contains(pops, "12〜18時20%", "pops current slot kept");
        // 🧪 tempsByDate: 同数形式（午前=最低）
        List<Object> temps = List.of("5", "15", "6", "16");
        List<Object> defs = List.of("2026-11-28T00:00:00+09:00", "2026-11-28T12:00:00+09:00",
        "2026-11-29T00:00:00+09:00", "2026-11-29T12:00:00+09:00");
        String byDate = JmaForecast.tempsByDate(temps, defs, LocalDate.of(2026, 11, 28));
        check(byDate.equals("今日 5〜15℃ / 明日 6〜16℃"), "tempsByDate same-count form");
        check(JmaForecast.tempsByDate(temps, List.of(), LocalDate.of(2026, 11, 28)).isEmpty(), "tempsByDate no defines");
        // 🧪 区域コードキャッシュ: 空はキャッシュしない
        check(!JmaForecast.cacheAreaCode("test|empty", ""), "area code empty not cached");
        check(JmaForecast.cacheAreaCode("test|ok", "123"), "area code cached");
        check(JmaForecast.cachedAreaCode("test|ok").equals("123"), "area code read back");
        // 🧪 警報コード表
        check(JmaForecast.WARNING_CODES.get("33").equals("大雨特別警報"), "warning code table special");
        check(JmaForecast.WARNING_CODES.get("03").equals("大雨警報"), "warning code table rain");
        // 🧪 popsRows
        List<WeatherTables.PopRow> rows = JmaForecast.popsRows(data, "100010", LocalDateTime.of(2026, 11, 28, 5, 0));
        check(rows.size() == 4 && rows.get(0).label().equals("6〜12時"), "popsRows basic");
    }

    // ----------------------------------------
    // 🌤️ Open-Meteo
    // ----------------------------------------
    private static final String METEO_JSON = """
{"current":{"time":"2026-11-28T10:00","temperature_2m":12.0,"weather_code":61,"wind_speed_10m":10.0},
"daily":{"time":["2026-11-28","2026-11-29"],
"weather_code_jma_seamless":[61,3],
"temperature_2m_max_jma_seamless":[15.0,14.0],
"temperature_2m_min_jma_seamless":[5.0,6.0],
"precipitation_probability_max_ecmwf_ifs025":[90,20],
"precipitation_sum_jma_seamless":[12.5,0.0]}}
""";

    private static void testOpenMeteo() {
        Object data = Json.parse(METEO_JSON);
        Event ev = OpenMeteo.parseCurrent(data, TAKASAKI);
        contains(ev.body, "高崎: 現在 雨 12℃", "meteo current");
        contains(ev.body, "今日 5〜15℃", "meteo today range");
        contains(ev.body, "降水確率 最大90%", "meteo pop from ecmwf suffix");
        contains(ev.body, "降水量 今日 12.5mm", "meteo precip");
        contains(ev.body, "週間: 11/28 🌧 5〜15℃ / 11/29 ☁ 6〜14℃", "meteo weekly line");
        check(ev.actionGuide.contains("大きめの傘"), "meteo umbrella guide at pop 90");
        check(ev.source.equals(OpenMeteo.SOURCE_LABEL), "meteo source label");
        // 📝 接尾辞なしキーへのフォールバック（旧フィクスチャ形式）
        Object plain = Json.parse("{\"current\":{\"time\":\"t\",\"temperature_2m\":20.0,\"weather_code\":0,\"wind_speed_10m\":3.0},"
            + "\"daily\":{\"time\":[\"2026-11-28\"],\"weather_code\":[0],\"temperature_2m_max\":[22.0],\"temperature_2m_min\":[10.0],"
            + "\"precipitation_probability_max\":[10],\"precipitation_sum\":[0.0]}}");
        contains(OpenMeteo.parseCurrent(plain, TAKASAKI).body, "現在 快晴 20℃", "meteo plain keys fallback");
        // 🧪 整形・絵文字・アイコン
        check(OpenMeteo.fmtNum(12.0).equals("12"), "fmtNum integer");
        check(OpenMeteo.fmtNum(12.5).equals("12.5"), "fmtNum decimal");
        check(OpenMeteo.wmoEmoji(0).equals("☀"), "wmo emoji sunny");
        check(OpenMeteo.wmoEmoji(95).equals("⛈"), "wmo emoji thunder");
        check(OpenMeteo.wxIcon(0, 90, 12).equals("🌧"), "wxIcon rain priority");
        check(OpenMeteo.wxIcon(0, 0, 23).equals("🌙"), "wxIcon night moon");
        check(OpenMeteo.wxIcon(0, 0, 12).equals("☀"), "wxIcon day sun");
        check(OpenMeteo.MODELS.equals("jma_seamless,ecmwf_ifs025"), "meteo models fixed order");
        // 🧪 時間別テーブル: 3時間区間の集約と区間最大
        LocalDateTime base = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).plusHours(1);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
        StringBuilder times = new StringBuilder(), temps = new StringBuilder(), codes = new StringBuilder(),
            pops = new StringBuilder(), precips = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) { times.append(","); temps.append(","); codes.append(","); pops.append(","); precips.append(",");}
            times.append("\"").append(base.plusHours(i).format(fmt)).append("\"");
            temps.append(10 + i);
            codes.append(3);
            pops.append(i == 1? 80: 10);
            precips.append("1.0");
        }
        Map<String, Object> hourly = Json.obj(Json.parse("{\"time\":[" + times + "],\"temperature_2m_jma_seamless\":[" + temps
            + "],\"weather_code_jma_seamless\":[" + codes + "],\"precipitation_probability_ecmwf_ifs025\":[" + pops
            + "],\"precipitation_jma_seamless\":[" + precips + "]}"));
        List<WeatherTables.HourRow> rows = OpenMeteo.hourlyRows(hourly, base.minusMinutes(1));
        check(rows.size() == 2, "hourly rows 3h blocks");
        check(rows.get(0).popMax().equals("80"), "hourly block popMax captures peak");
        check(rows.get(0).precip().equals("3"), "hourly block precip sum");
        // 🧪 週間テーブル
        Map<String, Object> daily = Json.obj(Json.obj(data).get("daily"));
        List<WeatherTables.DayRow> wrows = OpenMeteo.weeklyRows(daily);
        check(wrows.size() == 2 && wrows.get(0).temp().equals("5〜15"), "weekly rows");
        check(wrows.get(0).pop().equals("90"), "weekly rows pop");
    }

    // ----------------------------------------
    // 📰 ニュース
    // ----------------------------------------
    private static void testNews() {
        check(NewsFeed.decodeEntities("a&lt;b&amp;c&#x9707;").equals("a<b&c震"), "news entity decode");
        check(NewsFeed.decodeEntities("通常テキスト").equals("通常テキスト"), "news entity plain");
        check(NewsFeed.summarizeDesc("<p>abc</p> def", 80).equals("abc def"), "news summary strips html");
        String longDesc = "あ".repeat(100);
        check(NewsFeed.summarizeDesc(longDesc, 80).length() == 81, "news summary truncated");
        check(NewsFeed.xmlTag("<item><title><![CDATA[見出し]]></title></item>", "title").equals("見出し"), "news xmlTag cdata");
        check(NewsFeed.rssTime("Sat, 28 Nov 2026 09:30:00 +0900").equals("9:30"), "news rss time");
        String rss0 = "<rss><channel>"
            + "<item><title>地震のニュース</title><link>http://n/1</link><description>震度5弱を観測</description><pubDate>Sat, 28 Nov 2026 09:30:00 +0900</pubDate></item>"
            + "<item><title>通常の話題</title><link>http://n/2</link><description>説明</description><pubDate>Sat, 28 Nov 2026 08:00:00 +0900</pubDate></item>"
            + "</channel></rss>";
        String rss1 = "<rss><channel>"
            + "<item><title>地震のニュース</title><link>http://n/1</link><description>重複</description><pubDate>Sat, 28 Nov 2026 09:30:00 +0900</pubDate></item>"
            + "<item><title>台風接近</title><link>http://n/3</link><description>大雨に注意</description><pubDate>Sat, 28 Nov 2026 07:00:00 +0900</pubDate></item>"
            + "</channel></rss>";
        List<NewsFeed.NewsItem> items = NewsFeed.parseNews(rss0, rss1);
        check(items.size() == 3, "news merged dedup by link");
        check(items.get(0).title().equals("地震のニュース"), "news disaster first");
        check(items.get(1).title().equals("台風接近"), "news disaster second");
        check(items.get(2).title().equals("通常の話題"), "news general last");
        check(NewsFeed.parseRss(null).isEmpty(), "news null rss empty");
        check(NewsFeed.DISASTER_KEYWORDS.contains("線状降水帯"), "news keywords include linear rain");
    }

    // ----------------------------------------
    // 🌧️ レーダー
    // ----------------------------------------
    private static void testRadar() {
        Object data = Json.parse("{\"radar\":{\"past\":[{\"time\":100,\"path\":\"/v2/a\"},{\"time\":200,\"path\":\"/v2/b\"}]}}");
        Radar.RainFrame f = Radar.parseRainFrame(data);
        check(f!= null && f.path().equals("/v2/b") && f.time() == 200, "radar latest frame");
        check(Radar.parseRainFrame(Json.parse("{}")) == null, "radar no frame null");
        Object jma = Json.parse("[{\"basetime\":\"20261128000000\",\"validtime\":\"20261128000500\"}]");
        Radar.JmaFrame jf = Radar.parseJmaFrame(jma);
        check(jf!= null && jf.baseTime().equals("20261128000000"), "jma frame parsed");
        check(jf.epochSec() > 0, "jma frame epoch");
        check(Radar.jmaTileUrl(jf, 8, 10, 20).equals(
        "https://www.jma.go.jp/bosai/jmatile/data/nowc/20261128000000/none/20261128000500/surf/hrpns/8/10/20.png"), "jma tile url");
        check(Radar.ageMinutes(1000, 1000 + 5 * 60) == 5, "radar age minutes");
        check(Radar.ageMinutes(2000, 1000) == 0, "radar age future clamped");
        check(Radar.ageText(1000, 1000 + 5 * 60).equals("約5分前の観測"), "radar age text");
        contains(Radar.ageText(1000, 1000 + 30 * 60), "古い情報です", "radar age stale note");
        int[] xy = Radar.tileXY(36.3219, 139.0023, 8);
        check(xy[0] == 226 && xy[1] == 100, "tile XY takasaki z8");
    }
}
