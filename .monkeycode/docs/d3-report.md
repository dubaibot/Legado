# D3 报告：迁移框架与资源约定

> 执行日：2026-10-15。对应排期阶段 0 / D3（清单 1.2/1.4），分支 `feat/p0-infra`。

## 完成项

1. **DB 迁移 SOP**：`.monkeycode/docs/db-migration-sop.md`——版本纪律（逐版推进/禁跳版/禁破坏性回退）、迁移选型（AutoMigration / Spec / 手写 Migration）、改动日七步 Checklist、迁移 SQL 快照约定、实库升级测试流程（含设备步骤）。
2. **v91 空迁移预写**：
   - `AppDatabase` version 90→91，注册 `AutoMigration(from = 90, to = 91)`（无 schema 变更占位）。
   - KSP 导出 `app/schemas/io.legado.app.data.AppDatabase/91.json`：version=91，identityHash 与 v90 相同（`9975bfd4…`，空迁移符合预期），除 version 外结构逐字段一致。
   - 真实 schema 变更自 v92 起步（D7 RssArticle 字段）。
3. **主机侧迁移演练工具**：`tools/db_migration_check.py`——从 schema JSON 重建 vN 库 + 种子数据 → 应用迁移 SQL → 与 vN+1 结构比对（列/索引/外键/视图/行数保全）。90→90 自检与 90→91 演练均 PASS（21/21 表种子成功）。
4. **迁移 SQL 快照约定落地**：`tools/db/migrations/migration_90_91.sql`（空迁移占位文件）。
5. **资源分区命名约定**：`.monkeycode/docs/resource-naming-convention.md`——strings 按源分文件（`strings_{m,arc,max,ng}.xml`）、其他资源文件名/键名加源前缀、冲突处理流程。
6. **环境恢复**（执行中发现环境重置，D1 工具链与源树丢失）：
   - JDK 17.0.20.1（apt）+ Android SDK 462MB（cmdline-tools/platform-36/build-tools 36.0.0）+ `local.properties` 重建。
   - 四源树重克隆回 `/workspace/_refs/`，HEAD 与 `refs-manifest.md` 锁定 commit 逐一核对一致（m `46dc931…` / arc `e111bf6…` / max `9cef169…` / ng `49ca955…`）。

## 验证结果

```
./gradlew :app:assembleDebug --no-daemon（内存参数同 D1 基线）
BUILD SUCCESSFUL in 17m 54s
77 actionable tasks: 71 executed, 6 from cache
产物: app/build/outputs/apk/app/debug/*.apk

python3 tools/db_migration_check.py --from 90 --to 91 --sql tools/db/migrations/migration_90_91.sql
演练 v90 -> v91: 种子 21/21 表
[PASS] 结构与行数保全比对一致
```

## 说明与遗留

- 本环境无模拟器/真机，实库升级的设备侧流程已写入 SOP，待有设备时执行（不阻塞后续排期）；主机侧结构演练已作为 DB 改动日的门禁步骤。
- 91.json 的 identityHash 与 90 相同是空迁移的正常表现；首个带 schema 变更的迁移（v92）当日必须核对 identityHash 变化。

## 下一步

D4 纸面日：对比工作区与 m 的 `help/gsyVideo` 10 个同名文件、`ui/video`、`service/VideoPlayService`，产出《弹幕保留融合方案》。源树 `_refs/m` 已就位。
