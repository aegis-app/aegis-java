package aegis.weather;

import java.util.List;

// ========================================
// 📊 WeatherTables — ダッシュボード用天気テーブルの行データ
// ========================================
// 📌 ダッシュボード（serve）の表描画が使う不変の行レコード群。生成は
// OpenMeteo.parseWeatherTables が担う
public record WeatherTables(List<HourRow> hourly, List<DayRow> weekly, List<PopRow> pops) {

    // 🕐 時間別予報の1行。popMax は3時間区間の最大降水確率、precip は区間の降水量合計（mm）
    public record HourRow(String label, String icon, String desc, String temp, String pop, String popMax, String precip) {
        // 📝 後方互換: 5引数コンストラクタ（popMax=pop、precip は "—"）
        public HourRow(String label, String icon, String desc, String temp, String pop) {
            this(label, icon, desc, temp, pop, pop, "—");
        }
    }

    // 📅 週間予報の1行。precip はその日の降水量（mm）
    public record DayRow(String date, String icon, String desc, String temp, String pop, String precip) {
        public DayRow(String date, String icon, String desc, String temp, String pop) {
            this(date, icon, desc, temp, pop, "—");
        }
    }

    // 🌧️ 気象庁降水確率の1行
    public record PopRow(String date, String label, String pop) {}
}
