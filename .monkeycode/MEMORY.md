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
- Context: D5 执行时发现环境已重置（/opt/android-sdk、JDK、local.properties、_refs 全部丢失），重建工具链
- Category: Environment Configuration
- Instructions:
  - 执行环境可能被重置：gitignore 的 _refs/ 源树、local.properties、JDK、Android SDK 都会丢失，开工前先检查 `which java` 与 `/opt/android-sdk` 是否存在
  - 重建步骤：① `apt-get update && apt-get install -y openjdk-17-jdk-headless`（直接装会报 Unable to locate package，必须先 update）② 下载 commandlinetools-linux-11076708_latest.zip（dl.google.com 当前速度快，2 分钟内 153MB）解压到 /opt/android-sdk/cmdline-tools/latest ③ `yes | sdkmanager --licenses` 后装 "platforms;android-36" "build-tools;36.0.0" "platform-tools" ④ `echo "sdk.dir=/opt/android-sdk" > local.properties` ⑤ 构建命令前 `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`
  - 构建命令必须带内存参数，默认 -Xmx6g 会超 cgroup 上限：./gradlew :app:assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=768m -XX:+UseParallelGC" -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
  - Kotlin 默认 daemon 模式在 5.45GB cgroup 上限内启动失败，必须用 in-process 执行策略
  - 构建必须用 background_terminal_create（memory_percent=70、cpu_percent=400、timeout 30 分钟以上）；全新构建约 22 分钟，增量约 10 分钟
  - 网络速度波动大：2026-10-15 实测 dl.google.com 下载 153MB 仅约 2 分钟（早前记录约 200KB/s），重装 SDK 前先实测网速再决定是否启用镜像
  - 四个 fork 源树（m/arc/max/ng）丢失时按 .monkeycode/docs/refs-manifest.md 的克隆命令重建并校验锁定 commit
  - 多源合并的每日任务排期位于 .monkeycode/docs/merge-daily-plan.md，任务编号对应清单章节，执行每日任务前先读该文档对应行
  - DB 当前 v90，所有迁移逐版本推进、禁止跳版；每次 DB 改动日必须用实库备份跑升级验证
  - 用户要求任务拆分考虑 AI 积分限制：每日一个文件簇，纸面分析日与核心侵入日分开，融合类文件动前先出方案

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

[Project Knowledge Summary]
- Date: 2026-10-16
- Context: D8 gsyVideo 内核融合执行完毕，依赖链外溢与适配偏差记录
- Category: Build Methods
- Instructions:
  - m 源文件搬运的依赖链远超 D4 落点表：D8 实际外溢拉入 dlna 整包（21 文件）、m 版 AppLog（整体替换，put 签名兼容）、AppLogDialog 桥接（Triple 映射）、theme 三件套（UiCorner/ThemeUiPalette/ThemeRuntimeKeys）+ 46 个 PreferKey 外观键 + AppConfig 6 外观属性、http 层四个文件（DohDns/HostAccessStrategy/SourceNetworkClient/SourceLastHostHelper）+ videoStreamClient、Rss.kt 整体取 m（多线路多集采集）
  - 遇到 Unresolved reference 时的标准动作：先查 m 源定义文件（rg 定位），评估是否自包含后整文件拷贝；实体加可空字段/新表可走 AutoMigration，room 增列时构造参数上一行需补逗号
  - DB 版本归属更新：v93=D8（playHistories 表 + rssSources.ruleRoutes/ruleEpisodes + bookSources/rssSources.lastHost），v94=D13 AutoTask，v95=D28 urlRecord，v96=D32 AI 数据层，详见 db-migration-sop.md
  - 取 m 版 Rss.kt 时保留工作区 D6 接线检查：Rss.kt 无 D6 标记（缓存在 SourceHelp 层），可整体覆盖；其余基座文件覆盖前必须 git diff 确认无本地接线丢失
  - m 的 Compose 版 AppLogDialog 依赖 ui/widget/compose 组件库（54 文件，D4 方案已披露），D8 用 Triple 映射桥接 Sigma 版对话框；组件库整体移植仍属 D10+
  - rg 调试时禁用 -r 标志（是 replace 而非递归），误用会把匹配文本替换成占位串，导致误读源码（D8 实际踩坑两次）
  - D8 手测移交项：弹幕渲染/音画同步、DLNA 全链路、controller 布局被 m 版覆盖后的视觉回归（详见 d8-report.md）

[Project Knowledge Summary]
- Date: 2026-10-15
- Context: D6 书源缓存五件套移植完成后的接线状态
- Category: Workflow & Collaboration
- Instructions:
  - 每日任务各自独立分支（261015-feat-dX-xxx 模式），后一天开工前先把此前分支合并进当日基座（D6 基座=D1+D2+D3+D4/D5）
  - SourceQueryCache 失效出口已接线 SourceHelp 全部增删改；Restore/ImportOldData/RecycleBin/两个 SourceEditViewModel 尚未接线（TTL 2s 兜底自愈），随对应功能移植补齐
  - SourcePreconnectHelper/warmUpConnection 已就位，消费方（Rss.kt/BookChapterList）在 D11 接线
  - 工作区 AppLog 已在 D8 整体替换为 m 版（337 行，含 putDebug/putDebugWithTag/putSampled/Level/TAG_*），此前的"精简版降级适配"指引作废；从 m 搬日志代码可直接用 m 原文
  - 工作区 DNS 兜底/HTTP 缓存属 m 的 HttpHelper 大段演进（m 版 412 行 vs 工作区 226 行），D11 移植时整段对比；D8 已先行拉入 DohDns/HostAccessStrategy/videoStreamClient（ExoPlayerHelper 硬依赖）

[Project Knowledge Summary]
- Date: 2026-10-19
- Context: D9 播放壳搬入完成（20 轮构建迭代收敛），沉淀依赖外溢治理与 shim 模式
- Category: Build Methods
- Instructions:
  - 跨批次依赖用 shim 隔离：m 文件拖出大子系统（cloud/AI/DB v94）时，在工作区写同名最小 shim 覆盖当前消费面（D9 三例：TopBarConfig 695 行拖 AppCloudStorage→shim；AppSettingComponents 781 行拖 LazyColumnScrollbar→撤拉+shim；CoverCollectionManager 拖 sceneBookmarks Room 实体→shim），shim 文件头注明落位批次，届时以 m 原版覆盖
  - 判断"整拉 vs 合并"用 diff 的删除行数：`diff ws m | grep -c "^<"` 为 0 即 m 纯超集可整拉；非 0 时逐行看——工作区专属行若只是 m 的旧版代码（演进关系）仍可整拉，若是工作区特色接线才需三路合并
  - 取 m 活动内引用 DB 实体/DAO 的功能且实体未注册（schema 规则禁止）时：调用点降级为 toast stub，实体与 helper 不拉（备份到 /tmp/opencode 备份目录）
  - sqlite3.executescript 要求语句以分号分隔，组合迁移 SQL 快照（多个 CREATE/ALTER 拼接）每条必须带分号
  - miuix 接入技巧：exclude(group:) 排除 androidx.compose.* 与 org.jetbrains.compose* 全系传递组可让 miuix 0.8.8 在 minSdk 21 + BOM 2025.04.01 下工作，manifest 加 uses-sdk tools:overrideLibrary；D10 全量组件时复验
  - 构建错误收敛时用 `grep "^e: file" <log> | sed 's|.*io/legado/app/||; s/:[0-9]*:[0-9]* /: /' | sort -u` 取全量唯一清单（tail 截断会漏），编译器错误是唯一真值（自制扫描器误报多）
