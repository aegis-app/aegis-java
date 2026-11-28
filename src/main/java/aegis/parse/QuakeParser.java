package aegis.parse;

import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Location;
import aegis.core.Scale;
import aegis.core.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ========================================
// 🌏 QuakeParser — 地震情報の解析・マージ
// ========================================
// 📌 入力は A2S 変換（aegis.a2s.A2sConvert）が復元した内部形式の item
// （旧 P2P 551 電文と同形）のみ。本クラスは取得を行わず、電文群の
// マージと Event への整形だけを担う
// 📝 マージ仕様: 気象庁は1つの地震を段階的に発表する（震度速報→震源・
// 震度に関する情報→詳細情報）ため、A2S の保持イベントには同一地震の
// 電文が複数並ぶ。発生時刻＋震源名でグルーピングし、震源名の表記揺れ
// 対策として震央座標の近接（40km以内）を補助キーにして最新報へ統合する。
// 統合時は issue の種別を読み、報種別ラベル（速報／確定／訂正）と
// 発表時刻・種別を本文に含める
public final class QuakeParser {
    private QuakeParser() {}

    // 📝 報種別の優先度（大きいほど完全な報）
    public static int typeRank(String issueType) {
        return switch (issueType == null ? "" : issueType) {
            case "DetailScale" -> 4;
            case "ScaleAndDestination", "Foreign" -> 3;
            case "Destination" -> 2;
            case "ScalePrompt" -> 1;
            default -> 0;
        };
    }

    // 📝 issue.type の日本語名
    public static String typeName(String issueType) {
        return switch (issueType == null ? "" : issueType) {
            case "ScalePrompt" -> "震度速報";
            case "Destination" -> "震源に関する情報";
            case "ScaleAndDestination" -> "震源・震度に関する情報";
            case "DetailScale" -> "震度詳細情報";
            case "Foreign" -> "海外地震情報";
            default -> "地震情報";
        };
    }

    // 📝 報種別ラベル: 訂正報（issue.correct が None 以外）を最優先で「訂正」、
    // 詳細情報は「確定」、震度速報は「速報」、それ以外は種別名そのもの
    public static String issueLabel(String issueType, String correct) {
        if (correct != null && !correct.isEmpty() && !correct.equals("None")) return "訂正";
        return switch (issueType == null ? "" : issueType) {
            case "DetailScale" -> "確定";
            case "ScalePrompt" -> "速報";
            default -> typeName(issueType);
        };
    }

    // 📝 電文1件分の正規化レコード（マージ前の素材）。originTime は国外地震の
    // 発生時刻の原表記、tsunamiText は電文コメント原文（いずれも A2S の
    // 追加フィールド由来で、国内電文では空）
    private record QuakeRec(String id, String itemTime, String issueTime, String issueType, String correct,
                            int typeRank, String eqTime, String place, double mag, int depth,
                            int maxScale, String tsunamiDom, List<Object> points,
                            double lat, double lon, boolean hasCoord,
                            String originTime, String tsunamiText) {}

    private static QuakeRec parseRec(Map<String, Object> item) {
        Map<String, Object> eq = Json.obj(item.get("earthquake"));
        if (eq.isEmpty()) return null;
        Map<String, Object> hypo = Json.obj(eq.get("hypocenter"));
        Map<String, Object> issue = Json.obj(item.get("issue"));
        String issueType = Json.str(issue.get("type"));
        double latRaw = Json.dbl(hypo.get("latitude"), -999);
        double lonRaw = Json.dbl(hypo.get("longitude"), -999);
        // 🌏 震源未確定のとき緯度経度には範囲外のセンチネルが入る。範囲外は「座標なし」として扱う
        boolean hasCoord = latRaw >= -90 && latRaw <= 90 && lonRaw >= -180 && lonRaw <= 180;
        String issueTime = Json.str(issue.get("time"), Json.str(item.get("time")));
        return new QuakeRec(
                Json.str(item.get("id")),
                Json.str(item.get("time")),
                issueTime,
                issueType,
                Json.str(issue.get("correct")),
                typeRank(issueType),
                Json.str(eq.get("time")),
                Json.str(hypo.get("name")).trim(),
                Json.dbl(hypo.get("magnitude"), -1),
                (int) Json.dbl(hypo.get("depth"), -999),
                (int) Json.dbl(eq.get("maxScale"), -1),
                Json.str(eq.get("domesticTsunami")),
                Json.arr(item.get("points")),
                hasCoord ? latRaw : 0, hasCoord ? lonRaw : 0, hasCoord,
                Json.str(item.get("originTime")),
                Json.str(item.get("tsunamiText")));
    }

    // 🌏 同一地震かどうかの判定: 発生時刻が一致し、かつ（震源名が一致するか、
    // どちらか震源名なしで時刻が一致するか、震央座標が 40km 以内）
    static boolean sameQuake(QuakeRec a, QuakeRec b) {
        if (a.eqTime().isEmpty() || !a.eqTime().equals(b.eqTime())) return false;
        if (!a.place().isEmpty() && a.place().equals(b.place())) return true;
        if (a.place().isEmpty() || b.place().isEmpty()) return true;
        return a.hasCoord() && b.hasCoord()
                && EewParser.haversineKm(a.lat(), a.lon(), b.lat(), b.lon()) <= 40.0;
    }

    public static List<Event> parse(Object data) {
        return parse(data, List.of());
    }

    public static List<Event> parse(Object data, List<Location> locations) {
        if (locations == null) locations = List.of();
        // ⌨️ グルーピング（入力順 = 新しい順を維持）
        List<List<QuakeRec>> groups = new ArrayList<>();
        for (Object itemObj : Json.arr(data)) {
            QuakeRec rec = parseRec(Json.obj(itemObj));
            if (rec == null) continue;
            List<QuakeRec> target = null;
            for (List<QuakeRec> g : groups) {
                // 🌏 震源名ありグループ同士は名前一致か座標近接でのみ寄せる。
                // 名前なし電文は同じ発生時刻のグループが1つだけのとき寄せる（曖昧なら独立）
                boolean nameLess = rec.place().isEmpty();
                if (nameLess) {
                    long sameTime = groups.stream().filter(x -> !x.isEmpty() && x.get(0).eqTime().equals(rec.eqTime())).count();
                    if (sameTime == 1 && sameQuake(g.get(0), rec)) { target = g; break; }
                } else if (sameQuake(g.get(0), rec)) { target = g; break; }
            }
            if (target == null) {
                target = new ArrayList<>();
                groups.add(target);
            }
            target.add(rec);
        }
        List<Event> events = new ArrayList<>();
        for (List<QuakeRec> g : groups) events.add(mergeGroup(g, locations));
        // 🕐 発表時刻の新しい順に並べる（文字列は "yyyy/MM/dd HH:mm:ss" で辞書順 = 時系列順）
        events.sort((a, b) -> b.occurredAt.compareTo(a.occurredAt));
        return events;
    }

    // 🕐 電文群を1件に統合する: 優先度（種別→発表時刻）の高い報を主にし、欠けた項目は他の報から補う
    private static Event mergeGroup(List<QuakeRec> records, List<Location> locations) {
        List<QuakeRec> sorted = new ArrayList<>(records);
        sorted.sort((a, b) -> a.typeRank() != b.typeRank()
                ? Integer.compare(b.typeRank(), a.typeRank())
                : b.issueTime().compareTo(a.issueTime()));
        QuakeRec primary = sorted.get(0);
        String place = "";
        double mag = -1;
        int depthVal = -999;
        int maxScale = -1;
        String tsunamiDom = "";
        List<Object> points = List.of();
        double lat = 0, lon = 0;
        boolean hasCoord = false;
        String originTime = "";
        String tsunamiText = "";
        for (QuakeRec r : sorted) {
            if (originTime.isEmpty() && !r.originTime().isEmpty()) originTime = r.originTime();
            if (tsunamiText.isEmpty() && !r.tsunamiText().isEmpty()) tsunamiText = r.tsunamiText();
            if (place.isEmpty() && !r.place().isEmpty()) place = r.place();
            if (mag < 0 && r.mag() >= 0) mag = r.mag();
            // 🌏 深さは判明値（>= 0）のある報からのみ採用する。-1 は震源未確定
            // （震度速報）のセンチネル、欠落は内部値 -999 で、いずれも「不明」であり
            // 判明値のある報を上書き・先行させない。0 は「ごく浅い」の正当な判明値
            if (depthVal < 0 && r.depth() >= 0) depthVal = r.depth();
            if (maxScale < 0 && r.maxScale() >= 0) maxScale = r.maxScale();
            // 🌊 津波は確定した値（心配なし等）を優先し、確認中しかなければそのまま残す
            if ((tsunamiDom.isEmpty() || tsunamiDom.equals("Checking") || tsunamiDom.equals("Unknown"))
                    && !r.tsunamiDom().isEmpty()) tsunamiDom = r.tsunamiDom();
            if (points.isEmpty() && !r.points().isEmpty()) points = r.points();
            if (!hasCoord && r.hasCoord()) { lat = r.lat(); lon = r.lon(); hasCoord = true; }
        }
        if (place.isEmpty()) place = "不明";
        if (maxScale < 0) maxScale = primary.maxScale();
        // 🌦️ 深さの意味（気象庁XML・実データで確認）: 確定報の 0 は「ごく浅い」
        // （気象庁XMLはごく浅いを 0 と記載する）ため「深さ約0km」とは表示しない。
        // -1 は震源未確定のセンチネルであり「ごく浅い」と混同せず「不明」とする
        String magStr = mag < 0 ? "M不明" : "M" + mag;
        String depthStr = depthVal == 0 ? "深さごく浅い" : (depthVal < 0 ? "深さ不明" : "深さ約" + depthVal + "km");
        // 📝 「心配なし」は明示的な None のときだけ。欠落・未知の値（Checking 等）は
        // 安全側に倒して「確認中」とする
        String tsunamiJp = switch (tsunamiDom) {
            case "Forecast" -> "若干の海面変動の可能性あり";
            case "Advisory" -> "津波注意報レベル";
            case "Warning" -> "津波警報レベル";
            case "None" -> "津波の心配なし";
            default -> "津波の有無は確認中";
        };
        String tsunamiNote = " / " + tsunamiJp;
        // 🌏 国外地震の付帯情報: 発生時刻の原表記と電文コメント原文を落とさない
        if (!originTime.isEmpty()) tsunamiNote += " / 発生（原表記）: " + originTime;
        if (!tsunamiText.isEmpty()) tsunamiNote += " / 津波コメント: " + tsunamiText;
        // 🌏 最大震度を観測した地点（points から）。登録地点の市区町村と一致する観測点を
        // 先頭一致より優先する
        String topNote = "";
        {
            List<String> tops = new ArrayList<>();
            for (Object pObj : points) {
                Map<String, Object> p = Json.obj(pObj);
                if ((int) Json.dbl(p.get("scale"), 0) == maxScale) {
                    String addr = Json.str(p.get("addr"), Json.str(p.get("pref")));
                    if (!addr.isEmpty()) tops.add(addr);
                }
            }
            if (!tops.isEmpty()) {
                String first = tops.get(0);
                for (Location loc : locations) {
                    String city = loc.city();
                    if (city == null || city.isEmpty()) continue;
                    for (String t : tops) {
                        if (cityMatchesAddr(city, t)) { first = t; break; }
                    }
                }
                topNote = " / 最大震度観測: " + first + (tops.size() > 1 ? " ほか" + (tops.size() - 1) + "地点" : "");
            }
        }
        // 🌏 震源マップへのリンク
        String mapNote = "";
        if (hasCoord && (lat != 0 || lon != 0)) {
            mapNote = " / 地図: https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=8/" + lat + "/" + lon;
        }
        Severity sev = Scale.toSeverity(maxScale, mag);
        String guide = "";
        if (sev.level >= Severity.CRITICAL.level) guide = "頭を守り安全な場所へ。火の元を確認し、津波情報に注意。エレベーターは使わない";
        else if (sev.level >= Severity.HIGH.level) guide = "落下物に注意し、続報と津波の有無を確認する";
        // 🌏 観測点情報（実測）: points から観測点の総数・最大震度観測点・主要観測点を
        // 実測値として列挙する。観測点別の値が無い報（震度速報のみ等）では無理に出さず
        // 「観測点情報なし」と明示する
        String obsNote = " / " + observationPointsNote(points);
        // 🌏 登録地点の市区町村と一致する観測点の震度を「○○市の震度」として優先表示する。
        // これは観測点の実測そのものなので「実測」と明示し、根拠の観測点名とその実測震度を
        // 併記する。地域震度（isArea）は市区町村の観測ではないため除外する
        String cityNote = "";
        java.util.Set<String> measuredLocs = new java.util.HashSet<>();
        {
            StringBuilder csb = new StringBuilder();
            for (Location loc : locations) {
                String city = loc.city();
                if (city == null || city.isEmpty()) continue;
                int best = -1;
                String bestAddr = "";
                for (Object pObj : points) {
                    Map<String, Object> p = Json.obj(pObj);
                    if (Json.bool(p.get("isArea"))) continue;
                    String addr = Json.str(p.get("addr"));
                    if (!addr.isEmpty() && cityMatchesAddr(city, addr)) {
                        int sc = (int) Json.dbl(p.get("scale"), 0);
                        if (sc > best) { best = sc; bestAddr = addr; }
                    }
                }
                if (best > 0) {
                    csb.append(" / ").append(city).append("の震度 ").append(Scale.toStr(best))
                       .append("（実測・観測点 ").append(bestAddr).append(" の実測震度 ")
                       .append(Scale.toStr(best)).append("）");
                    measuredLocs.add(loc.name());
                }
            }
            cityNote = csb.toString();
        }
        // 🌏 最寄り震度は表示しない: 最寄り観測点の実測を根拠に地点震度を推定表示する
        // 仕組みは持たない。市区町村内の観測点実測が無い地点は震度を表示せず
        // 「実測なし」と明示する（推定値を新たに作らない）
        String locEstNote = "";
        {
            StringBuilder esb = new StringBuilder();
            for (Location loc : locations) {
                if (measuredLocs.contains(loc.name())) continue;
                esb.append(" / ").append(loc.name()).append(": 実測なし");
            }
            locEstNote = esb.toString();
        }
        // 🌏 速報段階の推定情報: 震度速報のみの段階では震源・マグニチュードは未確定で
        // 確定値ではない。確定報（震度詳細情報）が出たら統合結果が確定値表示に切り替わる
        String promptEstNote = "";
        if ("ScalePrompt".equals(primary.issueType())) {
            promptEstNote = " / 推定情報: 震度速報の段階では震源・マグニチュードは未確定（推定前）で各地の震度は速報値。確定報が出たら確定値に切り替わります";
        } else if ("Destination".equals(primary.issueType())) {
            promptEstNote = " / 推定情報: この報は震源のみの発表で観測点別の震度は未発表。各地の震度は続報（震度詳細情報）で確定します";
        }
        // 🌏 県内最大震度を points から拾って添える。対象県は登録地点の都道府県
        // （地点未指定時は群馬県・埼玉県）。これは地点の震度ではなく県内の最大値なので、
        // 地点震度と誤読されない文言で明記する
        String localNote = "";
        {
            java.util.Set<String> targetPrefs = new java.util.LinkedHashSet<>();
            for (Location loc : locations) {
                String pn = EewParser.prefNameForCode(loc.jmaPrefCode());
                if (!pn.isEmpty()) targetPrefs.add(pn);
            }
            if (targetPrefs.isEmpty()) { targetPrefs.add("群馬県"); targetPrefs.add("埼玉県"); }
            java.util.Map<String, Integer> localMax = new LinkedHashMap<>();
            for (Object pObj : points) {
                Map<String, Object> p = Json.obj(pObj);
                String pref = Json.str(p.get("pref"));
                if (targetPrefs.contains(pref)) {
                    int sc = (int) Json.dbl(p.get("scale"), 0);
                    localMax.merge(pref, sc, Math::max);
                }
            }
            if (!localMax.isEmpty()) {
                StringBuilder lsb = new StringBuilder();
                for (Map.Entry<String, Integer> e : localMax.entrySet()) {
                    lsb.append(" / ").append(e.getKey()).append("内の最大震度 ").append(Scale.toStr(e.getValue()));
                }
                localNote = lsb.toString();
            }
        }
        // 🌏 各地の震度（都道府県別の最大震度を降順に最大6件）
        String prefNote = "";
        {
            java.util.Map<String, Integer> prefMax = new LinkedHashMap<>();
            for (Object pObj : points) {
                Map<String, Object> p = Json.obj(pObj);
                String pref = Json.str(p.get("pref"));
                if (!pref.isEmpty()) prefMax.merge(pref, (int) Json.dbl(p.get("scale"), 0), Math::max);
            }
            if (prefMax.size() > 1) {
                List<Map.Entry<String, Integer>> sortedPrefs = new ArrayList<>(prefMax.entrySet());
                sortedPrefs.sort((a, b) -> b.getValue() - a.getValue());
                StringBuilder psb = new StringBuilder(" / 各地の震度:");
                int n = 0;
                for (Map.Entry<String, Integer> e : sortedPrefs) {
                    if (n++ >= 6) break;
                    String pn = e.getKey().replaceAll("[県府都]$", "");
                    psb.append(" ").append(pn).append(" ").append(Scale.toStr(e.getValue()));
                }
                prefNote = psb.toString();
            }
        }
        // 🕐 発表情報: 値を採用した報の発表時刻と種別を本文に含め、速報／確定の判別材料にする
        String issueNote = " / 発表: " + primary.issueTime()
                + "（" + issueLabel(primary.issueType(), primary.correct())
                + "・" + typeName(primary.issueType()) + "）";
        if (sorted.size() > 1) issueNote += " / 全" + sorted.size() + "報を統合";
        Event ev = new Event(
                "earthquake",
                "地震 最大震度" + Scale.toStr(maxScale) + " " + place,
                primary.eqTime() + "発生 / 震源: " + place + " / " + magStr + " / " + depthStr + " / 最大震度 " + Scale.toStr(maxScale) + tsunamiNote + topNote + obsNote + cityNote + locEstNote + localNote + prefNote + issueNote + promptEstNote + mapNote);
        ev.severity = sev;
        ev.source = "A.A.S（P2P地震情報・気象庁）";
        ev.eventId = primary.id();
        ev.occurredAt = !primary.itemTime().isEmpty() ? primary.itemTime() : primary.eqTime();
        ev.actionGuide = guide;
        ev.maxScale = maxScale;
        ev.ensureId();
        return ev;
    }

    // 📍 市区町村名と観測点 addr の照合（addr が市区町村名で始まる場合も一致とみなす。
    // 例: city=さいたま市 に対し addr=さいたま市大宮区）
    public static boolean cityMatchesAddr(String city, String addr) {
        if (city == null || addr == null || city.isEmpty() || addr.isEmpty()) return false;
        return addr.equals(city) || addr.startsWith(city) || city.startsWith(addr);
    }

    // ----------------------------------------
    // 📍 観測点情報（実測値）の整形
    // ----------------------------------------
    // points（観測点名 addr・実測震度 scale）から、観測点の総数・最大震度を観測した
    // 地点とその実測震度・主要観測点（震度上位 最大5件）を「地点名: 実測震度」で
    // 列挙する。地域震度（isArea=true）は観測点ではないため数えない。同名観測点は
    // 最大値を残して重複を除く。観測点が1件も無い場合は「観測点情報なし」と返す
    public static String observationPointsNote(List<Object> points) {
        Map<String, Integer> byAddr = new LinkedHashMap<>();
        if (points != null) {
            for (Object pObj : points) {
                Map<String, Object> p = Json.obj(pObj);
                if (Json.bool(p.get("isArea"))) continue; // 地域震度は観測点ではない
                String addr = Json.str(p.get("addr"), Json.str(p.get("pref")));
                int sc = (int) Json.dbl(p.get("scale"), 0);
                if (addr.isEmpty() || sc <= 0) continue;
                byAddr.merge(addr, sc, Math::max);
            }
        }
        if (byAddr.isEmpty()) return "観測点情報なし（観測点別の実測値は未発表）";
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(byAddr.entrySet());
        sorted.sort((a, b) -> a.getValue().equals(b.getValue())
                ? a.getKey().compareTo(b.getKey())
                : Integer.compare(b.getValue(), a.getValue()));
        Map.Entry<String, Integer> top = sorted.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("観測点情報（実測）: 全").append(sorted.size()).append("地点")
          .append(" / 最大震度観測点: ").append(top.getKey())
          .append(" 実測震度 ").append(Scale.toStr(top.getValue()))
          .append(" / 主要観測点: ");
        int shown = Math.min(5, sorted.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append("、");
            sb.append(sorted.get(i).getKey()).append(": 実測震度 ").append(Scale.toStr(sorted.get(i).getValue()));
        }
        if (sorted.size() > shown) sb.append(" ほか").append(sorted.size() - shown).append("地点");
        return sb.toString();
    }
}
