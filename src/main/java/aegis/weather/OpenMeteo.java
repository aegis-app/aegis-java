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
// 🌤️ OpenMeteo — Open-Meteo の取得と解析（現在値・時間別・週間）
// ========================================
// 📌 天気予報そのものは A2S の対象外のため Open-Meteo を直読みする
// （APIキー不要）。モデルは明示指定する: 主モデル jma_seamless（気象庁
// MSM/GSM）+ 降水確率のみ ecmwf_ifs025 で補完する複合指定。順序が
// current と応答格子を決めるため入れ替え禁止。best_match への黙示
// フォールバックもしない
public final class OpenMeteo {
    private OpenMeteo() {}

    // 📝 Open-Meteo のモデル明示指定（複合指定の正本）
    public static final String MODELS = "jma_seamless,ecmwf_ifs025";
    // 📝 UI 併記用の出典モデル表記
    public static final String SOURCE_LABEL = "Open-Meteo（気温・天気: 気象庁モデル MSM/GSM、降水確率: ECMWF IFS）";

    // ⌨️ 複合指定時の接尾辞付きキーから配列を読む。JMA 系変数は _jma_seamless、
    // 降水確率は _ecmwf_ifs025（JMA 側の同名キーは全て null のため読まない）。
    // 接尾辞付きキーが無い応答（旧フィクスチャ等）では接尾辞なしキーにフォールバックする
    public static List<Object> arrJma(Map<String, Object> m, String base) {
        Object v = m.get(base + "_jma_seamless");
        return Json.arr(v != null ? v : m.get(base));
    }

    public static List<Object> arrEcmwf(Map<String, Object> m, String base) {
        Object v = m.get(base + "_ecmwf_ifs025");
        return Json.arr(v != null ? v : m.get(base));
    }

    // 🔢 数値の整形（12.0 → "12"、それ以外はそのまま）。欠測の表記は呼び出し側で決める
    public static String fmtNum(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ----------------------------------------
    // 🌤️ 現在の気象（ブリーフィング・質問応答用）
    // ----------------------------------------
    public static Event fetchCurrent(Location loc) throws Exception {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + loc.lat() + "&longitude=" + loc.lon()
                + "&current=temperature_2m,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum"
                + "&forecast_days=7&timezone=Asia%2FTokyo"
                + "&models=" + MODELS;
        return parseCurrent(Http.fetchJson(url), loc);
    }

    public static Event parseCurrent(Object data, Location loc) {
        Map<String, Object> root = Json.obj(data);
        Map<String, Object> cur = Json.obj(root.get("current"));
        Map<String, Object> daily = Json.obj(root.get("daily"));
        int code = (int) Json.dbl(cur.get("weather_code"), -1);
        String desc = JmaForecast.WMO_WEATHER.getOrDefault(code, "不明");
        List<Object> tmaxArr = arrJma(daily, "temperature_2m_max");
        List<Object> tminArr = arrJma(daily, "temperature_2m_min");
        List<Object> popArr = arrEcmwf(daily, "precipitation_probability_max");
        Object tmax = tmaxArr.isEmpty() ? null : tmaxArr.get(0);
        Object tmin = tminArr.isEmpty() ? null : tminArr.get(0);
        double pop = popArr.isEmpty() ? -1 : Json.dbl(popArr.get(0), -1);
        double wind = Json.dbl(cur.get("wind_speed_10m"), 0);
        Severity sev = Severity.INFO;
        String guide = "";
        if (pop >= 80) { sev = Severity.MEDIUM; guide = "降水確率が高め。折りたたみ傘ではなく大きめの傘が安心"; }
        if (wind >= 54) { sev = Severity.MEDIUM; guide = "風が強い。飛来物と電車の遅延に注意"; }
        // 🔄 数値は fmtNum で整形し（12.0 → "12"）、欠測は「—」にする
        String tempStr = cur.get("temperature_2m") == null ? "—" : fmtNum(Json.dbl(cur.get("temperature_2m"), 0));
        String windStr = cur.get("wind_speed_10m") == null ? "—" : fmtNum(Json.dbl(cur.get("wind_speed_10m"), 0));
        String tminStr = tmin == null ? "—" : fmtNum(Json.dbl(tmin, 0));
        String tmaxStr = tmax == null ? "—" : fmtNum(Json.dbl(tmax, 0));
        // 🌧️ 降水量（mm）も併記し、降水確率だけでは分からない「大雨かどうか」を判断できるようにする
        List<Object> precipArr = arrJma(daily, "precipitation_sum");
        Object precip0 = precipArr.isEmpty() ? null : precipArr.get(0);
        String precipStr = precip0 == null ? "—" : fmtNum(Json.dbl(precip0, 0));
        String body = loc.name() + ": 現在 " + desc + " " + tempStr + "℃ / 風 "
                + windStr + "km/h / 今日 " + tminStr + "〜" + tmaxStr
                + "℃ / 降水確率 最大" + (pop < 0 ? "—" : fmtNum(pop)) + "% / 降水量 今日 " + precipStr + "mm"
                // 📝 2ソースの役割固定: 現在値は Open-Meteo を正とする
                + " / 出典: 現在値は Open-Meteo";
        // ➕ 週間予報（今日を含む最大7日分）を1行追加する
        String weekly = weeklyLine(daily);
        if (!weekly.isEmpty()) body += " / " + weekly;
        Event ev = new Event("weather_forecast", "現在の気象（" + loc.name() + "）", body);
        ev.severity = sev;
        ev.source = SOURCE_LABEL;
        ev.occurredAt = Json.str(cur.get("time"));
        ev.actionGuide = guide;
        ev.ensureId();
        return ev;
    }

    // 🌦️ WMO 天気コード → 絵文字（週間予報の整形用）
    public static String wmoEmoji(int code) {
        return switch (code) {
            case 0, 1 -> "☀";
            case 2 -> "🌤";
            case 3 -> "☁";
            case 45, 48 -> "🌫";
            case 51, 53, 55, 80, 81, 82 -> "🌦";
            case 56, 57, 61, 63, 65, 66, 67 -> "🌧";
            case 71, 73, 75, 77, 85, 86 -> "❄";
            case 95, 96, 99 -> "⛈";
            default -> "☁";
        };
    }

    // 📊 daily から週間1行を作る（今日を含む最大7日）。データが無ければ ""
    public static String weeklyLine(Map<String, Object> daily) {
        List<Object> times = Json.arr(daily.get("time"));
        if (times.isEmpty()) return "";
        List<Object> codes = arrJma(daily, "weather_code");
        List<Object> tmax = arrJma(daily, "temperature_2m_max");
        List<Object> tmin = arrJma(daily, "temperature_2m_min");
        StringBuilder sb = new StringBuilder("週間:");
        for (int i = 0; i < Math.min(7, times.size()); i++) {
            String d = Json.str(times.get(i));
            String md = d.length() >= 10 ? d.substring(5).replace('-', '/') : d;
            int code = i < codes.size() ? (int) Json.dbl(codes.get(i), -1) : -1;
            String hi = i < tmax.size() && tmax.get(i) != null ? fmtNum(Json.dbl(tmax.get(i), 0)) : "?";
            String lo = i < tmin.size() && tmin.get(i) != null ? fmtNum(Json.dbl(tmin.get(i), 0)) : "?";
            sb.append(i > 0 ? " / " : " ").append(md).append(' ').append(wmoEmoji(code))
                    .append(' ').append(lo).append("〜").append(hi).append("℃");
        }
        return sb.toString();
    }

    // ----------------------------------------
    // 📊 ダッシュボード用テーブル
    // ----------------------------------------
    private static final String[] WEEKDAY_JA = {"月", "火", "水", "木", "金", "土", "日"};

    // 🌧️ ダッシュボード表用の天気絵文字。降水確率が高ければ雨系を優先し、夜の晴れは月に変える
    public static String wxIcon(int code, double pop, int hour) {
        String icon = wmoEmoji(code);
        if (pop >= 50 && (icon.equals("☀") || icon.equals("🌤") || icon.equals("☁"))) {
            icon = pop >= 80 ? "🌧" : "🌦";
        }
        if (icon.equals("☀") && hour >= 0 && (hour >= 21 || hour <= 4)) icon = "🌙";
        return icon;
    }

    // 📝 hourly から時間別予報の行を作る: 今日の残り＋明日を3時間区間に集約する。
    // 1時間値を保持し、区間内の最大降水確率を popMax として併記して、3時間サンプリングで
    // 短時間のピークを取りこぼさないようにする。降水量は区間合計。代表値（天気・気温）は
    // 区間先頭の時間のものを使う
    public static List<WeatherTables.HourRow> hourlyRows(Map<String, Object> hourly, LocalDateTime now) {
        List<WeatherTables.HourRow> rows = new ArrayList<>();
        List<Object> times = Json.arr(hourly.get("time"));
        List<Object> temps = arrJma(hourly, "temperature_2m");
        List<Object> codes = arrJma(hourly, "weather_code");
        List<Object> pops = arrEcmwf(hourly, "precipitation_probability");
        List<Object> precips = arrJma(hourly, "precipitation");
        LocalDateTime start = now.withMinute(0).withSecond(0).withNano(0);
        java.time.LocalDate endDate = now.toLocalDate().plusDays(1);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
        // 🕐 区間キー（3時間に切り下げた開始時刻）ごとに集約する
        Map<LocalDateTime, List<Integer>> blocks = new LinkedHashMap<>();
        for (int i = 0; i < times.size(); i++) {
            LocalDateTime dt;
            try { dt = LocalDateTime.parse(Json.str(times.get(i)), fmt); } catch (Exception e) { continue; }
            if (dt.isBefore(start) || dt.toLocalDate().isAfter(endDate)) continue;
            LocalDateTime block = dt.withHour(dt.getHour() / 3 * 3);
            blocks.computeIfAbsent(block, k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<LocalDateTime, List<Integer>> e : blocks.entrySet()) {
            LocalDateTime block = e.getKey();
            List<Integer> idxs = e.getValue();
            int first = idxs.get(0);
            int code = first < codes.size() ? (int) Json.dbl(codes.get(first), -1) : -1;
            double popFirst = -1, popMax = -1, precipSum = 0;
            boolean hasPrecip = false;
            for (int i : idxs) {
                Object popObj = i < pops.size() ? pops.get(i) : null;
                double pv = popObj == null ? -1 : Json.dbl(popObj, -1);
                if (i == first) popFirst = pv;
                if (pv > popMax) popMax = pv;
                Object prObj = i < precips.size() ? precips.get(i) : null;
                if (prObj != null) { precipSum += Json.dbl(prObj, 0); hasPrecip = true; }
            }
            Object tempObj = first < temps.size() ? temps.get(first) : null;
            String temp = tempObj == null ? "?" : fmtNum(Json.dbl(tempObj, 0));
            rows.add(new WeatherTables.HourRow(
                    block.getMonthValue() + "/" + block.getDayOfMonth() + " " + block.getHour() + "時",
                    wxIcon(code, popMax, block.getHour()),
                    JmaForecast.WMO_WEATHER.getOrDefault(code, "不明"),
                    temp,
                    popFirst < 0 ? "—" : String.valueOf(Math.round(popFirst)),
                    popMax < 0 ? "—" : String.valueOf(Math.round(popMax)),
                    hasPrecip ? fmtNum(Math.round(precipSum * 10) / 10.0) : "—"));
        }
        return rows;
    }

    // 📝 daily（7日分）から週間予報の行を作る（今日を含む全日）
    public static List<WeatherTables.DayRow> weeklyRows(Map<String, Object> daily) {
        List<WeatherTables.DayRow> rows = new ArrayList<>();
        List<Object> times = Json.arr(daily.get("time"));
        List<Object> codes = arrJma(daily, "weather_code");
        List<Object> tmax = arrJma(daily, "temperature_2m_max");
        List<Object> tmin = arrJma(daily, "temperature_2m_min");
        List<Object> pops = arrEcmwf(daily, "precipitation_probability_max");
        List<Object> precips = arrJma(daily, "precipitation_sum");
        for (int i = 0; i < Math.min(7, times.size()); i++) {
            String d = Json.str(times.get(i));
            if (d.length() < 10) continue;
            String wd = "";
            try { wd = WEEKDAY_JA[java.time.LocalDate.parse(d.substring(0, 10)).getDayOfWeek().getValue() - 1]; }
            catch (Exception ignored) {}
            int code = i < codes.size() ? (int) Json.dbl(codes.get(i), -1) : -1;
            Object popObj = i < pops.size() ? pops.get(i) : null;
            double pop = popObj == null ? -1 : Json.dbl(popObj, -1);
            Object loObj = i < tmin.size() ? tmin.get(i) : null;
            Object hiObj = i < tmax.size() ? tmax.get(i) : null;
            String lo = loObj == null ? "?" : fmtNum(Json.dbl(loObj, 0));
            String hi = hiObj == null ? "?" : fmtNum(Json.dbl(hiObj, 0));
            String date = Integer.parseInt(d.substring(5, 7)) + "/" + Integer.parseInt(d.substring(8, 10))
                    + (wd.isEmpty() ? "" : "（" + wd + "）");
            Object prObj = i < precips.size() ? precips.get(i) : null;
            String precip = prObj == null ? "—" : fmtNum(Json.dbl(prObj, 0));
            rows.add(new WeatherTables.DayRow(date, wxIcon(code, pop, -1), JmaForecast.WMO_WEATHER.getOrDefault(code, "不明"),
                    lo + "〜" + hi, pop < 0 ? "—" : String.valueOf(Math.round(pop)), precip));
        }
        return rows;
    }

    // 🌐 取得済み JSON からダッシュボード表データを組み立てる（純粋関数）
    public static WeatherTables parseWeatherTables(Object omData, Object jmaData, String areaCode, LocalDateTime now) {
        Map<String, Object> root = Json.obj(omData);
        return new WeatherTables(
                hourlyRows(Json.obj(root.get("hourly")), now),
                weeklyRows(Json.obj(root.get("daily"))),
                jmaData == null ? List.of() : JmaForecast.popsRows(jmaData, areaCode, now));
    }

    // 🌦️ ダッシュボード用に Open-Meteo（hourly+daily）と気象庁予報を取得し、表データに変換する
    public static WeatherTables fetchWeatherTables(Location loc) throws Exception {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + loc.lat() + "&longitude=" + loc.lon()
                + "&hourly=temperature_2m,weather_code,precipitation_probability,precipitation"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum"
                + "&forecast_days=7&timezone=Asia%2FTokyo"
                + "&models=" + MODELS;
        Object om = Http.fetchJson(url);
        Object jma = null;
        String areaCode = "";
        try {
            jma = Http.fetchJson("https://www.jma.go.jp/bosai/forecast/data/forecast/" + loc.jmaPrefCode() + ".json");
            areaCode = JmaForecast.forecastAreaCode(jma, loc.jmaAreaName());
        } catch (Exception ignored) { jma = null; }
        return parseWeatherTables(om, jma, areaCode, LocalDateTime.now(ZoneId.of("Asia/Tokyo")));
    }
}
