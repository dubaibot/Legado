# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-10-15
- Context: Agent 执行「多源功能合并」排期时对仓库与环境的核查
- Category: Workflow & Collaboration
- Instructions:
  - 四个 fork 源树（m/arc/max/ng）当前不在工作区，需先克隆或上传到 /workspace/_refs/ 并锁定 commit hash，后续搬运任务全部依赖它
  - 多源合并的每日任务排期位于 .monkeycode/docs/merge-daily-plan.md，任务编号对应清单章节，执行每日任务前先读该文档对应行
  - DB 当前 v90，所有迁移逐版本推进、禁止跳版；每次 DB 改动日必须用实库备份跑升级验证
  - 用户要求任务拆分考虑 AI 积分限制：每日一个文件簇，纸面分析日与核心侵入日分开，融合类文件动前先出方案

[Project Knowledge Summary]
- Date: 2026-10-15
- Context: D1 基线构建执行（assembleDebug）
- Category: Build Methods
- Instructions:
  - 构建命令必须带内存参数，默认 -Xmx6g 会超 cgroup 上限：./gradlew :app:assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=768m -XX:+UseParallelGC" -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
  - Kotlin 默认 daemon 模式在 5.45GB cgroup 上限内启动失败，必须用 in-process 执行策略
  - 构建必须用 background_terminal_create（memory_percent=70、cpu_percent=400、timeout 30 分钟以上）；全新构建约 17 分钟，增量约 5 分钟
  - 工具链已装好：JDK 17（apt）、Android SDK /opt/android-sdk（platform-36、build-tools 36.0.0），local.properties 已写入
  - 网络分化：GitHub 快，Debian/Google 源约 200KB/s；settings.gradle 有预置 aliyun 镜像注释行可临时启用，用后还原勿提交

[Project Knowledge Summary]
- Date: 2026-10-15
- Context: D3 执行时发现执行环境被重置，D1 工具链与源树全部丢失，重新恢复并总结流程
- Category: Environment Configuration
- Instructions:
  - 环境重置会丢失：JDK、/opt/android-sdk、/root/.gradle 缓存、/workspace/_refs 源树、local.properties；仓库代码与 .monkeycode 文档不受影响
  - 恢复顺序：apt 装 openjdk-17-jdk-headless → dl.google.com 下 cmdline-tools 装 SDK（platform-tools、platforms;android-36、build-tools;36.0.0）→ 写 local.properties → 重克四源（命令在 refs-manifest.md）→ 跑 assembleDebug（全量约 18 分钟）
  - 每日开工先花 1 分钟探测环境（java -version、ls /workspace/_refs、du ~/.gradle），发现重置立即后台恢复，与当日纸面工作并行不阻塞

[Project Knowledge Summary]
- Date: 2026-10-15
- Context: D3 预写 v91 空迁移时验证 Room 行为
- Category: Build Methods
- Instructions:
  - Room 2.7.1 允许注册无 schema 变更的 AutoMigration(90, 91)：编译通过，identityHash 与 v90 保持一致，属空迁移正常表现
  - DB 版本归属：v91=D3 占位空迁移（已用掉），v92=D7 RssArticle 字段，v93=D13 AutoTask，v94=D28 urlRecord，v95=D32 AI 数据层，详见 db-migration-sop.md
  - DB 改动日主机侧门禁：python3 tools/db_migration_check.py --from N --to N+1 --sql tools/db/migrations/migration_N_N+1.sql（空迁移省略 --sql）

[Project Knowledge Summary]
- Date: 2026-10-15
- Context: D2 启用 Compose 时排查 BOM 与 AGP 兼容性
- Category: Environment Configuration
- Instructions:
  - Compose BOM 必须用 2025.04.01（对齐 m 源）：max 源的 2026.08.00 会解析出 compose-ui 1.12.0，要求 AGP 9.1+，与工作区 AGP 8.13.2 冲突
  - 移植 max 的 Compose UI（阶段 3）若报缺少新 API，先评估整体升 AGP 9.x 再考虑降级 BOM，两者是绑定的
  - Compose 配置已就位：kotlin-compose 插件（org.jetbrains.kotlin.plugin.compose）+ buildFeatures.compose + BOM 管理全部 compose 依赖，版本一律走 BOM 不写死
