package aegis.news;

import aegis.core.Event;
import aegis.core.Severity;
import aegis.net.Http;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ========================================
// 📰 NewsFeed — NHK RSS ニュースの取得と解析
// ========================================
// 📌 主要（cat0）と社会（cat1）の2フィードをマージし、防災キーワードに
// 該当する記事を優先して最大8件にする。ブリーフィングでは上位3件を
// 「📰 防災ニュース」として末尾に付ける
public final class NewsFeed {
    private NewsFeed() {}

    // 📰 ニュース1件
    public record NewsItem(String title, String summary, String link, String time) {}

    // 🔍 防災関連とみなすキーワード
    public static final List<String> DISASTER_KEYWORDS = List.of(
            "地震", "津波", "警報", "特別警報", "台風", "大雨", "洪水", "土砂", "避難", "震度",
            "噴火", "熱中症", "暴風", "大雪", "竜巻", "線状降水帯");

    // 📝 RSS 用の最小限の実体参照デコード（名前付きと数値参照のみ対応）
    public static String decodeEntities(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = s.substring(i + 1, semi);
                    String rep = switch (ent) {
                        case "lt" -> "<";
                        case "gt" -> ">";
                        case "quot" -> "\"";
                        case "apos" -> "'";
                        case "nbsp" -> " ";
                        case "amp" -> "&";
                        default -> null;
                    };
                    if (rep == null && ent.startsWith("#")) {
                        try {
                            int cp = ent.startsWith("#x") || ent.startsWith("#X")
                                    ? Integer.parseInt(ent.substring(2), 16)
                                    : Integer.parseInt(ent.substring(1));
                            rep = String.valueOf((char) cp);
                        } catch (Exception ignored) {}
                    }
                    if (rep != null) {
                        out.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // 📝 description の HTML を除去し、maxLen 字程度に切り詰める（途中で切れたら「…」）
    public static String summarizeDesc(String desc, int maxLen) {
        String t = desc == null ? "" : desc;
        t = t.replaceAll("<[^>]*>", "");
        t = decodeEntities(t);
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() > maxLen) t = t.substring(0, maxLen) + "…";
        return t;
    }

    // 📝 RSS は決まったタグだけ読めれば十分なため、XML パーサを使わずタグ範囲を切り出す（CDATA も剥がす）
    public static String xmlTag(String block, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int i = block.indexOf(open);
        if (i < 0) return "";
        int j = block.indexOf(close, i + open.length());
        if (j < 0) return "";
        String v = block.substring(i + open.length(), j).trim();
        if (v.startsWith("<![CDATA[") && v.endsWith("]]>")) v = v.substring(9, v.length() - 3);
        return decodeEntities(v).trim();
    }

    // 📝 pubDate（RFC 822, +0900）→ JST の "H:mm"
    public static String rssTime(String pubDate) {
        if (pubDate == null || pubDate.isEmpty()) return "";
        try {
            ZonedDateTime z = ZonedDateTime.parse(pubDate.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
            return z.withZoneSameInstant(ZoneId.of("Asia/Tokyo")).format(DateTimeFormatter.ofPattern("H:mm"));
        } catch (Exception e) {
            Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2}):\\d{2}").matcher(pubDate);
            return m.find() ? Integer.parseInt(m.group(1)) + ":" + m.group(2) : "";
        }
    }

    // 🔍 RSS フィード1本を記事一覧にする
    public static List<NewsItem> parseRss(String xml) {
        List<NewsItem> items = new ArrayList<>();
        if (xml == null) return items;
        int pos = 0;
        while (true) {
            int i = xml.indexOf("<item>", pos);
            if (i < 0) break;
            int j = xml.indexOf("</item>", i);
            if (j < 0) break;
            String block = xml.substring(i, j);
            String title = xmlTag(block, "title");
            if (!title.isEmpty()) {
                items.add(new NewsItem(title,
                        summarizeDesc(xmlTag(block, "description"), 80),
                        xmlTag(block, "link"),
                        rssTime(xmlTag(block, "pubDate"))));
            }
            pos = j + "</item>".length();
        }
        return items;
    }

    // ⌨️ cat0（主要）+ cat1（社会）をマージし、防災キーワード該当を優先して最大8件にする
    public static List<NewsItem> parseNews(String xml0, String xml1) {
        Map<String, NewsItem> byLink = new LinkedHashMap<>();
        for (NewsItem n : parseRss(xml0)) byLink.putIfAbsent(n.link().isEmpty() ? n.title() : n.link(), n);
        for (NewsItem n : parseRss(xml1)) byLink.putIfAbsent(n.link().isEmpty() ? n.title() : n.link(), n);
        List<NewsItem> disaster = new ArrayList<>();
        List<NewsItem> general = new ArrayList<>();
        for (NewsItem n : byLink.values()) {
            String hay = n.title() + n.summary();
            boolean hit = false;
            for (String kw : DISASTER_KEYWORDS) if (hay.contains(kw)) { hit = true; break; }
            (hit ? disaster : general).add(n);
        }
        List<NewsItem> out = new ArrayList<>(disaster);
        for (NewsItem n : general) {
            if (out.size() >= 8) break;
            out.add(n);
        }
        return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
    }

    // 🌐 2フィードを取得してマージする（社会フィードの失敗は無視する）
    public static List<NewsItem> fetchNews() throws Exception {
        String x0 = Http.fetchText("https://www3.nhk.or.jp/rss/news/cat0.xml");
        String x1 = "";
        try { x1 = Http.fetchText("https://www3.nhk.or.jp/rss/news/cat1.xml"); } catch (Exception ignored) {}
        return parseNews(x0, x1);
    }

    // 📰 ブリーフィング末尾用のニュースイベント（上位3件）。ニュースが無ければ null
    public static Event fetchNewsEvent() throws Exception {
        List<NewsItem> items = fetchNews();
        if (items.isEmpty()) return null;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < Math.min(3, items.size()); i++) {
            NewsItem n = items.get(i);
            if (i > 0) body.append("\n");
            body.append("・").append(n.title());
            if (!n.time().isEmpty()) body.append("（").append(n.time()).append("）");
            if (!n.summary().isEmpty()) body.append("\n　").append(n.summary());
        }
        Event ev = new Event("news", "📰 防災ニュース", body.toString());
        ev.severity = Severity.INFO;
        ev.source = "NHKニュース";
        ev.occurredAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"));
        ev.ensureId();
        return ev;
    }
}
