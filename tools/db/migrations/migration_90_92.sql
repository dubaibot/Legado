-- v90 -> v92 组合演练 SQL（多源合并 D7，实库升级测试用）
-- 路径：v90 --(空迁移占位, 无 SQL)--> v91 --(加索引)--> v92
-- 90→91 为无 schema 变更的占位迁移，故本文件只含 91→92 的 SQL。
CREATE INDEX IF NOT EXISTS `idx_origin_sort` ON `rssArticles` (`origin`, `sort`)
