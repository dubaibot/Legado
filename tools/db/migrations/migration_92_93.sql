-- 多源合并 D8/D9：Room DB v92 -> v93 迁移快照
-- 依据 app/schemas/io.legado.app.data.AppDatabase/{92,93}.json 差异推导：
--   1) rssSources 新增 ruleRoutes/ruleEpisodes/lastHost（视频路由规则与恢复用 host，可空）
--   2) book_sources 新增 lastHost（可空）
--   3) 新表 playHistories（复合主键 articleUrl+videoUrl+rssSourceId，含默认值列）
-- 实库演练：python3 tools/db_migration_check.py --from 90 --to 93 --sql tools/db/migrations/migration_90_93.sql

ALTER TABLE rssSources ADD COLUMN ruleRoutes TEXT;
ALTER TABLE rssSources ADD COLUMN ruleEpisodes TEXT;
ALTER TABLE rssSources ADD COLUMN lastHost TEXT;
ALTER TABLE book_sources ADD COLUMN lastHost TEXT;

CREATE TABLE IF NOT EXISTS `playHistories` (`articleUrl` TEXT NOT NULL, `videoUrl` TEXT NOT NULL, `position` INTEGER NOT NULL DEFAULT 0, `duration` INTEGER NOT NULL DEFAULT 0, `lastPlayTime` INTEGER NOT NULL DEFAULT 0, `rssSourceId` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`articleUrl`, `videoUrl`, `rssSourceId`));
