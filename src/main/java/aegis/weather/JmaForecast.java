package aegis.weather;

import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Location;
import aegis.core.Severity;
import aegis.net.Http;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ========================================
// 🌦️ JmaForecast — 気象庁 bosai 予報の取得と解析
// ========================================
// 📌 天気予報そのものは A2S の対象外のため、気象庁 bosai の予報 JSON を
// 直読みする（APIキー不要）。警報・注意報の取得は A2S 一本化済みで
// 本クラスには無い。ここに残るのは予報の解析と、ダッシュボード表が使う
// 降水確率の行生成、区域コードの解決である
public final class JmaForecast {
    private JmaForecast() {}

    // ⚠️ 気象庁の警報コード表（コード → 名称）。特別警報は 32〜38 番台。
    // A2S の warning 変換が名称を持たない場合の参照用にも使う
    public static final Map<String, String> WARNING_CODES = new LinkedHashMap<>();
    static {
        String[][] pairs = {
                {"02", "暴風雪警報"}, {"03", "大雨警報"}, {"04", "洪水警報"}, {"05", "暴風警報"},
                {"06", "大雪警報"}, {"07", "波浪警報"}, {"08", "高潮警報"},
                {"10", "大雨注意報"}, {"12", "大雪注意報"}, {"13", "風雪注意報"}, {"14", "雷注意報"},
                {"15", "強風注意報"}, {"16", "波浪注意報"}, {"17", "融雪注意報"}, {"18", "洪水注意報"},
                {"19", "高潮注意報"}, {"20", "濃霧注意報"}, {"21", "乾燥注意報"}, {"22", "なだれ注意報"},
                {"23", "低温注意報"}, {"24", "霜注意報"}, {"25", "着氷注意報"}, {"26", "着雪注意報"},
                {"32", "暴風雪特別警報"}, {"33", "大雨特別警報"}, {"35", "暴風特別警報"},
                {"36", "高潮特別警報"}, {"37", "波浪特別警報"}, {"38", "大雪特別警報"},
        };
        for (String[] p : pairs) WARNING_CODES.put(p[0], p[1]);
    }

    // 🌦️ WMO 天気コード → 日本語表記（Open-Meteo の weather_code 用）
    public static final Map<Integer, String> WMO_WEATHER = Map.ofEntries(
            Map.entry(0, "快晴"), Map.entry(1, "晴れ"), Map.entry(2, "一部曇り"), Map.entry(3, "曇り"),
            Map.entry(45, "霧"), Map.entry(48, "霧"), Map.entry(51, "霧雨"), Map.entry(53, "霧雨"), Map.entry(55, "霧雨"),
            Map.entry(61, "雨"), Map.entry(63, "雨"), Map.entry(65, "大雨"),
            Map.entry(71, "雪"), Map.entry(73, "雪"), Map.entry(75, "大雪"),
            Map.entry(80, "にわか雨"), Map.entry(81, "にわか雨"), Map.entry(82, "激しい雨"),
            Map.entry(95, "雷雨"), Map.entry(96, "雷雨（雹）"), Map.entry(99, "雷雨（雹）"),
            Map.entry(56, "着氷性の霧雨"), Map.entry(57, "着氷性の霧雨"), Map.entry(66, "着氷性の雨"),
            Map.entry(67, "着氷性の雨"), Map.entry(77, "雪あられ"), Map.entry(85, "雪"), Map.entry(86, "雪"));

    // ----------------------------------------
    // 📍 一次細分区域コードの解決（地点ごとにキャッシュ）
    // ----------------------------------------
    private static final Map<String, String> AREA_CODE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    // 🔍 予報 JSON から一次細分区域名に対応する区域コードを得る
    public static String forecastAreaCode(Object data, String areaName) {
        List<Object> list = Json.arr(data);
        if (list.isEmpty()) return "";
        List<Object> series = Json.arr(Json.obj(list.get(0)).get("timeSeries"));
        if (series.isEmpty()) return "";
        for (Object aObj : Json.arr(Json.obj(series.get(0)).get("areas"))) {
            Map<String, Object> area = Json.obj(Json.obj(aObj).get("area"));
            if (areaName.equals(Json.str(area.get("name")))) return Json.str(area.get("code"));
        }
        return "";
    }

    // 🌐 地点の区域コードを取得してキャッシュする（取得失敗はキャッシュしない）
    public static String areaCodeFor(Location loc) {
        String key = loc.jmaPrefCode() + "|" + loc.jmaAreaName();
        String cached = AREA_CODE_CACHE.get(key);
        if (cached != null) return cached;
        String code = "";
        try {
            code = forecastAreaCode(Http.fetchJson("https://www.jma.go.jp/bosai/forecast/data/forecast/" + loc.jmaPrefCode() + ".json"), loc.jmaAreaName());
        } catch (Exception ignored) {}
        // 🌐 取得失敗（空文字）をキャッシュすると、通信が回復しても県内全域マッチのまま
        // 固定されてしまうため、成功したコードだけをキャッシュする
        cacheAreaCode(key, code);
        return code;
    }

    // 🧪 取得できた地域コードだけをキャッシュする（テスト対象）。キャッシュしたら true
    public static boolean cacheAreaCode(String key, String code) {
        if (code == null || code.isEmpty()) return false;
        AREA_CODE_CACHE.put(key, code);
        return true;
    }

    // 🧪 キャッシュ済みの地域コードを返す（未キャッシュなら null）。テスト対象
    public static String cachedAreaCode(String key) {
        return AREA_CODE_CACHE.get(key);
    }

    // ----------------------------------------
    // 🌦️ 予報の取得と解析
    // ----------------------------------------
    public static Event fetchForecast(Location loc) throws Exception {
        return parseForecast(Http.fetchJson("https://www.jma.go.jp/bosai/forecast/data/forecast/" + loc.jmaPrefCode() + ".json"), loc);
    }

    public static Event parseForecast(Object data, Location loc) {
        List<Object> list = Json.arr(data);
        if (list.isEmpty()) return null;
        Map<String, Object> first = Json.obj(list.get(0));
        List<Object> series = Json.arr(first.get("timeSeries"));
        if (series.isEmpty()) return null;
        List<Object> areas = Json.arr(Json.obj(series.get(0)).get("areas"));
        Map<String, Object> chosen = null;
        boolean areaFallback = false;
        for (Object aObj : areas) {
            Map<String, Object> a = Json.obj(aObj);
            String an = Json.str(Json.obj(a.get("area")).get("name"));
            if (!loc.jmaAreaName().isEmpty() && an.equals(loc.jmaAreaName())) { chosen = a; break; }
        }
        if (chosen == null && !areas.isEmpty()) { chosen = Json.obj(areas.get(0)); areaFallback = true; }
        if (chosen == null) return null;
        List<Object> weathers = Json.arr(chosen.get("weathers"));
        List<Object> winds = Json.arr(chosen.get("winds"));
        String today = weathers.isEmpty() ? "不明" : Json.str(weathers.get(0));
        String tomorrow = weathers.size() > 1 ? Json.str(weathers.get(1)) : "";
        StringBuilder body = new StringBuilder(loc.name() + ": 今日 " + today);
        if (!winds.isEmpty()) body.append(" / 風: ").append(Json.str(winds.get(0)));
        if (!tomorrow.isEmpty()) body.append(" / 明日 ").append(tomorrow);
        // 🌧️ 降水確率（6時間ごと）と気温も併記する
        String chosenCode = Json.str(Json.obj(chosen.get("area")).get("code"));
        // 🌧️ 降水確率は現在時刻以降の枠だけを表示する
        String pops = popsText(data, chosenCode, LocalDateTime.now(ZoneId.of("Asia/Tokyo")));
        if (!pops.isEmpty()) body.append(" / ").append(pops);
        String temps = tempsText(data, chosenCode);
        if (!temps.isEmpty()) body.append(" / ").append(temps);
        // 📝 区域不一致で先頭区域へフォールバックした場合は無言にせず本文に付記する
        if (areaFallback || popsAreaFallback(data, chosenCode) || tempsAreaFallback(data, chosenCode)) {
            body.append(" / 注: 指定区域（").append(loc.jmaAreaName()).append("）と一致しないため、先頭区域の予報を表示しています");
        }
        // 🌧️ 2ソースの役割固定: 概況・降水確率・警報は気象庁を正とする
        body.append(" / 出典: 概況・降水確率・気温は気象庁");
        Event ev = new Event("weather_forecast", "天気予報（" + loc.name() + "）", body.toString());
        ev.severity = Severity.INFO;
        ev.source = "気象庁（" + Json.str(first.get("publishingOffice")) + "）";
        ev.occurredAt = Json.str(first.get("reportDatetime"));
        ev.ensureId();
        return ev;
    }

    // ----------------------------------------
    // 🌧️ 降水確率（pops）の整形
    // ----------------------------------------
    public static String popsText(Object data, String areaCode) {
        return popsText(data, areaCode, null);
    }

    // 🕐 現在時刻フィルタ付き: now が非 null の場合、時間帯の終了時刻（開始+6時間）が
    // now 以前の経過済み枠は表示しない
    public static String popsText(Object data, String areaCode, LocalDateTime now) {
        List<Object> list = Json.arr(data);
        if (list.isEmpty()) return "";
        for (Object tsObj : Json.arr(Json.obj(list.get(0)).get("timeSeries"))) {
            Map<String, Object> ts = Json.obj(tsObj);
            List<Object> tas = Json.arr(ts.get("areas"));
            if (tas.isEmpty() || !Json.obj(tas.get(0)).containsKey("pops")) continue;
            Map<String, Object> pa = null;
            for (Object aObj : tas) {
                Map<String, Object> a = Json.obj(aObj);
                if (areaCode.equals(Json.str(Json.obj(a.get("area")).get("code")))) { pa = a; break; }
            }
            if (pa == null) pa = Json.obj(tas.get(0));
            List<Object> pops = Json.arr(pa.get("pops"));
            List<Object> defs = Json.arr(ts.get("timeDefines"));
            StringBuilder pb = new StringBuilder("降水確率:");
            int shown = 0;
            String prevDate = "";
            for (int i = 0; i < pops.size() && shown < 6; i++) {
                String def = i < defs.size() ? Json.str(defs.get(i)) : "";
                if (def.length() < 13) continue;
                int hour = Integer.parseInt(def.substring(11, 13));
                if (now != null && def.length() >= 16) {
                    try {
                        LocalDateTime slotStart = LocalDateTime.parse(def.substring(0, 16),
                                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
                        if (!slotStart.plusHours(6).isAfter(now)) continue; // 経過済みの枠
                    } catch (Exception ignored) {}
                }
                String date = def.substring(5, 10).replace('-', '/');
                if (!date.equals(prevDate)) { pb.append(" ").append(date); prevDate = date; }
                pb.append(" ").append(hour).append("〜").append((hour + 6) % 24).append("時").append(Json.str(pops.get(i))).append("%");
                shown++;
            }
            return shown > 0 ? pb.toString() : "";
        }
        return "";
    }

    // 📝 区域コードで照合する系列（pops）が先頭区域へフォールバックしたか
    public static boolean popsAreaFallback(Object data, String areaCode) {
        if (areaCode == null || areaCode.isEmpty()) return false;
        List<Object> list = Json.arr(data);
        if (list.isEmpty()) return false;
        for (Object tsObj : Json.arr(Json.obj(list.get(0)).get("timeSeries"))) {
            Map<String, Object> ts = Json.obj(tsObj);
            List<Object> tas = Json.arr(ts.get("areas"));
            if (tas.isEmpty() || !Json.obj(tas.get(0)).containsKey("pops")) continue;
            for (Object aObj : tas) {
                if (areaCode.equals(Json.str(Json.obj(Json.obj(aObj).get("area")).get("code")))) return false;
            }
            return true;
        }
        return false;
    }

    // ----------------------------------------
    // 🌡️ 気温（temps）の整形
    // ----------------------------------------
    // 後方互換用: 区域コードなし（先頭エリア採用の旧挙動）
    public static String tempsText(Object data) {
        return tempsText(data, "");
    }

    // 🌧️ 気温も降水確率と同じ一次細分区域コード照合で選ぶ。temps 系列のエリアは区域の
    // 代表地点（前橋・熊谷など）で、コードが区域コードと一致しない配信形式もあるため、
    // (1) コード完全一致 → (2) 天気系列における区域の並び順に対応する代表地点 →
    // (3) 先頭エリア、の順で選ぶ
    public static String tempsText(Object data, String areaCode) {
        List<Object> list = Json.arr(data);
        if (list.isEmpty()) return "";
        List<Object> series = Json.arr(Json.obj(list.get(0)).get("timeSeries"));
        for (Object tsObj : series) {
            Map<String, Object> ts = Json.obj(tsObj);
            List<Object> tas = Json.arr(ts.get("areas"));
            if (tas.isEmpty() || !Json.obj(tas.get(0)).containsKey("temps")) continue;
            Map<String, Object> ta = null;
            if (!areaCode.isEmpty()) {
                // 📝 (1) 区域コード完全一致
                for (Object aObj : tas) {
                    Map<String, Object> a = Json.obj(aObj);
                    if (areaCode.equals(Json.str(Json.obj(a.get("area")).get("code")))) { ta = a; break; }
                }
                // 🌦️ (2) 天気系列での区域の並び順に対応する代表地点
                if (ta == null && !series.isEmpty()) {
                    List<Object> weatherAreas = Json.arr(Json.obj(series.get(0)).get("areas"));
                    for (int i = 0; i < weatherAreas.size(); i++) {
                        String code = Json.str(Json.obj(Json.obj(weatherAreas.get(i)).get("area")).get("code"));
                        if (areaCode.equals(code) && i < tas.size()) { ta = Json.obj(tas.get(i)); break; }
                    }
                }
            }
            if (ta == null) ta = Json.obj(tas.get(0));
            List<Object> temps = Json.arr(ta.get("temps"));
            if (temps.size() < 2) return "";
            String city = Json.str(Json.obj(ta.get("area")).get("name"));
            // 🔄 配列順の決め打ちをやめ、timeDefines と組でパースして日付で振り分ける。
            // timeDefines が使えない形式のときだけ従来の位置前提に倒す
            String byDate = tempsByDate(temps, Json.arr(ts.get("timeDefines")),
                    java.time.LocalDate.now(ZoneId.of("Asia/Tokyo")));
            if (!byDate.isEmpty()) return "気温（" + city + "）: " + byDate;
            if (temps.size() < 4) return "";
            return "気温（" + city + "）: 今日 " + Json.str(temps.get(0)) + "〜" + Json.str(temps.get(1))
                    + "℃ / 明日 " + Json.str(temps.get(2)) + "〜" + Json.str(temps.get(3)) + "℃";
        }
        return "";
    }

    // 🧪 temps と timeDefines の組から「今日 min〜max℃ / 明日 min〜max℃」を作る（テスト対象）。
    // 対応付け: timeDefines が temps の半数なら各定義日時に（最低, 最高）の対が対応し、
    // 同数なら時刻が午前（9時以下）の値を最低・それ以外を最高として日付ごとに集める。
    // 今日・明日どちらも組み立てられない場合は ""（呼び出し側が位置前提にフォールバック）
    public static String tempsByDate(List<Object> temps, List<Object> timeDefines, java.time.LocalDate today) {
        if (timeDefines == null || timeDefines.isEmpty() || today == null) return "";
        Map<java.time.LocalDate, String[]> byDay = new LinkedHashMap<>();
        if (timeDefines.size() * 2 == temps.size()) {
            for (int i = 0; i < timeDefines.size(); i++) {
                java.time.LocalDate d = parseDefineDate(Json.str(timeDefines.get(i)));
                if (d == null) return "";
                byDay.put(d, new String[]{Json.str(temps.get(2 * i)), Json.str(temps.get(2 * i + 1))});
            }
        } else if (timeDefines.size() == temps.size()) {
            for (int i = 0; i < timeDefines.size(); i++) {
                String def = Json.str(timeDefines.get(i));
                java.time.LocalDate d = parseDefineDate(def);
                if (d == null || def.length() < 13) return "";
                int hour = Integer.parseInt(def.substring(11, 13));
                String[] pair = byDay.computeIfAbsent(d, k -> new String[]{"", ""});
                pair[hour <= 9 ? 0 : 1] = Json.str(temps.get(i));
            }
        } else {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] t0 = byDay.get(today);
        String[] t1 = byDay.get(today.plusDays(1));
        if (t0 != null) sb.append("今日 ").append(t0[0].isEmpty() ? "—" : t0[0]).append("〜").append(t0[1].isEmpty() ? "—" : t0[1]).append("℃");
        if (t1 != null) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append("明日 ").append(t1[0].isEmpty() ? "—" : t1[0]).append("〜").append(t1[1].isEmpty() ? "—" : t1[1]).append("℃");
        }
        return sb.toString();
    }

    private static java.time.LocalDate parseDefineDate(String def) {
        if (def == null || def.length() < 10) return null;
        try { return java.time.LocalDate.parse(def.substring(0, 10)); } catch (Exception e) { return null; }
    }

    // 📝 気温系列が先頭エリアへフォールバックしたか。コード一致・並び順照合の
    // どちらでも選べなかった場合に true
    public static boolean tempsAreaFallback(Object data, String areaCode) {
        if (areaCode == null || areaCode.isEmpty()) return false;
        List<Object> list = Json.arr(data);
        if (list.isEmpty()) return false;
        List<Object> series = Json.arr(Json.obj(list.get(0)).get("timeSeries"));
        for (Object tsObj : series) {
            Map<String, Object> ts = Json.obj(tsObj);
            List<Object> tas = Json.arr(ts.get("areas"));
            if (tas.isEmpty() || !Json.obj(tas.get(0)).containsKey("temps")) continue;
            for (Object aObj : tas) {
                if (areaCode.equals(Json.str(Json.obj(Json.obj(aObj).get("area")).get("code")))) return false;
            }
            if (!series.isEmpty()) {
                List<Object> weatherAreas = Json.arr(Json.obj(series.get(0)).get("areas"));
                for (int i = 0; i < weatherAreas.size() && i < tas.size(); i++) {
                    if (areaCode.equals(Json.str(Json.obj(Json.obj(weatherAreas.get(i)).get("area")).get("code")))) return false;
                }
            }
            return true;
        }
        return false;
    }

    // ----------------------------------------
    // 🌧️ ダッシュボード用降水確率の行
    // ----------------------------------------
    public static List<WeatherTables.PopRow> popsRows(Object data, String areaCode) {
        return popsRows(data, areaCode, null);
    }

    // 📝 now が非 null の場合、経過済みの時間帯（開始+6時間が now 以前）を除く
    public static List<WeatherTables.PopRow> popsRows(Object data, String areaCode, LocalDateTime now) {
        List<WeatherTables.PopRow> rows = new ArrayList<>();
        List<Object> list = Json.arr(data);
        if (list.isEmpty()) return rows;
        for (Object tsObj : Json.arr(Json.obj(list.get(0)).get("timeSeries"))) {
            Map<String, Object> ts = Json.obj(tsObj);
            List<Object> tas = Json.arr(ts.get("areas"));
            if (tas.isEmpty() || !Json.obj(tas.get(0)).containsKey("pops")) continue;
            Map<String, Object> pa = null;
            for (Object aObj : tas) {
                Map<String, Object> a = Json.obj(aObj);
                if (areaCode.equals(Json.str(Json.obj(a.get("area")).get("code")))) { pa = a; break; }
            }
            if (pa == null) pa = Json.obj(tas.get(0));
            List<Object> pops = Json.arr(pa.get("pops"));
            List<Object> defs = Json.arr(ts.get("timeDefines"));
            for (int i = 0; i < pops.size() && rows.size() < 6; i++) {
                String def = i < defs.size() ? Json.str(defs.get(i)) : "";
                if (def.length() < 13) continue;
                int hour = Integer.parseInt(def.substring(11, 13));
                if (now != null && def.length() >= 16) {
                    try {
                        LocalDateTime slotStart = LocalDateTime.parse(def.substring(0, 16),
                                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
                        if (!slotStart.plusHours(6).isAfter(now)) continue;
                    } catch (Exception ignored) {}
                }
                String date = Integer.parseInt(def.substring(5, 7)) + "/" + Integer.parseInt(def.substring(8, 10));
                rows.add(new WeatherTables.PopRow(date, hour + "〜" + ((hour + 6) % 24) + "時", Json.str(pops.get(i))));
            }
            return rows;
        }
        return rows;
    }
}
