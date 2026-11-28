package aegis.notify;

import aegis.config.AppConfig;
import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Version;
import aegis.net.ProxyEnv;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// ========================================
// 🔔 Notifiers — 通知先（コンソール／ファイル／Webhook／デスクトップ）
// ========================================
// 📌 コンソールとファイルは常時。Discord Webhook・汎用 Webhook・
// デスクトップ通知は設定されたときだけ有効になる。1つの通知先の失敗で
// 他の通知先まで止めないよう、送信は dispatch が通知先ごとに例外を捕捉する
public final class Notifiers {
    private Notifiers() {}

    // 🔔 通知先の共通インタフェース
    public interface Notifier {
        void send(Event ev) throws Exception;
    }

    // ----------------------------------------
    // 🖥️ コンソール
    // ----------------------------------------
    public static final class ConsoleNotifier implements Notifier {
        @Override public void send(Event ev) {
            System.out.println(ev.formatMessage());
            System.out.println("-".repeat(60));
            System.out.flush();
        }
    }

    // ----------------------------------------
    // 📄 ファイル（通知ログへの追記）
    // ----------------------------------------
    public static final class FileNotifier implements Notifier {
        private final Path path; // 📝 追記先のログファイル

        public FileNotifier(Path path) { this.path = path; }

        @Override public synchronized void send(Event ev) throws Exception {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, ev.formatMessage() + "\n" + "-".repeat(60) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    // ----------------------------------------
    // 🌐 Webhook（Discord / 汎用 JSON）
    // ----------------------------------------
    public static final class WebhookNotifier implements Notifier {
        private final String url;  // 📝 送信先 URL（設定からのみ受け取る。ログには出さない）
        private final String kind; // 📝 "discord"（content 形式）か "generic"（Event の JSON）
        private final HttpClient http = ProxyEnv.newClient(Duration.ofSeconds(10));

        public WebhookNotifier(String url, String kind) { this.url = url; this.kind = kind; }

        @Override public void send(Event ev) throws Exception {
            if (url == null || url.isEmpty()) return;
            String payload;
            if ("discord".equals(kind)) {
                String content = ev.formatMessage();
                if (content.length() > 1900) content = content.substring(0, 1900);
                payload = "{\"content\":" + Json.quote(content) + "}";
            } else {
                payload = ev.toJson();
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "AEGIS-Java/" + Version.VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) System.err.println("[notify] webhook HTTP " + res.statusCode());
        }
    }

    // ----------------------------------------
    // 🔔 デスクトップ通知（OS 標準の通知機能。追加モジュール不要の範囲で実装）
    // ----------------------------------------
    public static final class DesktopNotifier implements Notifier {
        @Override public void send(Event ev) {
            List<String> cmd = desktopCommand(detectOs(System.getProperty("os.name", "")),
                    desktopTitle(ev), desktopBody(ev), commandExists("notify-send"));
            if (cmd == null) return; // 対応する通知手段が無い環境では静かにスキップ
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (Exception e) {
                // 🔔 デスクトップ通知の失敗は他の通知先に影響させない
                System.err.println("[notify] desktop failed: " + e.getMessage());
            }
        }
    }

    // 🔔 通知タイトル（純粋関数・テスト対象）
    public static String desktopTitle(Event ev) {
        return ev.severity.emoji + " " + ev.title;
    }

    // 🔔 通知本文: 1行に潰して最大200字に切り詰める（純粋関数・テスト対象）
    public static String desktopBody(Event ev) {
        String oneLine = (ev.body == null ? "" : ev.body).replaceAll("\\s+", " ").strip();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }

    // 🔔 OS 名から通知方式を判定する（純粋関数・テスト対象）: "windows" / "mac" / "linux" / "other"
    public static String detectOs(String osName) {
        String s = (osName == null ? "" : osName).toLowerCase(java.util.Locale.ROOT);
        if (s.contains("windows")) return "windows";
        if (s.contains("mac") || s.contains("darwin")) return "mac";
        if (s.contains("linux") || s.contains("nix") || s.contains("nux")) return "linux";
        return "other";
    }

    // 🔔 OS 別の通知コマンドを組み立てる（純粋関数・テスト対象）。手段が無ければ null。
    // notifySendAvailable は Linux の notify-send の存在チェック結果（他 OS では無視）
    public static List<String> desktopCommand(String os, String title, String body, boolean notifySendAvailable) {
        if (os == null) return null;
        return switch (os) {
            case "windows" -> List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", psToastScript(title, body));
            case "mac" -> List.of("osascript", "-e", appleScript(title, body));
            case "linux" -> notifySendAvailable ? List.of("notify-send", title, body) : null;
            default -> null;
        };
    }

    // 🔔 PowerShell のトースト通知スクリプト（純粋関数・テスト対象）。
    // PowerShell 自身の AUMID を使うことで追加モジュールなしに表示できる定番手法
    public static String psToastScript(String title, String body) {
        String xml = "<toast><visual><binding template=\"ToastText02\">"
                + "<text id=\"1\">" + xmlEscape(title) + "</text>"
                + "<text id=\"2\">" + xmlEscape(body) + "</text>"
                + "</binding></visual></toast>";
        // 📝 -Command 引数内の PowerShell 文字列リテラルとして安全な形にする（シングルクォートを二重化）
        String literal = xml.replace("'", "''");
        return "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null; "
                + "$xml = New-Object Windows.Data.Xml.Dom.XmlDocument; "
                + "$xml.LoadXml('" + literal + "'); "
                + "$toast = [Windows.UI.Notifications.ToastNotification]::new($xml); "
                + "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("
                + "'{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe').Show($toast)";
    }

    // 🧪 AppleScript の display notification（純粋関数・テスト対象）
    public static String appleScript(String title, String body) {
        return "display notification \"" + asEscape(body) + "\" with title \"" + asEscape(title) + "\"";
    }

    public static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    public static String asEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // 📝 コマンドが PATH 上に存在するか（which / where で確認）。失敗時は false
    public static boolean commandExists(String name) {
        String probe = detectOs(System.getProperty("os.name", "")).equals("windows") ? "where" : "which";
        try {
            Process p = new ProcessBuilder(probe, name).redirectErrorStream(true).start();
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // 🔔 設定に応じて通知先を組み立てる（コンソールとファイルは常時、Webhook とデスクトップは設定時のみ）
    public static List<Notifier> build(AppConfig cfg) {
        List<Notifier> list = new ArrayList<>();
        list.add(new ConsoleNotifier());
        list.add(new FileNotifier(AppConfig.root().resolve(cfg.logPath)));
        if (cfg.discordWebhookUrl != null && !cfg.discordWebhookUrl.isEmpty()) list.add(new WebhookNotifier(cfg.discordWebhookUrl, "discord"));
        if (cfg.genericWebhookUrl != null && !cfg.genericWebhookUrl.isEmpty()) list.add(new WebhookNotifier(cfg.genericWebhookUrl, "generic"));
        if (cfg.desktopNotify) list.add(new DesktopNotifier());
        return list;
    }

    // 🔔 通知先ごとに例外を捕捉して全先へ送る
    public static void dispatch(List<Notifier> notifiers, Event ev) {
        for (Notifier n : notifiers) {
            try { n.send(ev); } catch (Exception e) { System.err.println("[notify] " + n.getClass().getSimpleName() + " failed: " + e.getMessage()); }
        }
    }
}
