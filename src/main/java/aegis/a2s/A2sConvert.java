package aegis.a2s;

import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Location;
import aegis.core.Severity;
import aegis.parse.EewParser;
import aegis.parse.QuakeParser;
import aegis.parse.TsunamiParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ========================================
// 🔄 A2sConvert — A2S イベント → 内部形式 → Event の変換
// ========================================
// 📌 変換は A2S イベントを内部共通形式の item へ戻し、パーサ
// （QuakeParser / EewParser / TsunamiParser）に通すことで行う。同一視キー
// （地震 = 主報の電文ID、EEW = 発生時刻＋震央座標丸め、津波 = 電文ID）と
// 本文・マージ規則は直結時代と同一に保ち、Engine の重複排除がそのまま効く
// ⚠️ 警報・注意報と台風は item 形式を経由せず、ここで直接 Event 化する
// （警報はコードごとに1イベント、台風は台風ごとに1イベント）
public final class A2sConvert {
    private A2sConvert() {}

    // ----------------------------------------
    // 🔍 A2S イベントの最小検証
    // ----------------------------------------
    // 📌 必須フィールドの欠けたデータを変換に回すと、変換の失敗なのか上流の
    // 差異なのか区別できなくなるため、形が崩れているものはここで弾く
    public static boolean isA2sEvent(Map<String, Object> e) {
        if (e == null || e.isEmpty()) return false;
        String key = Json.str(e.get("key"));
        if (key.isEmpty()) return false;
        return switch (Json.str(e.get("kind"))) {
            case "quake" -> e.get("time") instanceof String
                    && e.get("hypocenter") instanceof String
                    && e.get("maxScale") instanceof Number
                    && e.get("points") instanceof List
                    && e.get("prefScales") instanceof List;
            case "eew" -> e.get("baseKey") instanceof String
                    && e.get("originTime") instanceof String
                    && e.get("estScale") instanceof Number
                    && e.get("isWarn") instanceof Boolean
                    && e.get("cancel") instanceof Boolean;
            case "tsunami" -> e.get("grade") instanceof String
                    && e.get("areas") instanceof List;
            case "warning" -> e.get("officeCode") instanceof String
                    && e.get("areaCode") instanceof String
                    && e.get("warnings") instanceof List
                    && e.get("maxSeverity") instanceof Number;
            case "typhoon" -> e.get("lines") instanceof List;
            default -> false;
        };
    }

    // 📝 数値は内部形式の流儀に合わせて Double で載せる。null（不明）の項目は
    // キー自体を載せないことで、既存パーサの「欠落 = 不明」処理に乗せる
    private static void putNum(Map<String, Object> m, String key, Object v) {
        if (v instanceof Number n) m.put(key, n.doubleValue());
    }

    // ----------------------------------------
    // 🌏 地震: A2S の quake を内部形式の item へ戻す
    // ----------------------------------------
    // 📌 eventId は主報の電文ID（telegramId）になる。国外地震の発生時刻の原表記
    // （originTime）と電文コメント原文（tsunamiText）は A2S の追加フィールドで、
    // item に載せてパーサ側で本文へ含める
    public static Map<String, Object> quakeToItem(Map<String, Object> e) {
        Map<String, Object> hypo = new LinkedHashMap<>();
        hypo.put("name", Json.str(e.get("hypocenter")));
        putNum(hypo, "magnitude", e.get("magnitude"));
        putNum(hypo, "depth", e.get("depthKm"));
        putNum(hypo, "latitude", e.get("lat"));
        putNum(hypo, "longitude", e.get("lon"));
        Map<String, Object> eq = new LinkedHashMap<>();
        eq.put("time", Json.str(e.get("time")));
        putNum(eq, "maxScale", e.get("maxScale"));
        eq.put("domesticTsunami", Json.str(e.get("tsunamiKind"), "None"));
        eq.put("hypocenter", hypo);
        Map<String, Object> issue = new LinkedHashMap<>();
        String announced = Json.str(e.get("announcedTime"), Json.str(e.get("time")));
        issue.put("time", announced);
        issue.put("type", Json.str(e.get("issueType")));
        issue.put("correct", Json.str(e.get("issueCorrect")));
        List<Object> points = new ArrayList<>();
        for (Object pObj : Json.arr(e.get("points"))) {
            Map<String, Object> p = Json.obj(pObj);
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("pref", Json.str(p.get("pref")));
            pt.put("addr", Json.str(p.get("name")));
            putNum(pt, "scale", p.get("scale"));
            pt.put("isArea", Json.bool(p.get("isArea")));
            points.add(pt);
        }
        // 🌏 震度速報段階の県別最大震度: 地域点（isArea=true）が points に無い場合は
        // prefScales から地域点として補う（県別最大震度表示の材料）
        if (points.stream().noneMatch(p -> Json.bool(Json.obj(p).get("isArea")))) {
            for (Object sObj : Json.arr(e.get("prefScales"))) {
                Map<String, Object> s = Json.obj(sObj);
                String pref = Json.str(s.get("pref"));
                if (pref.isEmpty()) continue;
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("pref", pref);
                pt.put("addr", pref);
                putNum(pt, "scale", s.get("scale"));
                pt.put("isArea", true);
                points.add(pt);
            }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", Json.str(e.get("telegramId")));
        item.put("time", announced);
        item.put("issue", issue);
        item.put("earthquake", eq);
        item.put("points", points);
        String originTime = Json.str(e.get("originTime"));
        if (!originTime.isEmpty()) item.put("originTime", originTime);
        String tsunamiText = Json.str(e.get("tsunamiText"));
        if (!tsunamiText.isEmpty()) item.put("tsunamiText", tsunamiText);
        return item;
    }

    // ----------------------------------------
    // 🚨 EEW: A2S の eew を内部共通形式の item へ戻す
    // ----------------------------------------
    // 📌 地域別予想震度（areaScales）と到達予想（areaArrivals）は都道府県で
    // 突き合わせて areas へ統合する（片方しかない地域も落とさない）
    public static Map<String, Object> eewToItem(Map<String, Object> e) {
        Map<String, Object> hypo = new LinkedHashMap<>();
        hypo.put("name", Json.str(e.get("hypocenter")));
        putNum(hypo, "magnitude", e.get("estMagnitude"));
        putNum(hypo, "depth", e.get("depthKm"));
        putNum(hypo, "latitude", e.get("lat"));
        putNum(hypo, "longitude", e.get("lon"));
        Map<String, Object> eq = new LinkedHashMap<>();
        eq.put("originTime", Json.str(e.get("originTime")));
        putNum(eq, "maxScale", e.get("estScale"));
        eq.put("hypocenter", hypo);
        // 📍 到達予想は都道府県ごとに引けるよう Map 化する
        Map<String, String> arrivalByPref = new LinkedHashMap<>();
        for (Object aObj : Json.arr(e.get("areaArrivals"))) {
            Map<String, Object> a = Json.obj(aObj);
            String pref = Json.str(a.get("pref"));
            String at = Json.str(a.get("arrivalTime"));
            if (!pref.isEmpty() && !at.isEmpty()) arrivalByPref.putIfAbsent(pref, at);
        }
        List<Object> areas = new ArrayList<>();
        java.util.Set<String> scaledPrefs = new java.util.HashSet<>();
        for (Object aObj : Json.arr(e.get("areaScales"))) {
            Map<String, Object> a = Json.obj(aObj);
            String pref = Json.str(a.get("pref"));
            if (pref.isEmpty()) continue;
            Map<String, Object> area = new LinkedHashMap<>();
            area.put("pref", pref);
            area.put("name", Json.str(a.get("name"), pref));
            putNum(area, "scale", a.get("scale"));
            // 📏 上限 scaleTo（99=程度以上）も載せる。地点別予想震度は A2S では
            // 生成されないため、この areaScales から EewParser 側で導出を行う
            putNum(area, "scaleTo", a.get("scaleTo"));
            String at = arrivalByPref.get(pref);
            if (at != null) area.put("arrivalTime", at);
            areas.add(area);
            scaledPrefs.add(pref);
        }
        // 📍 予想震度が無く到達予想だけある地域も areas へ残す（到達予想の表示に使う）
        for (Map.Entry<String, String> en : arrivalByPref.entrySet()) {
            if (scaledPrefs.contains(en.getKey())) continue;
            Map<String, Object> area = new LinkedHashMap<>();
            area.put("pref", en.getKey());
            area.put("name", en.getKey());
            area.put("arrivalTime", en.getValue());
            areas.add(area);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("time", Json.str(e.get("time")));
        item.put("isCancel", Json.bool(e.get("cancel")));
        item.put("isWarn", Json.bool(e.get("isWarn")));
        item.put("isFinal", Json.bool(e.get("isFinal")));
        item.put("isAssumption", Json.bool(e.get("isAssumption")));
        if (e.get("serial") instanceof Number n && n.intValue() > 0) item.put("serial", n.doubleValue());
        String eventId = Json.str(e.get("eventId"));
        if (!eventId.isEmpty()) item.put("eventId", eventId);
        item.put("earthquake", eq);
        item.put("areas", areas);
        return item;
    }

    // ----------------------------------------
    // 🌊 津波: A2S の tsunami を内部形式の item へ戻す
    // ----------------------------------------
    // 📌 同一視キーは電文IDのため、eventId は key の接頭辞を外した電文ID そのもの
    // にする。予想高さはラベルに加え数値（maxHeightValue、m）も maxHeight.value
    // に載せる
    public static Map<String, Object> tsunamiToItem(Map<String, Object> e) {
        List<Object> areas = new ArrayList<>();
        for (Object aObj : Json.arr(e.get("areas"))) {
            Map<String, Object> a = Json.obj(aObj);
            Map<String, Object> area = new LinkedHashMap<>();
            area.put("name", Json.str(a.get("name")));
            area.put("grade", Json.str(a.get("grade")));
            String mh = Json.str(a.get("maxHeightLabel"));
            if (!mh.isEmpty() || a.get("maxHeightValue") instanceof Number) {
                Map<String, Object> maxHeight = new LinkedHashMap<>();
                if (!mh.isEmpty()) maxHeight.put("description", mh);
                putNum(maxHeight, "value", a.get("maxHeightValue"));
                area.put("maxHeight", maxHeight);
            }
            String at = Json.str(a.get("arrivalLabel"));
            String cond = Json.str(a.get("firstHeightCondition"));
            if (!at.isEmpty() || !cond.isEmpty()) {
                Map<String, Object> firstHeight = new LinkedHashMap<>();
                if (!at.isEmpty()) firstHeight.put("arrivalTime", at);
                if (!cond.isEmpty()) firstHeight.put("condition", cond);
                area.put("firstHeight", firstHeight);
            }
            areas.add(area);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        String key = Json.str(e.get("key"));
        item.put("id", key.startsWith("552:") ? key.substring(4) : key);
        item.put("time", Json.str(e.get("time")));
        item.put("cancelled", Json.bool(e.get("cancelled")));
        item.put("areas", areas);
        return item;
    }

    // ----------------------------------------
    // ⚠️ 気象警報・注意報（A2S の warning）
    // ----------------------------------------
    // 📌 A2S の warning は一次細分区域ごとに1件（key = "warning:<officeCode>:<areaCode>"）。
    // officeCode は地点の jmaPrefCode と同じコード体系で、areaName は一次細分区域名
    // （地点の jmaAreaName と同じ表記）のため、この2つで地点へ照合する。区域名が解決
    // できなかった場合（フォールバック表記「区域 <code>」のとき）だけ、その官署の地点
    // 全てを対象にする（安全側の倒し方）。実名が一致しない区域は別区域の警報なので混ぜない。
    // 変換は警報コードごとに1イベントで、eventId も "warn-<officeCode>-<code>-g<世代>"
    // の形式を保つ。世代は WarningTracker で管理する。解除イベント（released=true、
    // warnings=[]）は SSE でのみ届き、通知はせず世代だけを進める

    // ⚠️ 警報コードごとの行動ガイド
    public static String warningGuide(String name) {
        if (name.contains("大雨") || name.contains("洪水")) return "川や崖に近づかない。地下空間を避け、通学ルート・運休情報を確認";
        if (name.contains("暴風") || name.contains("強風")) return "飛来物に注意し、不要な外出を控える。電車の遅延・運休を確認";
        if (name.contains("大雪")) return "路面凍結と交通障害に注意。時間に余裕を持って移動";
        return "";
    }

    // ⚠️ A2S の重要度（特別警報=4 / 警報=3 / 注意報=2）を内部形式へ写す。
    // 重要度が無いエントリはコードと名称から決める
    public static Severity warningSeverity(Map<String, Object> w) {
        if (w.get("severity") instanceof Number n) {
            return switch (n.intValue()) {
                case 4 -> Severity.CRITICAL;
                case 3 -> Severity.HIGH;
                case 2 -> Severity.MEDIUM;
                case 1 -> Severity.LOW;
                default -> Severity.INFO;
            };
        }
        String code = Json.str(w.get("code"));
        String name = Json.str(w.get("name"));
        try {
            int ci = Integer.parseInt(code);
            if (ci >= 32 && ci <= 38) return Severity.CRITICAL;
        } catch (Exception ignored) {}
        return name.endsWith("警報") ? Severity.HIGH : Severity.MEDIUM;
    }

    // ⚠️ A2S の warning 1件（1区域分）を、対象地点ごとのイベントへ変換する
    // （テスト対象）。解除イベントは世代を進めるだけで空を返す
    public static List<Event> warningToEvents(Map<String, Object> e, List<Location> locations) {
        String officeCode = Json.str(e.get("officeCode"));
        String areaCode = Json.str(e.get("areaCode"));
        String areaName = Json.str(e.get("areaName"));
        // 📍 対象地点: 官署コードが一致する地点のうち区域名も一致するもの。
        // 区域名が未解決（空、またはフォールバック表記「区域 <code>」）の場合だけ
        // 官署の地点全て（県内全域）を対象にする
        List<Location> officeLocs = new ArrayList<>();
        List<Location> areaLocs = new ArrayList<>();
        for (Location loc : locations) {
            if (!officeCode.equals(loc.jmaPrefCode())) continue;
            officeLocs.add(loc);
            if (loc.jmaAreaName().isEmpty() || loc.jmaAreaName().equals(areaName)) areaLocs.add(loc);
        }
        boolean areaUnresolved = areaName.isEmpty() || areaName.equals("区域 " + areaCode);
        List<Location> targets = areaLocs.isEmpty() && areaUnresolved ? officeLocs : areaLocs;
        if (targets.isEmpty()) return List.of();
        String locKey = officeCode + "|" + areaCode;
        // 📌 解除イベント: 通知はせず、この区域で有効だったコードの世代を進めるだけにする
        if (Json.bool(e.get("released"))) {
            WarningTracker.advanceGenerations(locKey, java.util.Set.of());
            return List.of();
        }
        // ⚠️ エントリ単位の防御: 解除・発表なしの status が混ざっても発表中として数えない
        List<Map<String, Object>> active = new ArrayList<>();
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        for (Object wObj : Json.arr(e.get("warnings"))) {
            Map<String, Object> w = Json.obj(wObj);
            String code = Json.str(w.get("code"));
            String status = Json.str(w.get("status"));
            if (code.isEmpty()) continue;
            if (status.equals("解除") || status.equals("発表警報・注意報はなし")) continue;
            active.add(w);
            codes.add(code);
        }
        Map<String, Integer> generations = WarningTracker.advanceGenerations(locKey, codes);
        String headline = Json.str(e.get("headline"));
        String reportDate = Json.str(e.get("reportDatetime"), Json.str(e.get("time")));
        List<Event> events = new ArrayList<>();
        for (Location loc : targets) {
            for (Map<String, Object> w : active) {
                String code = Json.str(w.get("code"));
                String name = Json.str(w.get("name"), "その他の警報・注意報（コード" + code + "）");
                Event ev = new Event("weather_warning", "気象 " + name + "（" + loc.name() + "）",
                        (loc.name() + "周辺に" + name + "が発表されています。" + headline).trim());
                ev.severity = warningSeverity(w);
                ev.source = "A.A.S（気象庁 警報・注意報）";
                ev.occurredAt = reportDate;
                ev.actionGuide = warningGuide(name);
                ev.eventId = "warn-" + officeCode + "-" + code + "-g" + generations.getOrDefault(code, 0);
                events.add(ev);
            }
        }
        return events;
    }

    // ----------------------------------------
    // 🌀 台風（A2S の typhoon）
    // ----------------------------------------
    // 📌 A2S の typhoon は台風ごとに1件（key = "typhoon:<番号>"）で、表示行
    // （lines）がそのまま本文になる。eventId は A2S の key をそのまま使うため、
    // 同じ台風の続報では変わらず、Engine の重複排除で再通知されない。代表時刻
    // （time）が空の場合は変換時刻で埋める（A2S 側に日時が無い場合の扱い）
    public static Event typhoonToEvent(Map<String, Object> e) {
        List<String> lines = new ArrayList<>();
        for (Object o : Json.arr(e.get("lines"))) {
            String s = Json.str(o);
            if (!s.isEmpty()) lines.add(s);
        }
        if (lines.isEmpty()) return null;
        Event ev = new Event("typhoon", "🌀 台風情報",
                String.join("\n", lines) + "\n詳細は気象庁の台風情報を確認してください。");
        ev.severity = Severity.HIGH;
        ev.source = "A.A.S（気象庁 台風情報）";
        String time = Json.str(e.get("time"));
        ev.occurredAt = time.isEmpty()
                ? java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
                : time;
        String key = Json.str(e.get("key"));
        ev.eventId = key.isEmpty() ? ev.ensureId() : "a2s-" + key;
        return ev;
    }

    // ----------------------------------------
    // 🔄 A2S イベント1件 → Event 群（入口）
    // ----------------------------------------
    // 📌 検証を通らないもの・変換結果が空のものは空リストを返す（テスト対象）
    public static List<Event> convertEvent(Map<String, Object> e, List<Location> locations) {
        if (!isA2sEvent(e)) return List.of();
        if (locations == null) locations = List.of();
        List<Event> evs = switch (Json.str(e.get("kind"))) {
            case "quake" -> QuakeParser.parse(List.of(quakeToItem(e)), locations);
            case "eew" -> EewParser.parse(List.of(eewToItem(e)), locations);
            case "tsunami" -> TsunamiParser.parse(List.of(tsunamiToItem(e)));
            case "warning" -> warningToEvents(e, locations);
            case "typhoon" -> {
                Event ev = typhoonToEvent(e);
                yield ev == null ? List.of() : List.of(ev);
            }
            default -> List.of();
        };
        // 📡 出典表記は A2S 経由であることが分かる形にする
        for (Event ev : evs) {
            ev.source = switch (ev.kind) {
                case "eew" -> "A.A.S（緊急地震速報（" + (ev.isWarn ? "警報" : "予報") + "））";
                case "tsunami" -> "A.A.S（気象庁 津波情報）";
                case "weather_warning" -> "A.A.S（気象庁 警報・注意報）";
                case "typhoon" -> "A.A.S（気象庁 台風情報）";
                default -> "A.A.S（P2P地震情報・気象庁）";
            };
        }
        return evs;
    }
}
