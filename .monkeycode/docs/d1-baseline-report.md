# D1 基线报告

> 执行日：2026-10-15。对应排期 `.monkeycode/docs/merge-daily-plan.md` 阶段 0 / D1。

## 完成项

1. 四个 fork 源树克隆到 `/workspace/_refs/{m,arc,max,ng}`（浅克隆，HEAD 锁定，见 `.monkeycode/docs/refs-manifest.md`），关键目录与清单核对一致：
   - m：`help/video/`（VideoPlaybackPipeline、MacCmsNormalizer 等）存在
   - arc：`model/AutoTask*.kt`、`ui/autoTask/` 存在
   - max：`model/debug/`、`ui/debug/`、`ui/debuglog/` 存在
   - ng：`help/ai/`、`web/mcp/`、`app/src/main/assets/ai/` 存在
2. 工作区打基线 tag `baseline-v90`（指向 main `92f93ee`）。
3. 工具链装好：JDK 17.0.20（apt）、Android SDK（platforms;android-36、build-tools;36.0.0、platform-tools），`local.properties` 指向 `/opt/android-sdk`。
4. 基线构建通过。

## 基线构建结果

```
./gradlew :app:assembleDebug --no-daemon
  -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=768m -XX:+UseParallelGC"
  -Pkotlin.compiler.execution.strategy=in-process
  -Dkotlin.compiler.execution.strategy=in-process
  -Dorg.gradle.workers.max=2

BUILD SUCCESSFUL in 5m 8s
76 actionable tasks: 9 executed, 67 up-to-date
产物: app/build/outputs/apk/app/debug/myreader_app_3.26.100100_universal.apk
```

## 环境注意事项

- 本环境 cgroup 内存上限约 5.45GB（总内存 7965MB × 70%）。默认 `org.gradle.jvmargs=-Xmx6g` 超限，必须用上述参数覆盖为 4g 堆。
- Kotlin 守护进程模式（默认 daemon）在 5.45GB 上限内会因内存不足启动失败，必须用 `in-process` 执行策略（Kotlin 编译在 Gradle JVM 内完成）。
- 构建必须通过 `background_terminal_create` 执行（memory_percent=70、cpu_percent=400、timeout 建议 30 分钟以上）。
- 全新构建（含依赖下载）约 17 分钟；增量约 5 分钟。依赖缓存约 871MB（`~/.gradle`）。
- 网络速度分化：GitHub 快、Debian/Google 源约 200KB/s。settings.gradle 已预置 aliyun 镜像注释行，仓库源慢时可临时启用（用后还原，勿提交）。

## D1 遗留

无。D2（Compose 构建配置）可直接开工。
