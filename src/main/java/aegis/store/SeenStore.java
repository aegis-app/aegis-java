package aegis.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ========================================
// 📜 SeenStore — ファイルベースの永続化（重複排除と履歴）
// ========================================
// 📌 依存ゼロを守るため SQLite ではなく TSV ファイルで代替する。
// 肥大化対策: seen は記録日を持ち、保持期間（30日）を過ぎた行を起動時と
// 日付が変わったタイミングで剪定する。history は上限行数を超えたら
// 古い行を切り捨てる
public final class SeenStore {
    public static final int SEEN_RETENTION_DAYS = 30;   // 📝 seen の保持日数
    public static final int HISTORY_MAX_LINES = 10_000; // 📝 history の上限行数
    public static final int HISTORY_KEEP_LINES = 5_000; // 📝 ローテーション時に残す行数
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path seenFile;    // 📝 重複排除ファイル（seen.tsv）
    private final Path historyFile; // 📝 履歴ファイル（history.tsv）
    private final Map<String, String[]> seen = new LinkedHashMap<>(); // 📝 eventId → 行
    private int historyLines = 0;   // 📝 history の現在行数
    private LocalDate lastPruneDate; // 📝 最後に剪定した日付

    public SeenStore(Path dir) throws Exception {
        Files.createDirectories(dir);
        this.seenFile = dir.resolve("seen.tsv");
        this.historyFile = dir.resolve("history.tsv");
        if (Files.exists(seenFile)) {
            LocalDate today = LocalDate.now();
            boolean dirty = false;
            for (String line : Files.readAllLines(seenFile, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 1 || parts[0].isEmpty()) continue;
                LocalDate recorded = parseDate(parts.length >= 5 ? parts[4] : "");
                if (recorded == null) {
                    // 📅 旧形式（記録日なし）は移行として今日の日付を付けて保持する
                    recorded = today;
                    dirty = true;
                }
                String[] row = {parts[0],
                        parts.length > 1 ? parts[1] : "",
                        parts.length > 2 ? parts[2] : "",
                        parts.length > 3 ? parts[3] : "",
                        recorded.format(DATE_FMT)};
                seen.put(parts[0], row);
            }
            if (pruneSeen(today) || dirty) rewriteSeen();
            lastPruneDate = today;
        }
        if (Files.exists(historyFile)) {
            historyLines = Files.readAllLines(historyFile, StandardCharsets.UTF_8).size();
        }
    }

    // 🔄 TSV の列・行が壊れないよう、タブと改行を空白に置換する（書き込み前の整形）
    private static String sanitize(String v) {
        return v == null ? "" : v.replace("\t", " ").replace("\n", " ").replace("\r", " ");
    }

    // 🧪 "yyyy-MM-dd" をパースする。失敗したら null（テスト対象）
    public static LocalDate parseDate(String s) {
        if (s == null || s.length() < 10) return null;
        try { return LocalDate.parse(s.substring(0, 10), DATE_FMT); }
        catch (Exception e) { return null; }
    }

    // 🧪 記録日が保持期間を超えているか（パースできない場合は false = 残す）。テスト対象
    public static boolean seenExpired(String dateStr, LocalDate today) {
        LocalDate d = parseDate(dateStr);
        return d != null && d.isBefore(today.minusDays(SEEN_RETENTION_DAYS));
    }

    // 📝 保持期間を過ぎた seen をメモリから落とす。落としたら true
    private boolean pruneSeen(LocalDate today) {
        return seen.entrySet().removeIf(e -> seenExpired(e.getValue().length > 4 ? e.getValue()[4] : "", today));
    }

    // 📅 日付が変わっていたら剪定する（常駐プロセスでも定期的に効くように）
    private void pruneSeenIfNeeded() {
        LocalDate today = LocalDate.now();
        if (lastPruneDate == null || !today.equals(lastPruneDate)) {
            lastPruneDate = today;
            if (pruneSeen(today)) rewriteSeen();
        }
    }

    private void rewriteSeen() {
        StringBuilder sb = new StringBuilder();
        for (String[] row : seen.values()) {
            sb.append(String.join("\t",
                    sanitize(row[0]), sanitize(row[1]), sanitize(row[2]), sanitize(row[3]),
                    row.length > 4 ? row[4] : LocalDate.now().format(DATE_FMT))).append("\n");
        }
        try {
            Files.writeString(seenFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[store] seen rewrite failed: " + e.getMessage());
        }
    }

    // 🔍 通知済みかどうか
    public synchronized boolean isSeen(String eventId) {
        return seen.containsKey(eventId);
    }

    // 🚨 EEW の通知済み最大推定震度を返す。EEW は基本IDに加えて「基本ID@s<推定震度>」の
    // キーでも記録されており、その最大値を返す。一度も記録がなければ null。
    // 基本IDのみの旧記録しかない場合は -1（震度不明として扱う）
    public synchronized Integer eewSeenMaxScale(String baseEventId) {
        String prefix = baseEventId + "@s";
        Integer best = null;
        for (String key : seen.keySet()) {
            if (key.startsWith(prefix)) {
                try {
                    int v = Integer.parseInt(key.substring(prefix.length()));
                    best = best == null ? v : Math.max(best, v);
                } catch (Exception ignored) {}
            }
        }
        if (best != null) return best;
        return seen.containsKey(baseEventId) ? -1 : null;
    }

    // 📝 通知済みとして記録する（既に記録済みなら何もしない）
    public synchronized void markSeen(String eventId, String kind, String title, String severity) {
        if (seen.containsKey(eventId)) return;
        pruneSeenIfNeeded();
        String date = LocalDate.now().format(DATE_FMT);
        String[] row = {eventId, kind, title, severity, date};
        seen.put(eventId, row);
        try {
            Files.writeString(seenFile, String.join("\t", sanitize(eventId), sanitize(kind), sanitize(title), sanitize(severity), date) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("[store] seen write failed: " + e.getMessage());
        }
    }

    // 📜 履歴へ1行追加する（上限を超えたらローテーション）
    public synchronized void addHistory(String eventId, String kind, String title, String body, String severity) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String line = String.join("\t", ts, sanitize(eventId), sanitize(kind), sanitize(severity), sanitize(title), sanitize(body)) + "\n";
        try {
            Files.writeString(historyFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            historyLines++;
            if (historyLines > HISTORY_MAX_LINES) rotateHistory();
        } catch (Exception e) {
            System.err.println("[store] history write failed: " + e.getMessage());
        }
    }

    // 📝 history が上限を超えたら、直近 HISTORY_KEEP_LINES 行だけを残して切り詰める
    private void rotateHistory() {
        try {
            List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - HISTORY_KEEP_LINES);
            List<String> keep = new ArrayList<>(lines.subList(from, lines.size()));
            Files.writeString(historyFile, keep.isEmpty() ? "" : String.join("\n", keep) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            historyLines = keep.size();
        } catch (Exception e) {
            System.err.println("[store] history rotate failed: " + e.getMessage());
        }
    }

    // 📜 直近の履歴を新しい順に返す（history コマンドとダッシュボード用）
    public synchronized List<Map<String, String>> recentHistory(int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        try {
            if (!Files.exists(historyFile)) return rows;
            List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && rows.size() < limit; i--) {
                String[] p = lines.get(i).split("\t", -1);
                if (p.length < 6) continue;
                Map<String, String> m = new LinkedHashMap<>();
                m.put("created_at", p[0]);
                m.put("event_id", p[1]);
                m.put("kind", p[2]);
                m.put("severity", p[3]);
                m.put("title", p[4]);
                m.put("body", p[5]);
                rows.add(m);
            }
        } catch (Exception e) {
            System.err.println("[store] history read failed: " + e.getMessage());
        }
        return rows;
    }
}
