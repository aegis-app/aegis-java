package aegis.config;

import aegis.core.Json;
import aegis.core.Location;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// ========================================
// ⚙️ AppConfig — config.json の読み込み
// ========================================
// 📌 設定ファイルの形式は Python 版 AEGIS と互換（同じ config.json を
// そのまま使える）。config.json 自体は秘密情報（Webhook URL・A2S
// トークン）を含むため Git 管理外とする
// 📡 A2S 連携: 地震・津波・EEW・気象警報/注意報・台風の唯一の情報源。
// a2s_url と a2s_token の両方が指定されたときだけ有効で、既定は無効
// （無効時は A2S 対象の情報は出ない仕様）。環境変数 A2S_URL / A2S_TOKEN
// があれば config.json より優先する。トークンはここ（設定）からのみ読み、
// コードやログには一切出さない
public final class AppConfig {
    public List<Location> locations = new ArrayList<>(); // 📝 監視地点
    public int pollSecondsQuake = 20;      // 📝 地震・津波のポーリング間隔（秒）
    public int pollSecondsEew = 10;        // 📝 EEW のポーリング間隔（秒）
    public int pollSecondsWeather = 600;   // 📝 気象警報のポーリング間隔（秒）
    public boolean desktopNotify = false;  // 📝 デスクトップ通知の有効化
    public String a2sUrl = "";             // 📡 A2S のベース URL
    public String a2sToken = "";           // 📡 A2S の専用トークン（設定からのみ読む）
    public String notifyMinSeverity = "MEDIUM"; // 📝 通知する最低重要度
    public int quakeMinScale = 30;         // 📝 通知する地震の最低震度コード
    public String discordWebhookUrl = "";  // 📝 Discord Webhook URL（空なら無効）
    public String genericWebhookUrl = "";  // 📝 汎用 Webhook URL（空なら無効）
    public String morningBriefing = "07:00"; // 📝 朝ブリーフィング時刻
    public String eveningBriefing = "21:00"; // 📝 夜ブリーフィング時刻
    public String storeDir = "data";       // 📝 永続化ファイルの置き場所
    public String logPath = "data/alerts.log"; // 📝 通知ログのパス

    // 📂 基準ディレクトリ（jar 実行時・IDE 実行時ともカレントディレクトリ）
    public static Path root() {
        return Path.of(System.getProperty("user.dir"));
    }

    // 🧪 ポーリング間隔の下限クランプ（テスト対象）。短すぎる間隔で外部 API を叩き続けないため
    public static int clampMin(int value, int min) {
        return Math.max(min, value);
    }

    // 📡 A2S が有効かどうか（テスト対象）: URL とトークンの両方が必要
    public boolean a2sEnabled() {
        return !a2sUrl.isBlank() && !a2sToken.isBlank();
    }

    // 🧪 A2S 設定の解決（テスト対象）: 環境変数が空でなければ config.json の値より優先する。
    // 戻り値は {url, token}
    public static String[] resolveA2s(String cfgUrl, String cfgToken, String envUrl, String envToken) {
        String url = envUrl != null && !envUrl.isBlank() ? envUrl.trim() : (cfgUrl == null ? "" : cfgUrl.trim());
        String token = envToken != null && !envToken.isBlank() ? envToken.trim() : (cfgToken == null ? "" : cfgToken.trim());
        return new String[]{url, token};
    }

    // 📝 config.json が無ければ既定値のまま返し、読み込みに失敗しても既定値で続行する
    public static AppConfig load() {
        AppConfig cfg = new AppConfig();
        cfg.locations.add(new Location("高崎", 36.3219, 139.0023, "100000", "南部", "高崎市"));
        cfg.locations.add(new Location("大宮", 35.9064, 139.6239, "110000", "南部", "さいたま市"));
        Path p = root().resolve("config.json");
        // 📡 config.json が無くても環境変数だけで A2S を有効化できるようにする
        if (!Files.exists(p)) {
            applyEnvA2s(cfg, "", "");
            return cfg;
        }
        try {
            Map<String, Object> m = Json.obj(Json.parse(Files.readString(p)));
            if (m.isEmpty()) {
                applyEnvA2s(cfg, "", "");
                return cfg;
            }
            List<Object> locs = Json.arr(m.get("locations"));
            if (!locs.isEmpty()) {
                cfg.locations.clear();
                for (Object o : locs) cfg.locations.add(Location.fromMap(Json.obj(o)));
            }
            cfg.pollSecondsQuake = clampMin((int) Json.dbl(m.get("poll_seconds_quake"), cfg.pollSecondsQuake), 5);
            cfg.pollSecondsEew = clampMin((int) Json.dbl(m.get("poll_seconds_eew"), cfg.pollSecondsEew), 5);
            cfg.pollSecondsWeather = clampMin((int) Json.dbl(m.get("poll_seconds_weather"), cfg.pollSecondsWeather), 60);
            cfg.desktopNotify = Json.bool(m.get("desktop_notify"));
            applyEnvA2s(cfg, Json.str(m.get("a2s_url")), Json.str(m.get("a2s_token")));
            cfg.notifyMinSeverity = Json.str(m.get("notify_min_severity"), cfg.notifyMinSeverity);
            cfg.quakeMinScale = (int) Json.dbl(m.get("quake_min_scale"), cfg.quakeMinScale);
            cfg.discordWebhookUrl = Json.str(m.get("discord_webhook_url"));
            cfg.genericWebhookUrl = Json.str(m.get("generic_webhook_url"));
            cfg.morningBriefing = Json.str(m.get("morning_briefing"), cfg.morningBriefing);
            cfg.eveningBriefing = Json.str(m.get("evening_briefing"), cfg.eveningBriefing);
            // 📝 Python 版との互換: db_path があればその親を storeDir にする
            String db = Json.str(m.get("db_path"));
            if (!db.isEmpty() && db.contains("/")) cfg.storeDir = db.substring(0, db.lastIndexOf('/'));
            cfg.logPath = Json.str(m.get("log_path"), cfg.logPath);
        } catch (Exception e) {
            System.err.println("[config] 読み込み失敗、デフォルトを使用: " + e.getMessage());
        }
        return cfg;
    }

    // 📡 config.json の値に環境変数（A2S_URL / A2S_TOKEN）を上書き適用する
    private static void applyEnvA2s(AppConfig cfg, String cfgUrl, String cfgToken) {
        String[] a2s = resolveA2s(cfgUrl, cfgToken, System.getenv("A2S_URL"), System.getenv("A2S_TOKEN"));
        cfg.a2sUrl = a2s[0];
        cfg.a2sToken = a2s[1];
    }
}
