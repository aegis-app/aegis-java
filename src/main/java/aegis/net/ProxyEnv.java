package aegis.net;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

// ========================================
// 🔌 ProxyEnv — 環境変数プロキシ対応の HttpClient 生成
// ========================================
// 📌 Java の HttpClient は環境変数 HTTPS_PROXY を自動で読まないため、
// Python 版（urllib）と同じ挙動になるようここで橋渡しする。認証情報は
// 環境変数から実行時に読むだけで、コードやログには一切出さない
public final class ProxyEnv {
    static {
        // 🌐 HTTPS トンネル（CONNECT）でも Basic 認証を使えるようにする。
        // Java は既定でトンネル時の Basic 認証を無効化しているため、認証付きプロキシで必要
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
    }

    private ProxyEnv() {}

    // 🌐 プロキシ環境変数を優先順に探す（HTTPS 系を最優先し、大文字・小文字の両方を見る）
    private static String envProxy() {
        for (String key : new String[]{"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"}) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    // 🔌 ローカル宛てはプロキシを経由しない（NO_PROXY 扱い）
    private static boolean isLocal(String host) {
        return host == null || host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    // 🌐 環境変数プロキシを反映した HttpClient を作る。プロキシが無ければ素のクライアント
    public static HttpClient newClient(Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        String proxyUrl = envProxy();
        if (proxyUrl != null) {
            try {
                URI uri = URI.create(proxyUrl);
                String host = uri.getHost();
                int port = uri.getPort() >= 0 ? uri.getPort() : 3128;
                if (host != null) {
                    builder.proxy(new ProxySelector() {
                        @Override public List<Proxy> select(URI target) {
                            if (isLocal(target.getHost())) return List.of(Proxy.NO_PROXY);
                            return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
                        }
                        @Override public void connectFailed(URI target, SocketAddress sa, IOException ioe) {}
                    });
                    String userInfo = uri.getUserInfo();
                    if (userInfo != null && userInfo.contains(":")) {
                        String user = userInfo.substring(0, userInfo.indexOf(':'));
                        String pass = userInfo.substring(userInfo.indexOf(':') + 1);
                        builder.authenticator(new Authenticator() {
                            @Override protected PasswordAuthentication getPasswordAuthentication() {
                                if (getRequestorType() == RequestorType.PROXY) {
                                    return new PasswordAuthentication(user, pass.toCharArray());
                                }
                                return null;
                            }
                        });
                    }
                }
            } catch (Exception ignored) {
                // 🔌 プロキシ解析に失敗したら直接接続にフォールバックする（認証情報は出さない）
            }
        }
        return builder.build();
    }
}
