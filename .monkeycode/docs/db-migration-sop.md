# DB 迁移 SOP（v91 起）

> 清单 1.2 / 排期 D3 产出。基线：工作区 DB v90（tag `baseline-v90`，schema identityHash `9975bfd44b7aa0493993f32ec5fbaaa5`）。
> 工作区已含 v91 占位空迁移（多源合并 D3 落地），首个真实 schema 变更从 v92 起步。

## 1. 版本纪律

1. **逐版本推进**：从 v91 起每个 DB 改动日只推进一版，禁止跳版、禁止复用已构建过的版本号。
2. **预期版本归属**（随排期，实际以当日落地为准）：
   - v91 = D3 空迁移占位（已完成）
   - v92 = D7 `RssArticle` 视频扩展字段
   - v93 = D13 `AutoTaskRule` 实体 + DAO
   - v94 = D28 urlRecord 阅读记录实体
   - v95 = D32 AI 数据层
   - 后续再动 DB 顺延编号
3. **红线**：
   - `fallbackToDestructiveMigrationFrom` 仅覆盖 v1–v9；v10 起任何路径禁止破坏性回退。
   - 禁止手改 `app/schemas/**/*.json`（KSP 生成物，只读）。
   - 分叉字段（工作区 / 源 fork / 上游 legado 三方不一致）→ 三方对比取并集，决策记入当日报告。

## 2. 迁移类型选择

| 变更类型 | 用法 | 参考 |
|----------|------|------|
| 纯新增（加表/加列/加索引/加视图） | `AutoMigration(from = N, to = N+1)`，注册到 `AppDatabase.autoMigrations` 末尾 | AutoMigration(from=89, to=90) |
| 新增 + 数据回填 | AutoMigration + `AutoMigrationSpec`（`onPostMigrate` 执行 SQL） | `Migration_54_55`、`Migration_80_81` |
| 删列/改列/重建表等破坏性变更 | 手写 `Migration(N, N+1)` 放 `DatabaseMigrations.migrations`，并在方案文档先行评审 | `migration_10_11` |

新实体须同时登记到 `@Database(entities = [...])` 与对应 abstract DAO 属性。

## 3. DB 改动日标准流程（Checklist）

1. **开工核对**：`AppDatabase.version` 与 `app/schemas/` 最新 JSON 版本一致；git 状态干净。
2. **编码**：写实体/DAO → `version = N+1` → 注册迁移（按上表选型）。
3. **构建**：`./gradlew :app:assembleDebug`（命令与内存参数见附录），必须通过。
4. **schema 导出核对**：确认生成 `app/schemas/io.legado.app.data.AppDatabase/(N+1).json`，`git diff` 检查：
   - `version` = N+1
   - 变更仅含本次预期的表/列/索引/视图
   - 有 schema 变更时 `identityHash` 必然变化
5. **主机侧演练**（本环境无模拟器时的结构层验证门禁）：
   ```
   python3 tools/db_migration_check.py --from N --to N+1 --sql tools/db/migrations/migration_N_N+1.sql
   ```
   工具从 schema JSON 重建 vN 库并预置种子数据 → 应用迁移 SQL → 与 vN+1 结构逐项比对（列/索引/外键/视图/行数保全）。空迁移省略 `--sql`。
6. **实库升级测试**（有设备/模拟器时，DB 改动日必做）：
   - 用 `baseline-v90` 构建的 v90 基线包造实库：导入书源、加书、阅读产生进度与记录。
   - 备份：`adb pull /data/data/<pkg>/databases/legado.db`（连同 `-wal`/`-shm`）。
   - 同签名同包名安装新 debug 包，启动触发迁移。
   - 验收：无 crash；logcat 无 `Migration didn't properly handle`；书架/阅读进度/书源/订阅数据完整；`PRAGMA user_version` = N+1。
   - 结论记入当日 `dN-report.md`。
7. **提交**：代码 + `(N+1).json` + `tools/db/migrations/migration_N_N+1.sql` 快照 + 报告，commit 注明清单编号。

## 4. 迁移 SQL 快照约定

- 位置：`tools/db/migrations/migration_{from}_{to}.sql`。
- 内容：与代码迁移逻辑一致的 SQL 副本；AutoMigration 的加列/建表 SQL 以 `(N+1).json` 的 `createSql` 为准手写对应副本，供演练工具与代码审查使用。
- 快照与代码不一致导致演练 FAIL 时，先核对快照，再核对实体定义。

## 附录：构建命令（D1 验证过的内存参数，环境 cgroup 上限约 5.45GB）

```
./gradlew :app:assembleDebug --no-daemon \
  -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=768m -XX:+UseParallelGC" \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.workers.max=2
```

构建须通过 `background_terminal_create` 执行（memory_percent=70、cpu_percent=400、timeout ≥ 30 分钟）。
