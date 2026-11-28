package aegis.core;

// ========================================
// 🌏 Scale — 気象庁震度階級コードの換算
// ========================================
// 📌 震度は整数コードで扱う（10=震度1、45=5弱、50=5強、55=6弱、60=6強、
// 70=震度7。-1 は「震度不明」のセンチネル）
public final class Scale {
    private Scale() {}

    // 🔢 震度コード → 表示文字列。0 以下は「不明」
    public static String toStr(Object scale) {
        int s;
        try { s = (int) Math.round(Json.dbl(scale, 0)); } catch (Exception e) { return Json.str(scale); }
        if (s <= 0) return "不明";
        return switch (s) {
            case 10 -> "1";
            case 20 -> "2";
            case 30 -> "3";
            case 40 -> "4";
            case 45 -> "5弱";
            case 50 -> "5強";
            case 55 -> "6弱";
            case 60 -> "6強";
            case 70 -> "7";
            default -> String.valueOf(s);
        };
    }

    // 🚦 震度とマグニチュードのどちらか一方が閾値に達すれば、その重要度にする
    public static Severity toSeverity(Object scale, double magnitude) {
        int s;
        try { s = (int) Math.round(Json.dbl(scale, 0)); } catch (Exception e) { s = 0; }
        if (s >= 45 || magnitude >= 6.0) return Severity.CRITICAL;
        if (s >= 40 || magnitude >= 5.0) return Severity.HIGH;
        if (s >= 30 || magnitude >= 4.5) return Severity.MEDIUM;
        if (s >= 20) return Severity.LOW;
        return Severity.INFO;
    }
}
