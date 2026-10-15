# D7 报告：media3 依赖扩展与 RssArticle 视频字段

> 执行日：2026-10-15。对应排期阶段 1 / D7（清单 2.1.5/2.1.6），分支 `261015-feat-d7-media3-rssvideo`（基于 D6 基座 18adac8）。

## 完成项

1. **清单 2.1.5 依赖**（对齐 m 源写法，版本沿用工作区 media3 1.8.0）：
   - 启用 `media3.exoplayer.hls`（m3u8 播放）与 `media3.ui`
   - 新增 `media3.effect`（画质增强：GlEffect/RgbMatrix 等）
   - 新增 `media3.datasource.cronet`（TLS 指纹与 Chrome 一致，解决 CDN 指纹检测导致 m3u8 失败）；**排除 `org.chromium.net:cronet-api` 传递依赖**，API 由本地 `cronetlib/cronet_api.jar` 提供，避免重复类
   - `session` 保持注释（第一版不引入）
2. **清单 2.1.6 RSS 字段**：
   - `RssArticle` 增加 `idx_origin_sort(origin, sort)` 复合索引（来自 m 源，视频列表/分类聚合查询命中）
   - type/durPos 视频列工作区已存在（0网页/1图片/2视频、阅读进度），与 m 一致，无新增列
   - m 源的 `RssArticleDao` 查询优化（getReadLinks/getImage/CursorWindow 裁剪）属 D9/D10 播放壳接线范围，本日不动
3. **DB v91→v92 迁移**：
   - `AppDatabase` version 92，注册 `AutoMigration(from = 91, to = 92)`（Room 自动生成 CREATE INDEX）
   - KSP 导出 `92.json`，SQL 快照 `tools/db/migrations/migration_91_92.sql`
   - 实库升级组合演练快照 `tools/db/migrations/migration_90_92.sql`（90→91 空迁移无 SQL）

## 验证结果

```
./gradlew :app:assembleDebug --no-daemon（内存参数同基线）
BUILD SUCCESSFUL in 11m 40s
77 actionable tasks: 71 executed, 6 from cache

schema 导出核对（91.json → 92.json）：
  v91: identityHash 9975bfd4…  →  v92: identityHash f25e09a6…
  表集合一致；views 一致；唯一差异 = rssArticles 新增索引 idx_origin_sort (origin, sort, non-unique)

主机侧升级演练：
  python3 tools/db_migration_check.py --from 91 --to 92 --sql tools/db/migrations/migration_91_92.sql
  演练 v91 -> v92: 种子 21/21 表  [PASS]
  python3 tools/db_migration_check.py --from 90 --to 92 --sql tools/db/migrations/migration_90_92.sql
  演练 v90 -> v92: 种子 21/21 表  [PASS]（实库 v90→新版本组合路径，90→91 空迁移无 SQL）
```

设备侧实库升级（v90 基线包装实库 → 装新包升级）按 SOP 第 3.6 步执行，待有设备时补记；结构层门禁已全通过。

## 三方对比结论（清单第 7 节纪律）

rssArticles 表工作区/源/上游差异：工作区=上游（type/durPos，无索引）；m=同列+`idx_origin_sort` 索引。
取并集 = 保留现有列 + 追加索引，无字段冲突。
