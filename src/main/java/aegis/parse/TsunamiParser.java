package aegis.parse;

import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ========================================
// 🌊 TsunamiParser — 津波情報の解析
// ========================================
// 📌 入力は A2S 変換（aegis.a2s.A2sConvert）が復元した内部形式の item
// （旧 P2P 552 電文と同形）のみ。本クラスは取得を行わない
public final class TsunamiParser {
    private TsunamiParser() {}

    public static List<Event> parse(Object data) {
        List<Event> events = new ArrayList<>();
        for (Object itemObj : Json.arr(data)) {
            Map<String, Object> item = Json.obj(itemObj);
            boolean cancelled = Json.bool(item.get("cancelled"));
            List<String> grades = new ArrayList<>();
            for (Object aObj : Json.arr(item.get("areas"))) {
                Map<String, Object> a = Json.obj(aObj);
                if (!Json.str(a.get("grade")).isEmpty()) grades.add(Json.str(a.get("grade")));
            }
            Severity sev;
            String label;
            String guide;
            if (cancelled) {
                sev = Severity.MEDIUM; label = "津波警報・注意報 解除";
                guide = "解除を確認。ただし公式発表で安全が確認できるまで海岸には近づかない";
            } else if (grades.contains("MajorWarning")) {
                sev = Severity.CRITICAL; label = "大津波警報";
                guide = "ただちに高台・津波避難ビルへ避難。海岸・河口に絶対に近づかない";
            } else if (grades.contains("Warning")) {
                sev = Severity.CRITICAL; label = "津波警報";
                guide = "ただちに高台・津波避難ビルへ避難。海岸・河口に絶対に近づかない";
            } else if (grades.contains("Watch")) {
                sev = Severity.HIGH; label = "津波注意報";
                guide = "海から上がり海岸から離れる。海水浴・磯釣りは中止";
            } else {
                sev = Severity.MEDIUM; label = "津波予報/情報";
                guide = "公式の続報を確認する";
            }
            // 🖥️ 地域はグレード別にグループ化して表示する。最大グレードのラベルを
            // 全地域に被せず、グループごとに件数を絞る場合は「ほかN区域」を付記する
            String areaTxt = areaText(item);
            Event ev = new Event("tsunami", label + "（" + areaTxt + "）",
                    "対象地域: " + areaTxt + " / 発表: " + Json.str(item.get("time")));
            ev.severity = sev;
            ev.source = "A.A.S（気象庁 津波情報）";
            ev.eventId = Json.str(item.get("id"));
            ev.occurredAt = Json.str(item.get("time"));
            ev.actionGuide = guide;
            ev.ensureId();
            events.add(ev);
        }
        return events;
    }

    // 🌊 対象地域テキスト: areas を grade ごとにグループ化し、「大津波警報: A、B /
    // 津波警報: C / 津波注意報: D」の形にする。1グループの表示は最大4区域までとし、
    // 切り捨てた場合は「ほかN区域」を付記する。予想高さ・第1波到達予想・到達確認等の
    // condition も併記する
    public static String areaText(Map<String, Object> item) {
        Map<String, List<String>> byGrade = new LinkedHashMap<>();
        for (Object aObj : Json.arr(item.get("areas"))) {
            Map<String, Object> a = Json.obj(aObj);
            String nm = Json.str(a.get("name"));
            if (nm.isEmpty()) continue;
            String mh = Json.str(Json.obj(a.get("maxHeight")).get("description"));
            if (!mh.isEmpty()) nm += "（予想 " + mh + "）";
            String at = Json.str(Json.obj(a.get("firstHeight")).get("arrivalTime"));
            if (!at.isEmpty()) nm += "（第1波到達予想 " + (at.length() >= 16 ? at.substring(11, 16) : at) + "）";
            String cond = Json.str(Json.obj(a.get("firstHeight")).get("condition"));
            if (!cond.isEmpty()) nm += "（" + cond + "）";
            byGrade.computeIfAbsent(Json.str(a.get("grade")), k -> new ArrayList<>()).add(nm);
        }
        if (byGrade.isEmpty()) return "全国";
        Map<String, String> gradeLabel = Map.of(
                "MajorWarning", "大津波警報", "Warning", "津波警報",
                "Watch", "津波注意報", "Unknown", "津波予報");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byGrade.entrySet()) {
            List<String> ns = e.getValue();
            String shown = String.join("、", ns.subList(0, Math.min(4, ns.size())));
            if (ns.size() > 4) shown += " ほか" + (ns.size() - 4) + "区域";
            parts.add(gradeLabel.getOrDefault(e.getKey(), "津波情報") + ": " + shown);
        }
        return String.join(" / ", parts);
    }
}
