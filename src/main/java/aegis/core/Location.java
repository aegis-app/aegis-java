package aegis.core;

import java.util.Map;

// ========================================
// 📍 Location — 監視地点
// ========================================
// 📌 city は市区町村名（例: 高崎市・さいたま市）。地震の観測点（addr）との
// 照合による「市区町村の実測震度」表示に使う。未設定（空文字）の地点では
// 市区町村照合を行わない
public record Location(
        String name,        // 📝 地点の表示名（例: 高崎）
        double lat,         // 📝 緯度
        double lon,         // 📝 経度
        String jmaPrefCode, // 📝 気象庁の都道府県コード（例: 100000）
        String jmaAreaName, // 📝 気象庁の一次細分区域名（例: 南部）
        String city) {      // 📝 市区町村名（観測点照合用、任意）

    // 📍 後方互換: 市区町村なしの5引数コンストラクタ
    public Location(String name, double lat, double lon, String jmaPrefCode, String jmaAreaName) {
        this(name, lat, lon, jmaPrefCode, jmaAreaName, "");
    }

    // 🔍 config.json の locations 要素（snake_case キー）から組み立てる
    public static Location fromMap(Map<String, Object> m) {
        return new Location(
                Json.str(m.get("name")),
                Json.dbl(m.get("lat"), 0),
                Json.dbl(m.get("lon"), 0),
                Json.str(m.get("jma_pref_code")),
                Json.str(m.get("jma_area_name")),
                Json.str(m.get("city")));
    }
}
