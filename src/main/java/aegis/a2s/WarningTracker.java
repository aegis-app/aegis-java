package aegis.a2s;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// ========================================
// ⚠️ WarningTracker — 警報コードの世代管理
// ========================================
// 📌 区域ごとに「前回観測で有効だった警報コードの集合」と「コードごとの
// 世代」を保持する。コードが集合から消えた（解除された）ときに世代を
// 進めることで、解除→再発表を別イベントとして拾えるようにする
// （発表時刻には依存しない）。A2S の警報変換（A2sConvert）が使う
public final class WarningTracker {
    private WarningTracker() {}

    // 📝 区域キー → 前回観測で有効だったコード集合
    private static final Map<String, Set<String>> LAST_CODES = new ConcurrentHashMap<>();
    // 📝 「区域キー|コード」→ 世代番号
    private static final Map<String, Integer> GENERATIONS = new ConcurrentHashMap<>();

    // 📝 現在の有効コード集合と前回観測分を比べ、消えたコードの世代を進める。
    // 現在の世代マップ（code → 世代）を返す（テスト対象）
    public static synchronized Map<String, Integer> advanceGenerations(String areaKey, Set<String> currentCodes) {
        Set<String> prev = LAST_CODES.getOrDefault(areaKey, Set.of());
        for (String code : prev) {
            if (!currentCodes.contains(code)) {
                GENERATIONS.merge(areaKey + "|" + code, 1, Integer::sum);
            }
        }
        LAST_CODES.put(areaKey, Set.copyOf(currentCodes));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String code : currentCodes) out.put(code, GENERATIONS.getOrDefault(areaKey + "|" + code, 0));
        return out;
    }
}
