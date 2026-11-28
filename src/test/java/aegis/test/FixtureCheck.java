package aegis.test;

import aegis.core.Event;
import aegis.core.Json;
import aegis.core.Location;
import aegis.parse.QuakeParser;
import aegis.parse.TsunamiParser;
import aegis.weather.JmaForecast;
import aegis.weather.OpenMeteo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// ========================================
// 🧪 FixtureCheck — 実データ JSON の解析検証（ネットワーク不要）
// ========================================
// 📌 使い方: java -cp out aegis.test.FixtureCheck <fixtureDir>
// fixtureDir には curl 等で取得した実 JSON を置く:
//   quake.json（内部形式の地震 item 配列）/ tsunami.json /
//   forecast100000.json / openmeteo.json
// （警報・注意報は A2S 経由のため、変換は A2sTest の警報テストで検証する）
public final class FixtureCheck {
    private FixtureCheck() {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length == 0 ? "fixtures" : args[0]);
        if (!Files.isDirectory(dir)) {
            System.out.println("FixtureCheck: no fixtures at " + dir + " - skipped (manual tool, see DESIGN.md)");
            return;
        }
        Location takasaki = new Location("高崎", 36.3219, 139.0023, "100000", "南部");
        int total = 0;

        List<Event> quakes = QuakeParser.parse(Json.parse(Files.readString(dir.resolve("quake.json"))));
        System.out.println("--- 地震 " + quakes.size() + "件 ---");
        quakes.stream().limit(3).forEach(e -> System.out.println(e.formatMessage()));
        total += quakes.size();

        List<Event> tsunami = TsunamiParser.parse(Json.parse(Files.readString(dir.resolve("tsunami.json"))));
        System.out.println("--- 津波 " + tsunami.size() + "件 ---");
        tsunami.forEach(e -> System.out.println(e.formatMessage()));
        total += tsunami.size();

        Event forecast = JmaForecast.parseForecast(Json.parse(Files.readString(dir.resolve("forecast100000.json"))), takasaki);
        System.out.println("--- 気象庁予報 ---");
        System.out.println(forecast == null ? "(なし)" : forecast.formatMessage());
        if (forecast != null) total++;

        Event meteo = OpenMeteo.parseCurrent(Json.parse(Files.readString(dir.resolve("openmeteo.json"))), takasaki);
        System.out.println("--- Open-Meteo ---");
        System.out.println(meteo == null ? "(なし)" : meteo.formatMessage());
        if (meteo != null) total++;

        System.out.println("FIXTURE TOTAL EVENTS: " + total);
        if (quakes.isEmpty() || forecast == null || meteo == null) throw new AssertionError("fixture parse failed");
        System.out.println("FIXTURE CHECK PASSED");
    }
}
