#!/usr/bin/env bash
# 脱机 JVM 测试验证脚本：编译纯逻辑层 + 测试，运行全部 main() 测试。
# 依赖：JDK 17、kotlin-compiler-2.0.20、kotlinx-coroutines-core、jsoup。
set -euo pipefail
cd "$(dirname "$0")/.."

KC=${KC:-/tmp/opencode/kotlinc/kotlinc/bin/kotlinc}
CP_DIR=${CP_DIR:-/tmp/opencode}
# annotations.jar：编译器代码生成阶段需要 org.jetbrains.annotations.NotNull，
# 缺失时会在 IR 降低阶段抛出误导性的 ERROR_EXPR 'Default Stub'。
JARS="$CP_DIR/kotlin-stdlib22.jar:$CP_DIR/coroutines-core.jar:$CP_DIR/jsoup.jar:$CP_DIR/gson.jar:$CP_DIR/okhttp.jar:$CP_DIR/okio.jar:$CP_DIR/conscrypt.jar:$CP_DIR/annotations.jar"
OUT=${OUT:-/tmp/opencode/out}

mkdir -p "$OUT"

# Android 硬依赖的文件排除（纯 JVM 层之外的），OkHttp/Gson/Log 桩保留
EXCLUDES=(
  "LanyueApp.kt"
  "ui/MainActivity.kt"
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

# 注意：tests/NetDispatcherE2ETest.kt 是第十三轮的历史占位，正文仅有注释，
# 编译后不产生任何类，因此不能也不必加入下面的执行清单；
# 其实质用例已集中到 tests/NetworkDispatcherE2ETest.kt。
TESTS=$(find tests -name "*.kt" | sort)

# 注意本脚本用的是 jsoup 1.13.1，与 app/build.gradle.kts 的 1.17.2 故意不同
# （见 docs/AI-HANDOFF.md §3.2）：1.17.2 的包级 @NullMarked 会让 Kotlin 把
# Elements.first() 之类判为可空，与测试里大量非空假设冲突。不要统一两个版本。
# 下面这个开关是防御性的：万一有人把测试链的 jsoup 升到带 JSpecify 注解的版本，
# 它保证既有断言仍按原语义编译，而不是去调用点加一串 '!!'。
"$KC" -jvm-target 17 \
  -Xjspecify-annotations=ignore \
  -classpath "$JARS" \
  -d "$OUT/tests.jar" \
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
  VideoDanmakuLayerTestKt \
  SchemaExportTestKt \
  ShellNavTestKt \
  SourceManageTestKt \
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
  TvBoxParserTestKt \
  SortUrlParserTestKt \
  TextPurifierTestKt \
  PurifyRuleCodecTestKt \
  PurifyPipelineTestKt \
  TtsReadTestKt \
  DanmakuSourceLayerTestKt \
  AppShellTestKt \
  WrapperConfigTestKt \
  MainShellTestKt \
  BuildScriptTestKt \
  DetailTocTestKt \
  SearchSessionTestKt; do
  if grep -q "$cls" <<< "$JAR_LIST"; then
    java -cp "$OUT/tests.jar:$JARS" "$cls" || exit 1
    echo ""
  else
    echo "跳过（jar 中无此类）：$cls"
  fi
done
