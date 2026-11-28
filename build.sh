#!/usr/bin/env bash
# ============================================================================
# 🌸 AEGIS-Java build script (full build: clean compile + unit tests + jar)
# ----------------------------------------------------------------------------
# Usage: ./build.sh [--skip-tests]
#   Compiles src/main/java and src/test/java with javac (JDK 21, standard
#   library only - no dependency downloads, works fully offline), runs the
#   unit test suites, and packages aegis.jar.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"
# 日本語の文字化け対策 (POSIX ロケール環境でも UTF-8 で処理する)
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"

# ----------------------------------------
# 🔍 JDK 21 の探索 (JAVA_HOME → PATH → 既知の配置場所)
# ----------------------------------------
find_javac() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then echo "$JAVA_HOME/bin/javac"; return; fi
  if command -v javac >/dev/null 2>&1; then command -v javac; return; fi
  for c in "$HOME/workspace/tools/jdk-21/bin/javac" /usr/lib/jvm/*/bin/javac; do
    if [ -x "$c" ]; then echo "$c"; return; fi
  done
  echo "javac が見つかりません。JDK 21 をインストールするか JAVA_HOME を設定してください。" >&2; exit 1
}
JAVAC="$(find_javac)"
JAVA="$(dirname "$JAVAC")/java"
JAR="$(dirname "$JAVAC")/jar"
echo "Using javac: $JAVAC"
"$JAVAC" -version

SKIP_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --skip-tests|notest) SKIP_TESTS=1 ;;
  esac
done

# ----------------------------------------
# 🧹 クリーン + コンパイル
# ----------------------------------------
echo "== Clean =="
rm -rf out aegis.jar
mkdir -p out

echo "== Compile =="
find src/main/java src/test/java -name '*.java' > out/sources.txt
"$JAVAC" -encoding UTF-8 -d out @out/sources.txt
rm -f out/sources.txt

# ----------------------------------------
# 🧪 単体テスト (ネットワーク不要)
# ----------------------------------------
JAVA_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dnative.encoding=UTF-8"
if [ "$SKIP_TESTS" -eq 0 ]; then
  echo "== Test: aegis.test.AegisTest =="
  "$JAVA" $JAVA_OPTS -cp out aegis.test.AegisTest
  echo "== Test: aegis.test.A2sTest =="
  "$JAVA" $JAVA_OPTS -cp out aegis.test.A2sTest
else
  echo "== Tests skipped =="
fi

# ----------------------------------------
# 📦 jar 化 (Main-Class: aegis.app.Main)
# ----------------------------------------
echo "== Package =="
"$JAR" cfe aegis.jar aegis.app.Main -C out .
ls -la aegis.jar
echo "Build OK"
