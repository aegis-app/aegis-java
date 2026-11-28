package aegis.app;

import aegis.a2s.A2sClient;
import aegis.config.AppConfig;
import aegis.core.Event;
import aegis.core.Location;
import aegis.weather.JmaForecast;
import aegis.weather.OpenMeteo;

import java.util.List;
import java.util.stream.Collectors;

// ========================================
// 💬 Assistant — 自然文 Q&A
// ========================================
// 📌 キーワードで問い合わせ先を振り分け、最初に当たったものだけ答える。
// 地震・津波・警報の回答は A2S からのみ取る。A2S 未設定時はその旨を答え、
// 天気予報など A2S 対象外の質問には通常どおり答える
public final class Assistant {
    private Assistant() {}

    // 📍 質問文に含まれる地点名で対象地点を選ぶ。含まれなければ先頭の地点
    // （一覧が空なら高崎の既定値）
    private static Location pickLocation(String text, List<Location> locations) {
        for (Location loc : locations) if (text.contains(loc.name())) return loc;
        return locations.isEmpty() ? new Location("高崎", 36.3219, 139.0023, "100000", "南部") : locations.get(0);
    }

    public static String answer(String question, AppConfig cfg) {
        List<Location> locations = cfg.locations;
        String q = question == null ? "" : question.strip();
        Location loc = pickLocation(q, locations);
        try {
            if (q.contains("津波")) {
                if (!cfg.a2sEnabled()) return "津波情報は A.A.S 未設定のため確認できません（a2s_url / a2s_token を設定してください）。強い地震を感じたらすぐ高台へ。";
                List<Event> evs = A2sClient.fetchTsunami(cfg.a2sUrl, cfg.a2sToken, 5);
                if (evs.isEmpty()) return "現在、発表されている津波警報・注意報はありません（A.A.Sで確認）。海岸付近でも通常通りで問題ありませんが、強い地震を感じたらすぐ高台へ。";
                return evs.stream().map(Event::formatMessage).collect(Collectors.joining("\n---\n"));
            }
            if (q.contains("地震") || q.contains("揺れ") || q.contains("震度")) {
                if (!cfg.a2sEnabled()) return "地震情報は A.A.S 未設定のため取得できません（a2s_url / a2s_token を設定してください）。";
                List<Event> evs = A2sClient.fetchQuakes(cfg.a2sUrl, cfg.a2sToken, 5, locations);
                if (evs.isEmpty()) return "最近の地震情報は取得できませんでした。";
                return "最近の地震（新しい順・最大5件）:\n" + evs.stream().map(Event::formatMessage).collect(Collectors.joining("\n---\n"));
            }
            if (q.contains("警報") || q.contains("注意報")) {
                if (!cfg.a2sEnabled()) return "警報・注意報は A.A.S 未設定のため確認できません（a2s_url / a2s_token を設定してください）。";
                List<Event> evs = A2sClient.fetchWarnings(cfg.a2sUrl, cfg.a2sToken, List.of(loc));
                if (evs.isEmpty()) return loc.name() + "周辺に、現在発表されている警報・注意報はありません（A.A.Sで確認）。";
                return evs.stream().map(Event::formatMessage).collect(Collectors.joining("\n---\n"));
            }
            if (q.contains("天気") || q.contains("雨") || q.contains("気温") || q.contains("傘") || q.contains("明日") || q.contains("今日")) {
                java.util.List<String> parts = new java.util.ArrayList<>();
                try { Event ev = JmaForecast.fetchForecast(loc); if (ev != null) parts.add(ev.formatMessage()); } catch (Exception e) { parts.add("（JMA予報 取得失敗: " + e.getMessage() + "）"); }
                try { Event ev = OpenMeteo.fetchCurrent(loc); if (ev != null) parts.add(ev.formatMessage()); } catch (Exception e) { parts.add("（Open-Meteo 取得失敗: " + e.getMessage() + "）"); }
                List<Event> warns = cfg.a2sEnabled() ? A2sClient.fetchWarnings(cfg.a2sUrl, cfg.a2sToken, List.of(loc)) : List.of();
                if (!warns.isEmpty()) parts.add("⚠ 警報・注意報:\n" + warns.stream().map(Event::formatMessage).collect(Collectors.joining("\n")));
                else if (cfg.a2sEnabled()) parts.add(loc.name() + "周辺に警報・注意報は出ていません。");
                else parts.add("警報・注意報は A.A.S 未設定のため確認できません。");
                if (q.contains("傘")) parts.add("▶ 傘の目安: 降水確率 最大60%以上なら傘推奨、80%以上なら大きめの傘が安心です（上の数値を参照）。");
                return String.join("\n---\n", parts);
            }
        } catch (Exception e) {
            return "情報の取得に失敗しました: " + e.getMessage();
        }
        // 📝 フォールバック: 総合（現在の気象＋最新の地震＋聞き方の案内）
        java.util.List<String> parts = new java.util.ArrayList<>();
        try { Event ev = OpenMeteo.fetchCurrent(loc); if (ev != null) parts.add(ev.formatMessage()); } catch (Exception ignored) {}
        if (cfg.a2sEnabled()) {
            try { List<Event> quakes = A2sClient.fetchQuakes(cfg.a2sUrl, cfg.a2sToken, 1, locations); if (!quakes.isEmpty()) parts.add("最新の地震:\n" + quakes.get(0).formatMessage()); } catch (Exception ignored) {}
        }
        parts.add("聞き方: 「高崎の天気は？」「大宮に警報は？」「最近の地震は？」「津波は？」のように聞いてください。");
        return String.join("\n---\n", parts);
    }
}
