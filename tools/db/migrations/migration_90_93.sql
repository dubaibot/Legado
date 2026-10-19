-- v90 -> v93 组合演练 SQL（多源合并 D9，实库升级测试用）
-- 路径：v90 --(空迁移占位, 无 SQL)--> v91 --(加索引)--> v92 --(rssSources/book_sources 新列 + playHistories 新表)--> v93
-- 90→91 为无 schema 变更的占位迁移；本文件 = migration_90_92.sql + migration_92_93.sql
CREATE INDEX IF NOT EXISTS `idx_origin_sort` ON `rssArticles` (`origin`, `sort`);

ALTER TABLE rssSources ADD COLUMN ruleRoutes TEXT;
ALTER TABLE rssSources ADD COLUMN ruleEpisodes TEXT;
ALTER TABLE rssSources ADD COLUMN lastHost TEXT;
ALTER TABLE book_sources ADD COLUMN lastHost TEXT;

CREATE TABLE IF NOT EXISTS `playHistories` (`articleUrl` TEXT NOT NULL, `videoUrl` TEXT NOT NULL, `position` INTEGER NOT NULL DEFAULT 0, `duration` INTEGER NOT NULL DEFAULT 0, `lastPlayTime` INTEGER NOT NULL DEFAULT 0, `rssSourceId` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`articleUrl`, `videoUrl`, `rssSourceId`));
