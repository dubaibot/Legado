#!/usr/bin/env bash
# 脱机 JVM 测试验证脚本：编译纯逻辑层 + 测试，运行全部 main() 测试。
# 依赖：JDK 17、kotlin-compiler-2.0.20、kotlinx-coroutines-core、jsoup。
set -euo pipefail
cd "$(dirname "$0")/.."

KC=${KC:-/tmp/opencode/kotlinc/kotlinc/bin/kotlinc}
CP_DIR=${CP_DIR:-/tmp/opencode}
JARS="$CP_DIR/coroutines-core.jar:$CP_DIR/jsoup.jar:$CP_DIR/gson.jar:$CP_DIR/okhttp.jar:$CP_DIR/okio.jar:$CP_DIR/conscrypt.jar"
OUT=${OUT:-/tmp/opencode/out}

mkdir -p "$OUT"

# Android 硬依赖的文件排除（纯 JVM 层之外的），OkHttp/Gson/Log 桩保留
EXCLUDES=(
  "data/db/CatalogDaos.kt"
  "data/db/Converters.kt"
  "data/db/LanyueDatabase.kt"
  "data/db/SourceDaos.kt"
  "core/security/KeystoreSecretStore.kt"
  "core/engine/RssFallbackParser.kt"
  "danmaku/DanmakuMatchEngine.kt"
  "danmaku/render/DanmakuFormatConverter.kt"
  "danmaku/render/DanmakuCanvasLayer.kt"
  "player/"
  "source/video/LocalParseProxy.kt"
  "source/video/CmsVideoSource.kt"
  "source/book/LegadoBookSource.kt"
  "source/danmaku/DandanplaySource.kt"
  "source/rss/LegadoRssSource.kt"
  "source/SourceRegistry.kt"
)

SRCS=()
while IFS= read -r f; do
  skip=0
  for ex in "${EXCLUDES[@]}"; do
    if [[ "$f" == *"$ex"* ]]; then skip=1; break; fi
  done
  [ "$skip" -eq 0 ] && SRCS+=("$f")
done < <(find app/src/main/java -name "*.kt" | sort)

TESTS=$(find tests -name "*.kt" | sort)

"$KC" -jvm-target 17 \
  -classpath "$JARS" \
  -include-runtime -d "$OUT/tests.jar" \
  "${SRCS[@]}" $CP_DIR/stub/android/util/Log.kt $CP_DIR/stub/androidx/room/Entity.kt $CP_DIR/stub/androidx/room/PrimaryKey.kt $CP_DIR/stub/androidx/room/Index.kt $TESTS 2>&1

echo "== 编译通过，运行测试 =="
# pipefail 下 grep -q 提前退出会让 unzip 以 SIGPIPE 失败，先取全量清单再匹配
JAR_LIST=$(unzip -l "$OUT/tests.jar" || true)
for cls in \
  RuleEngineDispatchTestKt \
  JsonXpathTestKt \
  SourceLayerTestKt \
  ReadingLayerTestKt \
  BackupLayerTestKt \
  SourceImportLayerTestKt \
  ShelfAggregateLayerTestKt \
  DanmakuFilterTestKt \
  SecurityGuardTestKt \
  EngineRuleSyntaxTestKt \
  EngineCoreTestKt \
  RuleEngineDispatchRound2TestKt \
  NetworkDispatcherE2ETestKt \
  RuleChainTestKt \
  JsRuntimePoolTestKt \
  SourceRouterTestKt \
  SourceValidatorTestKt \
  CapabilityResolverTestKt \
  TvBoxParserTestKt; do
  if grep -q "$cls" <<< "$JAR_LIST"; then
    java -cp "$OUT/tests.jar:$JARS" "$cls" || exit 1
    echo ""
  else
    echo "跳过（jar 中无此类）：$cls"
  fi
done
