package aegis.radar;

import aegis.core.Json;
import aegis.net.Http;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

// ========================================
// 🌧️ Radar — 気象レーダー（RainViewer + 気象庁降水ナウキャスト）
// ========================================
// 📌 ダッシュボードのレーダー表示用。主系は RainViewer（地理院タイルへ
// 重ねる雨タイル）、第二ソースとして気象庁の降水ナウキャスト（無料・
// キー不要、z10 まで原生解像度）を使い、RainViewer が取得できない場合に
// ダッシュボード側がフォールバックする。台風情報そのものは A2S 経由
// （aegis.a2s）であり、本クラスは扱わない
public final class Radar {
    private Radar() {}

    // 🌧️ RainViewer の最新フレーム（タイルパスと UNIX 時刻）
    public record RainFrame(String path, long time) {}

    // 🌧️ 気象庁ナウキャストのフレーム（基準時刻・対象時刻は UTC の yyyyMMddHHmmss）
    public record JmaFrame(String baseTime, String validTime, long epochSec) {}

    // 📝 weather-maps.json から最新フレーム（radar.past の最後）を選ぶ
    public static RainFrame parseRainFrame(Object data) {
        Map<String, Object> root = Json.obj(data);
        List<Object> past = Json.arr(Json.obj(root.get("radar")).get("past"));
        if (past.isEmpty()) return null;
        Map<String, Object> last = Json.obj(past.get(past.size() - 1));
        String path = Json.str(last.get("path"));
        if (path.isEmpty()) return null;
        return new RainFrame(path, (long) Json.dbl(last.get("time"), 0));
    }

    public static RainFrame fetchRainFrame() throws Exception {
        return parseRainFrame(Http.fetchJson("https://api.rainviewer.com/public/weather-maps.json"));
    }

    // ----------------------------------------
    // 🌧️ 気象庁 降水ナウキャスト（RainViewer の第二ソース）
    // ----------------------------------------
    // 📌 targetTimes_N1.json（実況）の先頭が最新フレームで、時刻は UTC。
    // タイル URL: …/nowc/{basetime}/none/{validtime}/surf/hrpns/{z}/{x}/{y}.png
    public static final String JMA_NOWCAST_TARGET_TIMES =
            "https://www.jma.go.jp/bosai/jmatile/data/nowc/targetTimes_N1.json";

    // 🧪 targetTimes_N1.json の先頭エントリを最新フレームとして読む（テスト対象）
    public static JmaFrame parseJmaFrame(Object data) {
        List<Object> arr = Json.arr(data);
        if (arr.isEmpty()) return null;
        Map<String, Object> first = Json.obj(arr.get(0));
        String bt = Json.str(first.get("basetime"));
        String vt = Json.str(first.get("validtime"));
        if (bt.isEmpty() || vt.isEmpty()) return null;
        long epoch = 0;
        try {
            epoch = LocalDateTime.parse(vt, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    .atZone(ZoneId.of("UTC")).toEpochSecond();
        } catch (Exception ignored) {}
        return new JmaFrame(bt, vt, epoch);
    }

    public static String jmaTileUrl(JmaFrame f, int z, int x, int y) {
        return "https://www.jma.go.jp/bosai/jmatile/data/nowc/" + f.baseTime() + "/none/" + f.validTime()
                + "/surf/hrpns/" + z + "/" + x + "/" + y + ".png";
    }

    public static JmaFrame fetchJmaFrame() throws Exception {
        return parseJmaFrame(Http.fetchJson(JMA_NOWCAST_TARGET_TIMES));
    }

    // ----------------------------------------
    // 🕐 フレームの鮮度表示
    // ----------------------------------------
    // 📡 レーダーフレームが古いとみなす閾値（分）。これを超えたら古い旨の注記を出す
    public static final long STALE_MINUTES = 20;

    // 🕐 フレーム時刻と現在時刻の差を分で返す（未来時刻・不正値は 0 に丸める）
    public static long ageMinutes(long frameEpochSec, long nowEpochSec) {
        if (frameEpochSec <= 0 || nowEpochSec <= 0) return 0;
        long diff = nowEpochSec - frameEpochSec;
        return diff <= 0 ? 0 : diff / 60;
    }

    // 🖥️ フレームの相対時刻表示（例: 「約5分前の観測」）。閾値超過時は古い旨の注記を付ける
    public static String ageText(long frameEpochSec, long nowEpochSec) {
        long min = ageMinutes(frameEpochSec, nowEpochSec);
        String text = "約" + min + "分前の観測";
        if (min > STALE_MINUTES) {
            text += "（" + STALE_MINUTES + "分以上前の古い情報です。最新の状況は気象庁などで確認してください）";
        }
        return text;
    }

    // ----------------------------------------
    // 🗺️ タイル座標（標準 XYZ / slippy map）
    // ----------------------------------------
    public static int[] tileXY(double lat, double lon, int z) {
        double[] f = tileFraction(lat, lon, z);
        return new int[]{(int) Math.floor(f[0]), (int) Math.floor(f[1])};
    }

    // 📝 小数タイル座標（マーカー位置の算出用）
    public static double[] tileFraction(double lat, double lon, int z) {
        double n = Math.pow(2, z);
        double fx = (lon + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(lat);
        double fy = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
        return new double[]{fx, fy};
    }
}
