package aegis.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// ========================================
// 📨 Event — 通知・表示の単位となる防災イベント
// ========================================
// 📌 各パーサ（地震・EEW・津波・警報・天気・ニュース）が共通の形に整形し、
// Engine の重複排除・重要度フィルタを経て通知先へ渡る。eventId が重複排除
// の同一視キーになるため、パーサ側で種別ごとの規則に従って必ず設定する
public final class Event {
    public String kind;       // 📝 種別（earthquake / eew / tsunami / weather_warning / weather_forecast / typhoon / news）
    public String title;      // 📝 件名
    public String body;       // 📝 本文
    public Severity severity = Severity.INFO; // 📝 重要度
    public String source = "";      // 📝 出典表記
    public String eventId = "";     // 📝 同一視キー（重複排除用）
    public String occurredAt = "";  // 📝 発生・発表時刻の表示文字列
    public String actionGuide = ""; // 📝 行動ガイド（任意）
    public int maxScale = -1;       // 📝 地震・EEW 用の最大震度コード（quake_min_scale フィルタと EEW 上方修正判定に使う。不明は -1）
    // 🚨 EEW 用の付帯情報（kind="eew" のときだけ意味を持つ）
    public boolean isWarn;        // 📝 警報かどうか（予報のみの EEW は false）
    public boolean isFinal;       // 📝 最終報かどうか
    public int serial = -1;       // 📝 報番号（不明は -1）
    public String reportEventId = ""; // 📝 電文のイベントID
    public boolean isAssumption;  // 📝 仮定震源要素による推定かどうか

    public Event(String kind, String title, String body) {
        this.kind = kind;
        this.title = title;
        this.body = body;
    }

    // ⚙️ eventId が未設定なら、種別・件名・発生時刻・本文冒頭から SHA-256 で決定的に生成する
    public String ensureId() {
        if (eventId == null || eventId.isEmpty()) {
            String base = kind + "|" + title + "|" + occurredAt + "|"
                    + (body.length() > 80 ? body.substring(0, 80) : body);
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(base.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 12; i++) sb.append(String.format("%02x", hash[i]));
                eventId = sb.toString();
            } catch (Exception e) {
                eventId = Integer.toHexString(base.hashCode());
            }
        }
        return eventId;
    }

    // 📝 通知文の整形（重要度絵文字＋件名＋本文＋行動ガイド＋出典）
    public String formatMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(severity.emoji).append(" [").append(severity.label).append("] ").append(title)
                .append("\n").append(body);
        if (actionGuide != null && !actionGuide.isEmpty()) sb.append("\n▶ 行動ガイド: ").append(actionGuide);
        if (source != null && !source.isEmpty()) sb.append("\n(出典: ").append(source).append(" / ").append(occurredAt).append(")");
        return sb.toString();
    }

    // 📝 JSON 表現（汎用 Webhook とダッシュボード API 用）
    public String toJson() {
        return "{"
                + "\"kind\":" + Json.quote(kind) + ","
                + "\"title\":" + Json.quote(title) + ","
                + "\"body\":" + Json.quote(body) + ","
                + "\"severity\":" + Json.quote(severity.label) + ","
                + "\"source\":" + Json.quote(source) + ","
                + "\"event_id\":" + Json.quote(ensureId()) + ","
                + "\"occurred_at\":" + Json.quote(occurredAt) + ","
                + "\"action_guide\":" + Json.quote(actionGuide == null ? "" : actionGuide)
                + "}";
    }
}
