package aegis.core;

// ========================================
// 🚦 Severity — 重要度（5段階）
// ========================================
// 📌 通知のしきい値判定と比較は level の数値で行う。label は表示と
// 設定値（notify_min_severity）の表記、emoji は通知文の先頭に付く
public enum Severity {
    INFO(0, "INFO", "ℹ️"),
    LOW(1, "LOW", "🟢"),
    MEDIUM(2, "MEDIUM", "🟡"),
    HIGH(3, "HIGH", "🟠"),
    CRITICAL(4, "CRITICAL", "🔴");

    public final int level;   // 📝 比較用の数値（大きいほど重大）
    public final String label; // 📝 表示・設定用の名前
    public final String emoji; // 📝 通知文の先頭絵文字

    Severity(int level, String label, String emoji) {
        this.level = level;
        this.label = label;
        this.emoji = emoji;
    }

    // 🔍 名前から引く。未知の名前は既定の MEDIUM に倒す
    public static Severity fromName(String name) {
        for (Severity s : values()) if (s.label.equalsIgnoreCase(name)) return s;
        return MEDIUM;
    }
}
