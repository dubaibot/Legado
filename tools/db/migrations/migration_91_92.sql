-- v91 -> v92 迁移 SQL 快照（多源合并 D7，清单 2.1.6）
-- rssArticles 增加 idx_origin_sort 复合索引：RSS 视频列表/分类聚合查询命中 (origin, sort)。
-- SQL 以 92.json 中 rssArticles.indices[0].createSql 为准，与 m 源 DatabaseMigrations 93→94 同款。
CREATE INDEX IF NOT EXISTS `idx_origin_sort` ON `rssArticles` (`origin`, `sort`)
