package aegis.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ========================================
// 🧩 Json — 軽量 JSON パーサ／生成（外部依存ゼロ）
// ========================================
// 📌 このプロジェクトは Java 標準ライブラリのみで組み立てる方針のため、
// JSON の読み書きは本クラスが担う。パース結果は Map / List / String /
// Long / Double / Boolean / null の素の型で返し、アクセスは静的ヘルパー
// （obj / arr / str / dbl / bool）経由に統一する
public final class Json {
    private final String text; // 📝 パース対象の文字列全体
    private int pos;           // 📝 現在の読み取り位置

    private Json(String text) {
        this.text = text;
    }

    // ----------------------------------------
    // 🔍 パース
    // ----------------------------------------
    public static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        return value;
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private Object readValue() {
        if (pos >= text.length()) return null;
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBoolean();
            case 'n' -> { pos += 4; yield null; }
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // '{' を消費
        skipWhitespace();
        if (pos < text.length() && text.charAt(pos) == '}') { pos++; return map; }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == ':') pos++;
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == ',') { pos++; continue; }
            if (pos < text.length() && text.charAt(pos) == '}') pos++;
            break;
        }
        return map;
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // '[' を消費
        skipWhitespace();
        if (pos < text.length() && text.charAt(pos) == ']') { pos++; return list; }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == ',') { pos++; continue; }
            if (pos < text.length() && text.charAt(pos) == ']') pos++;
            break;
        }
        return list;
    }

    private String readString() {
        StringBuilder sb = new StringBuilder();
        pos++; // 開始の '"' を消費
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') break;
            if (c == '\\' && pos < text.length()) {
                char esc = text.charAt(pos++);
                switch (esc) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '/' -> sb.append('/');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case 'u' -> {
                        if (pos + 4 <= text.length()) {
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Boolean readBoolean() {
        if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
        pos += 5; // "false" を消費
        return Boolean.FALSE;
    }

    private Number readNumber() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        String num = text.substring(start, pos);
        try {
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            return Long.parseLong(num);
        } catch (Exception e) {
            try { return Double.parseDouble(num); } catch (Exception e2) { return 0; }
        }
    }

    // ----------------------------------------
    // 🔧 アクセスヘルパー（型が違う場合は空／既定値に倒す）
    // ----------------------------------------
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static String str(Object value, String fallback) {
        String v = str(value);
        return v.isEmpty() ? fallback : v;
    }

    public static double dbl(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(str(value)); } catch (Exception e) { return fallback; }
    }

    public static boolean bool(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(str(value));
    }

    // ----------------------------------------
    // ✏️ 生成ヘルパー
    // ----------------------------------------
    public static String escape(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }
}
