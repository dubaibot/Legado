# AI 接手须知

> 这份文档写给**下一个接手这个仓库的 AI**。目标：让它在 10 分钟内
> 能跑通测试、看懂约束、知道下一步做什么，而不是从零摸索踩一遍同样的坑。
>
> 路线见 [`APK-DEVELOPMENT-PLAN.md`](APK-DEVELOPMENT-PLAN.md)，
> 逐条任务见 [`TASK-BACKLOG.md`](TASK-BACKLOG.md)。

---

## 一、这个仓库是什么

蓝阅（Lanyue）：自托管、**本地优先**的 Android 阅读器。定位对标 Legado，
但强调无埋点、无遥测、无第三方统计 SDK。

**当前阶段：逻辑层完备，UI 层为零。** 这句话很重要——

- `app/src/main/java` 下 75 个 Kotlin 文件、12,762 行，全是**纯逻辑**：
  规则引擎、网络、解析、净化、排版计算、数据层、弹幕策略、朗读调度；
- 但**没有任何 Activity / Compose 页面**，也**没有 `LanyueApp`**。
- 所以：`./gradlew assembleDebug` 现在**一定失败**，且失败原因不止一个。
  这不是「坏了」，是「还没到那一步」。不要试图用几分钟修好它。

**历史上已经做完的轮次**（R19–R23，读 README 的对应章节可了解设计意图）：

| 轮次 | 内容 |
|---|---|
| R19 | 正文替换净化引擎 `TextPurifier` |
| R20 | Legado `replaceRule` 编解码 + 书源字段接入 |
| R21 | 净化管线 `PurifyPipeline`，按源缓存，调试判定移到净化之后 |
| R22 | 朗读分段与调度 `TtsSegmenter` / `TtsReadController` |
| R23 | 弹幕源配置校验与排序 `DanmakuSourceValidator` / `DanmakuSourceRegistry` |

---

## 二、跑测试：唯一必须先跑通的事

### 2.1 为什么需要自建工具链

仓库**没有 Gradle Wrapper**，JVM 测试不依赖 Android SDK，靠
`tools/test_jvm.sh` 直接调 `kotlinc`。所以接手第一件事是**准备编译器与依赖 jar**。

沙箱环境可能会重置（`/tmp` 与 `/mnt/work` 都清空）。重建顺序如下。

### 2.2 拉取依赖 jar

```bash
mkdir -p /tmp/opencode && cd /tmp/opencode
M=https://repo1.maven.org/maven2
get(){ curl -sL --max-time 300 -o "$2" "$1" && echo "OK $2 $(stat -c%s "$2")"; }

get $M/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.2.20/kotlin-compiler-embeddable-2.2.20.jar kc22.jar
get $M/org/jetbrains/kotlin/kotlin-stdlib/2.2.20/kotlin-stdlib-2.2.20.jar kotlin-stdlib22.jar
get $M/org/jetbrains/kotlin/kotlin-script-runtime/2.2.20/kotlin-script-runtime-2.2.20.jar scriptrt.jar
get $M/org/jetbrains/kotlin/kotlin-reflect/2.2.20/kotlin-reflect-2.2.20.jar reflect.jar
get $M/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar trove4j.jar
get $M/org/jetbrains/annotations/13.0/annotations-13.0.jar annotations.jar
get $M/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar coroutines-core.jar
get $M/org/jsoup/jsoup/1.13.1/jsoup-1.13.1.jar jsoup.jar
get $M/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar gson.jar
get $M/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar okhttp.jar
get $M/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar okio.jar
get $M/org/conscrypt/conscrypt-openjdk-uber/2.5.2/conscrypt-openjdk-uber-2.5.2.jar conscrypt.jar
```

### 2.3 生成编译器包装脚本

```bash
cat > /tmp/opencode/kc22 <<'EOS'
#!/bin/bash
D=/tmp/opencode
exec java -Xmx1800m -cp "$D/kc22.jar:$D/kotlin-stdlib22.jar:$D/scriptrt.jar:$D/reflect.jar:$D/trove4j.jar:$D/annotations.jar:$D/coroutines-core.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib "$@"
EOS
chmod +x /tmp/opencode/kc22
```

### 2.4 生成 Android / Room 编译桩

JVM 测试不引 Android SDK，所以 `android.util.Log`、`androidx.room.*` 必须打桩。

```bash
mkdir -p /tmp/opencode/stub/android/util /tmp/opencode/stub/androidx/room
```

`stub/android/util/Log.kt`：

```kotlin
package android.util
object Log {
    @JvmStatic fun v(t: String?, m: String?): Int = 0
    @JvmStatic fun d(t: String?, m: String?): Int = 0
    @JvmStatic fun i(t: String?, m: String?): Int = 0
    @JvmStatic fun w(t: String?, m: String?): Int = 0
    @JvmStatic fun e(t: String?, m: String?): Int = 0
    @JvmStatic fun e(t: String?, m: String?, tr: Throwable?): Int = 0
}
```

`stub/androidx/room/Entity.kt`：

```kotlin
package androidx.room
@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.RUNTIME)
annotation class Entity(
    val tableName: String = "",
    val primaryKeys: Array<String> = [],
    val indices: Array<Index> = []
)
```

`stub/androidx/room/PrimaryKey.kt`：

```kotlin
package androidx.room
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY) @Retention(AnnotationRetention.RUNTIME)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
```

`stub/androidx/room/Index.kt`：

```kotlin
package androidx.room
@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.RUNTIME)
annotation class Index(vararg val value: String, val unique: Boolean = false, val name: String = "")
```

> ⚠️ `Index` 的 `value` **必须是 `vararg val`**，不能写成 `Array<String>`。
> 实体里写的是 `@Index("col")` 这种单值形式，签成数组会编译失败。

### 2.5 跑全量测试

```bash
cd <repo>
bash tools/test_jvm.sh > /tmp/test.log 2>&1; echo "exit=$?"
grep -cE "^PASS = " /tmp/test.log                      # 套数
grep -oE "^PASS = [0-9]+" /tmp/test.log | grep -oE "[0-9]+" | awk '{s+=$1} END{print s}'  # 断言总数
grep -E "^FAIL = [1-9]" /tmp/test.log || echo "无失败"
```

**基线：21 套 / 1,434 项 / FAIL=0。** 这是接手时唯一可信的起点。
跑不到这个数，先查环境，不要改代码。

---

## 三、环境相关的已知坑

这些都是实际踩过、且会重复踩的。

### 3.1 Kotlin 编译器与 JDK
- 用 **kotlin-compiler-embeddable 2.2.20**。系统自带 `kotlinc` 在高版本 JDK 上会报错。
- 包装脚本里必须带 `-no-stdlib`，否则会用编译器内置 stdlib 与外部 jar 冲突。

### 3.2 jsoup 版本是**故意不一致**的
- `app/build.gradle.kts` → `jsoup:1.17.2`（Android 构建用）
- `tools/test_jvm.sh` → `jsoup:1.13.1`（JVM 测试用）

1.17.2 加了可空注解，会让测试代码里大量非空假设编译失败。**不要统一版本。**

### 3.3 测试输出路径是硬编码的
部分测试把失败明细写到 `/mnt/work/lanyueNN/out_*.txt` 这样的绝对路径。
沙箱重置后目录不存在会抛 `FileNotFoundException`，表现为「测试莫名失败」。

**解决**：先 `mkdir -p` 这些目录，或改测试里的路径。这是环境问题，不是逻辑错误。

### 3.4 测试类必须在清单里注册
`tools/test_jvm.sh` 里是一份**显式类名清单**。新增测试文件后必须把
`XxxTestKt` 加进去，否则它根本不会运行——而且**不会有任何提示**，
你会以为它通过了。

### 3.5 断言数只增不减
每轮结束前核对总数。断言数掉下来通常意味着你不小心删了测试，
或某个测试类没被注册。

### 3.6 GitHub API 上传大文件会超时
浏览页 HTML 有 1.3MB。分阶段推送（先源码、再网页）+ 逐 blob 上传 + 指数退避重试
是验证有效的做法。读远端大文件时用 `contents` API 加 `Accept: application/vnd.github.raw`，
用 `git/blobs` 的 base64 inline 会 `IncompleteRead`。

---

## 四、代码纪律

### 4.1 四道合规门禁，必须全绿
```bash
bash tools/ci.sh
```
它跑四个检查：

| 脚本 | 检查什么 | 新增内容时要注意 |
|---|---|---|
| `check_no_telemetry.py` | 无埋点、无统计 SDK | 不许引入任何上报依赖 |
| `check_permissions.py` | 权限最小化 | 新权限要进白名单，并说明理由 |
| `check_disclaimer.py` | 免责声明存在 | 相关文案别删 |
| `check_contracts.py` | 源字段契约稳定 | 改 `source/model/` 要走版本迁移 |

这些是**门禁不是建议**。绕过它们等于把仓库的立身之本拆了。

### 4.2 注释要有信息量
仓库现有代码的注释风格是：**解释为什么，不解释是什么**。
比如「段首不留标点，否则引擎会把」」读成『右引号』」——
这是踩过的坑，不是复述代码。

新代码请保持这个标准。不要写 `// 设置名称` 这种废话注释，
也不要把整段逻辑翻译成中文散文。

### 4.3 每轮交付的形状
一轮 = 一个可独立评审的提交单元：
1. 实现（新增/修改源码）；
2. 单测（覆盖新逻辑的边界，不是走过场）；
3. README 补章节 + 单测表格加一行；
4. 全量回归 FAIL=0；
5. 分阶段推送（源码 → 浏览页）+ 远端核验（零回退）。

### 4.4 哪些地方改动风险高
- **`source/model/`**：字段契约，动它要迁移；
- **`data/db/`**：实体改动涉及 Room schema 与迁移；
- **`core/render/Paginator`**：排版计算，改了要重跑 `ReadingLayerTest`；
- **`tools/check_*.py`**：门禁本身，别为了让代码过检而放宽规则。

---

## 五、下一步做什么

**按 `TASK-BACKLOG.md` 的 M1 开始，顺序是 T01 → T02 → T03 → T04 → T05。**

如果只能推进一步，就做 **T01：补 `LanyueApp`**。理由：
它是清单里已经声明、源码却不存在的类，是当前唯一的**硬编译阻塞**。
补上它之后，才有资格谈「构建失败的其他原因」。

### M1 期间的建议心态
这一步的产出不是功能，是**「能构建」这个事实**。会连续遇到
KSP / Compose 插件 / 依赖版本的问题，每个都要单独查。
不要在这一阶段同时写业务代码——那会让错误来源混在一起，很难判断
「是构建配置错，还是我的 UI 代码错」。

---

## 六、给接手者的三条提醒

1. **先跑测试再改代码。** 环境没通就动手，后面所有结论都不可信。
2. **区分「设计如此」与「写错了」。** 这个仓库里好几处看起来奇怪的逻辑
   （净化比例回退、探活失败仍允许保存、jsoup 版本不一致）都是有原因的，
   README 对应章节写了理由。先读，再改。
3. **不要为了让门禁通过而放宽门禁。** 如果某条检查确实过不了，
   说明设计有问题，应该改设计，而不是改检查。
