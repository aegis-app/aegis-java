package aegis.parse;

import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Location;
import aegis.core.Scale;
import aegis.core.Severity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ========================================
// 🚨 EewParser — EEW（緊急地震速報）の解析・地点別導出・到達予想
// ========================================
// 📌 入力は A2S 変換（aegis.a2s.A2sConvert）が復元した内部共通形式の
// item のみ。本クラスは取得を行わず、解析と地点別導出（予想震度・
// 到達予想）を担う
public final class EewParser {
private EewParser() {}

// ----------------------------------------
// 🔍 フィールド読み取り（内部共通形式）
// ----------------------------------------
public static boolean cancelled(Map<String, Object> item) {
return Json.bool(item.get("isCancel"));
}

public static boolean isWarn(Map<String, Object> item) {
return Json.bool(item.get("isWarn"));
}

public static boolean isFinal(Map<String, Object> item) {
return Json.bool(item.get("isFinal"));
}

// 🌏 区域の予想震度（下限）。上限は scaleTo（99 は「〜程度以上」）で別に読む
public static int areaScale(Map<String, Object> area) {
return (int) Json.dbl(area.get("scale"), -1);
}

// 🌏 推定最大震度: earthquake.maxScale。欠けた場合は areas の scale の最大値で代用する
public static int estScale(Map<String, Object> item, Map<String, Object> eq) {
int ms = (int) Json.dbl(eq.get("maxScale"), -1);
if (ms > 0) return ms;
int best = -1;
for (Object aObj: Json.arr(item.get("areas"))) {
best = Math.max(best, areaScale(Json.obj(aObj)));
}
return best;
}

public static int serial(Map<String, Object> item) {
return (int) Json.dbl(item.get("serial"), -1);
}

public static String reportEventId(Map<String, Object> item) {
return Json.str(item.get("eventId"));
}

public static boolean isAssumption(Map<String, Object> item) {
return Json.bool(item.get("isAssumption"));
}

// ----------------------------------------
// 🔄 item 群 → Event 群
// ----------------------------------------
public static List<Event> parse(Object data) {
return parse(data, List.of());
}

public static List<Event> parse(Object data, List<Location> locations) {
List<Event> events = new ArrayList<>();
for (Object itemObj: Json.arr(data)) {
Map<String, Object> item = Json.obj(itemObj);
if (!(item.get("earthquake") instanceof Map)) continue;
Map<String, Object> eq = Json.obj(item.get("earthquake"));
Map<String, Object> hypo = Json.obj(eq.get("hypocenter"));
String hypoName = Json.str(hypo.get("name"), "不明");
boolean cancel = cancelled(item);
boolean warn = isWarn(item);
boolean fin = isFinal(item);
int serial = serial(item);
boolean assumption = isAssumption(item);
int estScale = estScale(item, eq);
double estMag = Json.dbl(hypo.get("magnitude"), -1);
// 🚨 EEW の震源・マグニチュード・震度はすべて推定値であり、確定値と
// 混同しないよう本文の先頭から「推定情報」と明示する
StringBuilder body = new StringBuilder();
double estDepth = Json.dbl(hypo.get("depth"), -1);
String estDepthStr = estDepth == 0? "推定深さごく浅い"
: (estDepth < 0? "推定深さ不明": "推定深さ約" + (int) estDepth + "km");
body.append("推定震源: ").append(hypoName)
.append(" / ").append(estDepthStr)
.append(" / 推定最大震度 ").append(estScale > 0? Scale.toStr(estScale): "不明")
.append(" / ").append(estMag < 0? "推定M不明": "推定M" + estMag)
.append(" / 発表: ").append(Json.str(item.get("time")));
if (serial > 0) body.append(" / 第").append(serial).append("報");
if (fin &&!cancel) body.append(" / 最終報");
if (assumption) body.append(" / 仮定震源要素による推定");
java.util.LinkedHashSet<String> prefs = new java.util.LinkedHashSet<>();
for (Object aObj: Json.arr(item.get("areas"))) {
if (prefs.size() >= 6) break;
String p = Json.str(Json.obj(aObj).get("pref"));
if (!p.isEmpty()) prefs.add(p);
}
if (!prefs.isEmpty()) body.append(" / 対象: ").append(String.join("、", prefs));
// 🌏 地域別予想震度（推定）: areas の区域名と予想震度を震度上位から列挙する
String areaScaleNote = areaScaleNote(item);
if (!areaScaleNote.isEmpty()) body.append(" / ").append(areaScaleNote);
// 🌏 登録地点の予想震度と主要動到達予想（生成時点の静的テキスト）。
// いずれも取消報には付けない
if (!cancel) {
for (String note: scaleNotes(item, locations)) body.append(" / ").append(note);
for (String note: arrivalNotes(item, eq, locations, LocalDateTime.now())) body.append(" / ").append(note);
}
// 📡 タイトルには予報／警報の区別を出す（isWarn=true が警報）。取消は（取消）
Event ev = new Event("eew",
cancel? "緊急地震速報（取消） " + hypoName
: (warn? "緊急地震速報（警報） ": "緊急地震速報（予報） ") + hypoName,
body.toString());
// 🚨 重要度は推定最大震度から決める（小規模な EEW まで CRITICAL にしない）。
// 取消は「速報を取り消した」という重要情報なので、既定の通知しきい値
// （MEDIUM）を通過する重要度にする（LOW だとフィルタで落ちて取消が届かない）
ev.severity = cancel? Severity.MEDIUM: Scale.toSeverity(estScale, estMag);
if (!cancel && estScale < 0) ev.severity = Severity.HIGH;
ev.source = warn? "A.A.S（緊急地震速報（警報））": "A.A.S（緊急地震速報（予報））";
ev.isWarn = warn;
ev.isFinal = fin;
ev.serial = serial;
ev.reportEventId = reportEventId(item);
ev.isAssumption = assumption;
// 🌏 続報は item の id が変わるため、発生時刻＋震源で同一視して重複通知を防ぐ。
// 取消報は通常報と同一視すると重複排除で消えてしまうため別イベントとして扱う
String origin = Json.str(eq.get("originTime"), Json.str(item.get("time")));
// 🌏 震央座標がある場合は震源名を使わず座標キーで同一視する（表記変更に強い）
String coordKey = coordKey(hypo);
ev.eventId = "eew-" + origin + (coordKey.isEmpty()? "-" + hypoName: coordKey) + (cancel? "-cancel": "");
ev.maxScale = estScale; // 推定最大震度。Engine の上方修正判定に使う
ev.occurredAt = Json.str(item.get("time"));
ev.actionGuide = cancel? "": actionGuide(estScale, warn);
ev.ensureId();
events.add(ev);
}
return events;
}

// 🚨 EEW の行動ガイド文言の出し分け: 推定最大震度が小さい予報（震度3程度）
// でも「強い揺れに備え移動する」文言を出すと過剰になるため、震度で3段階に
// 分ける。震度不明は、警報なら強い揺れ前提で強い文言、予報なら注意喚起のみ
public static String actionGuide(int estScale, boolean warn) {
if (estScale >= 45) return "強い揺れに備え、頭を守り安全な場所へ移動する";
if (estScale >= 40) return "やや強い揺れに備え、落下物に注意して頭を守る";
if (estScale > 0) return "揺れに注意する";
return warn? "強い揺れに備え、頭を守り安全な場所へ移動する": "揺れに注意する";
}

// 🚨 同一視キーの震央座標サフィックス: 震源名の表記変更でキーが崩れないよう、
// 震央座標を 0.1 度単位に丸めた値を併用する。座標が無い・範囲外の場合は
// 空文字（従来キーと同一）にする
public static String coordKey(Map<String, Object> hypo) {
double la = Json.dbl(hypo.get("latitude"), -999);
double lo = Json.dbl(hypo.get("longitude"), -999);
if (la < -90 || la > 90 || lo < -180 || lo > 180) return "";
return "@" + Math.round(la * 10) + "," + Math.round(lo * 10); // 例: "@363,1390"
}

// ----------------------------------------
// 🗾 都道府県名表（地点の jmaPrefCode と EEW の area.pref の照合用）
// ----------------------------------------
public static final Map<String, String> PREF_NAMES = Map.ofEntries(
Map.entry("010000", "北海道"), Map.entry("020000", "青森県"), Map.entry("030000", "岩手県"),
Map.entry("040000", "宮城県"), Map.entry("050000", "秋田県"), Map.entry("060000", "山形県"),
Map.entry("070000", "福島県"), Map.entry("080000", "茨城県"), Map.entry("090000", "栃木県"),
Map.entry("100000", "群馬県"), Map.entry("110000", "埼玉県"), Map.entry("120000", "千葉県"),
Map.entry("130000", "東京都"), Map.entry("140000", "神奈川県"), Map.entry("150000", "新潟県"),
Map.entry("160000", "富山県"), Map.entry("170000", "石川県"), Map.entry("180000", "福井県"),
Map.entry("190000", "山梨県"), Map.entry("200000", "長野県"), Map.entry("210000", "岐阜県"),
Map.entry("220000", "静岡県"), Map.entry("230000", "愛知県"), Map.entry("240000", "三重県"),
Map.entry("250000", "滋賀県"), Map.entry("260000", "京都府"), Map.entry("270000", "大阪府"),
Map.entry("280000", "兵庫県"), Map.entry("290000", "奈良県"), Map.entry("300000", "和歌山県"),
Map.entry("310000", "鳥取県"), Map.entry("320000", "島根県"), Map.entry("330000", "岡山県"),
Map.entry("340000", "広島県"), Map.entry("350000", "山口県"), Map.entry("360000", "徳島県"),
Map.entry("370000", "香川県"), Map.entry("380000", "愛媛県"), Map.entry("390000", "高知県"),
Map.entry("400000", "福岡県"), Map.entry("410000", "佐賀県"), Map.entry("420000", "長崎県"),
Map.entry("430000", "熊本県"), Map.entry("440000", "大分県"), Map.entry("450000", "宮崎県"),
Map.entry("460000", "鹿児島県"), Map.entry("470000", "沖縄県"), Map.entry("471000", "沖縄県"));

public static String prefNameForCode(String code) {
if (code == null) return "";
String n = PREF_NAMES.getOrDefault(code, "");
if (!n.isEmpty()) return n;
if (code.startsWith("01")) return "北海道"; // 北海道は細分コード（011000等）もある
return "";
}

// ----------------------------------------
// 🕐 時刻・距離のユーティリティ
// ----------------------------------------
public static LocalDateTime parseEventTime(String s) {
if (s == null || s.length() < 19) return null;
try {
return LocalDateTime.parse(s.substring(0, 19), DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
} catch (Exception e) {
return null;
}
}

public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
double r = 6371.0;
double dLat = Math.toRadians(lat2 - lat1);
double dLon = Math.toRadians(lon2 - lon1);
double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
return 2 * r * Math.asin(Math.sqrt(a));
}

// ----------------------------------------
// 📍 地点別ノート（予想震度・到達予想）
// ----------------------------------------
// 📍 登録地点ごとの主要動到達予想を作る。area に arrivalTime があればそれを
// 使い、なければ震源距離と深さから S波 3.5km/s で推定する（「推定」と明記）
public static List<String> arrivalNotes(Map<String, Object> item, Map<String, Object> eq,
List<Location> locations, LocalDateTime now) {
List<String> notes = new ArrayList<>();
if (locations == null || locations.isEmpty()) return notes;
Map<String, Object> hypo = Json.obj(eq.get("hypocenter"));
double hLat = Json.dbl(hypo.get("latitude"), 0);
double hLon = Json.dbl(hypo.get("longitude"), 0);
double depth = Json.dbl(hypo.get("depth"), -1);
if (depth < 0) depth = 0; // 深さ不明（-1）は 0km として推定する
LocalDateTime origin = parseEventTime(Json.str(eq.get("originTime"), Json.str(item.get("time"))));
List<Object> areas = Json.arr(item.get("areas"));
for (Location loc: locations) {
String prefName = prefNameForCode(loc.jmaPrefCode());
if (prefName.isEmpty()) continue;
String shortPref = prefName.replaceAll("[県府都]$", "");
Map<String, Object> matched = null;
for (Object aObj: areas) {
Map<String, Object> a = Json.obj(aObj);
String ap = Json.str(a.get("pref"));
String an = Json.str(a.get("name"));
if (ap.equals(prefName) || (!an.isEmpty() && (an.startsWith(prefName) || an.startsWith(shortPref)))) {
matched = a;
break;
}
}
if (matched == null) continue;
LocalDateTime arrival = null;
boolean estimated = false;
String at = Json.str(matched.get("arrivalTime"));
if (!at.isEmpty()) {
arrival = parseEventTime(at);
// 🔄 非空でもパースできない値の場合は null のままにし、到達予想を捨てず S波推定にフォールバックする
}
if (arrival == null && origin!= null && (hLat!= 0 || hLon!= 0)) {
double surface = haversineKm(loc.lat(), loc.lon(), hLat, hLon);
double slant = Math.sqrt(surface * surface + depth * depth);
arrival = origin.plusSeconds(Math.round(slant / 3.5));
estimated = true;
}
if (arrival == null) continue;
long remain = java.time.Duration.between(now, arrival).getSeconds();
StringBuilder note = new StringBuilder();
note.append(loc.name()).append("の主要動到達予想 ")
.append(arrival.format(DateTimeFormatter.ofPattern("H:mm"))).append("（");
if (estimated) note.append("推定・");
if (remain > 0) note.append("あと約").append(remain).append("秒");
else note.append("到達した可能性が高いです");
note.append("）");
notes.add(note.toString());
}
return notes;
}

// 🌏 登録地点ごとの予想震度ノート。areas のうち地点の都道府県に一致するものを
// 探し、細分区域名（jmaAreaName）を含む区域を優先する。「高崎: 予想震度5弱」の形式
public static List<String> scaleNotes(Map<String, Object> item, List<Location> locations) {
List<String> notes = new ArrayList<>();
if (locations == null || locations.isEmpty()) return notes;
List<Object> areas = Json.arr(item.get("areas"));
for (Location loc: locations) {
String prefName = prefNameForCode(loc.jmaPrefCode());
if (prefName.isEmpty()) continue;
String shortPref = prefName.replaceAll("[県府都]$", "");
Map<String, Object> matched = null;
Map<String, Object> prefOnly = null;
for (Object aObj: areas) {
Map<String, Object> a = Json.obj(aObj);
String ap = Json.str(a.get("pref"));
String an = Json.str(a.get("name"));
boolean prefHit = ap.equals(prefName) || (!an.isEmpty() && (an.startsWith(prefName) || an.startsWith(shortPref)));
if (!prefHit) continue;
if (prefOnly == null) prefOnly = a;
if (!loc.jmaAreaName().isEmpty() && an.contains(loc.jmaAreaName())) { matched = a; break;}
}
if (matched == null) matched = prefOnly;
if (matched == null) continue;
int sc = areaScale(matched);
if (sc <= 0) continue;
notes.add(loc.name() + ": 予想震度" + Scale.toStr(sc) + "（推定）");
}
return notes;
}

// 🚨 地域別予想震度ノート（テスト対象）。areas の区域名と予想震度を震度の高い順に
// 最大6区域まで列挙し、「推定」であることを明示する。上限 scaleTo が 99 の区域は
// 「〜程度以上」を併記する。予想震度のある区域が無ければ "" を返す
public static String areaScaleNote(Map<String, Object> item) {
// 📝 区域名 → {下限, 上限}。同名区域は下限の最大値を残し、その上限を併記する
Map<String, int[]> byName = new LinkedHashMap<>();
for (Object aObj: Json.arr(item.get("areas"))) {
Map<String, Object> a = Json.obj(aObj);
String name = Json.str(a.get("name"));
int sc = areaScale(a);
if (name.isEmpty() || sc <= 0) continue;
int to = (int) Json.dbl(a.get("scaleTo"), -1);
int[] cur = byName.get(name);
if (cur == null || sc > cur[0]) byName.put(name, new int[]{sc, to});
}
if (byName.isEmpty()) return "";
List<Map.Entry<String, int[]>> sorted = new ArrayList<>(byName.entrySet());
sorted.sort((a, b) -> a.getValue()[0] == b.getValue()[0]
? a.getKey().compareTo(b.getKey())
: Integer.compare(b.getValue()[0], a.getValue()[0]));
StringBuilder sb = new StringBuilder("地域別予想震度（推定）: ");
int shown = Math.min(6, sorted.size());
for (int i = 0; i < shown; i++) {
if (i > 0) sb.append("、");
sb.append(sorted.get(i).getKey()).append(" ").append(Scale.toStr(sorted.get(i).getValue()[0]));
if (sorted.get(i).getValue()[1] == 99) sb.append("程度以上");
}
if (sorted.size() > shown) sb.append(" ほか").append(sorted.size() - shown).append("区域");
return sb.toString();
}
}
