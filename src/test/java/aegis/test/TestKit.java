package aegis.test;

// ========================================
// 🧪 TestKit — 単体テスト共通の検証ヘルパー
// ========================================
// 📌 JUnit を使わず main メソッドで実行する軽量テスト方式のための基盤。
// check が失敗したら AssertionError で即座に止め、成功数を数える
final class TestKit {
    private TestKit() {}

    static int passed = 0; // 📝 成功した check の総数

    // ✅ 条件を検証する。失敗したらテスト名つきで例外を投げる
    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("ok " + name);
    }

    // ✅ 文字列に部分文字列が含まれることを検証する
    static void contains(String text, String part, String name) {
        check(text != null && text.contains(part), name + " (expected to contain: " + part + ")");
    }

    // ✅ 文字列に部分文字列が含まれないことを検証する
    static void notContains(String text, String part, String name) {
        check(text == null || !text.contains(part), name + " (expected NOT to contain: " + part + ")");
    }
}
