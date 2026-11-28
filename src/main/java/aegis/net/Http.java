package aegis.net;

import aegis.core.Json;
import aegis.core.Version;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

// ========================================
// 🌐 Http — HTTP 取得基盤（リトライ・gzip 展開）
// ========================================
// 📌 リトライの意味論: ネットワークエラー／タイムアウトと HTTP 5xx の
// ときだけ最大3回まで試行し、HTTP 4xx は即失敗する。待機は 1秒→3秒
public final class Http {
    private Http() {}

    private static final HttpClient CLIENT = ProxyEnv.newClient(Duration.ofSeconds(15));
    private static final String UA = "AEGIS-Java/" + Version.VERSION + " (autonomous disaster assistant; +local)";

    // ⚠️ HTTP ステータス由来の失敗。4xx か 5xx かでリトライ可否を判定するために使う
    public static class HttpStatusException extends Exception {
        public final int status; // 📝 HTTP ステータスコード

        public HttpStatusException(int status, String url) {
            super("HTTP " + status + " for " + url);
            this.status = status;
        }
    }

    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws Exception;
    }

    // 🔁 リトライ待機（ミリ秒）。要素数がリトライ回数（= 最大試行回数 - 1）になる。
    // テストでは {0, 0} に差し替えて待たずに分岐だけを検証する
    public static volatile long[] retryDelaysMs = {1000L, 3000L};

    // 🧪 一時的な失敗へのリトライ（テスト対象）
    public static <T> T withRetry(IoSupplier<T> op) throws Exception {
        Exception last = null;
        long[] delays = retryDelaysMs;
        for (int attempt = 0; attempt <= delays.length; attempt++) {
            try {
                return op.get();
            } catch (HttpStatusException e) {
                if (e.status < 500) throw e; // 4xx は即失敗
                last = e;
            } catch (Exception e) {
                last = e;
            }
            if (attempt < delays.length) Thread.sleep(delays[attempt]);
        }
        throw last != null ? last : new IllegalStateException("fetch failed");
    }

    private static byte[] fetchBytes(String url, String accept) throws Exception {
        return withRetry(() -> fetchBytesOnce(url, accept));
    }

    private static byte[] fetchBytesOnce(String url, String accept) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Accept", accept)
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
        HttpResponse<byte[]> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) throw new HttpStatusException(res.statusCode(), url);
        byte[] body = res.body();
        // 📝 HttpClient は gzip を自動展開しないため、Content-Encoding を見て手動で展開する
        if ("gzip".equalsIgnoreCase(res.headers().firstValue("Content-Encoding").orElse(""))) {
            try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(body);
                 java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(bais)) {
                body = gis.readAllBytes();
            }
        }
        return body;
    }

    // 🌐 JSON を取得してパース済みの形で返す
    public static Object fetchJson(String url) throws Exception {
        return Json.parse(new String(fetchBytes(url, "application/json"), StandardCharsets.UTF_8));
    }

    // 🌐 テキストを取得する（RSS など）
    public static String fetchText(String url) throws Exception {
        return new String(fetchBytes(url, "*/*"), StandardCharsets.UTF_8);
    }
}
