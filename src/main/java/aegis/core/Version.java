package aegis.core;

// ========================================
// 🔖 Version — バージョン定数
// ========================================
// 📌 作り直しに伴いバージョンは 1.0.0 に振り直した（判断記録は DESIGN.md）。
// HTTP の User-Agent や自己診断の表示など、複数モジュールから参照される
// ため、循環参照を避けて独立した定数クラスに置く
public final class Version {
    private Version() {}

    public static final String VERSION = "1.0.0"; // 📝 現在のバージョン
}
