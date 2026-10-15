# D6 报告：并行工具与规则缓存

> 执行日：2026-10-15。对应排期 D6（清单 2.2.1 / 2.2.3）。分支 `261015-feat-d6-parallel-caches`（基座含 D1–D5 合并）。

## 完成项

1. **FlowExtensions 守卫修复**（对齐 m 源演进）：`onEachParallelSafe`、`mapParallelSafe`、`transformParallelSafe` 三处补 `CancellationException` 重新抛出守卫——原实现吞掉取消异常会破坏协程取消传播。函数集合与 m 源已完全一致。
2. **五个缓存类新增**（`help/source/`，对齐 m 源）：
   - `BookSourceStorageScope`：源 URL → SHA-256 命名空间（纯 JVM）
   - `BookSourceFileAccessPolicy`：书源文件沙箱访问策略，防路径穿越/符号链接逃逸（纯 JVM）
   - `BookSourceCacheStore`：JS 脚本缓存按源隔离（DB 前缀键 + 内存前缀键 + 文件专属目录三处），开关默认关
   - `SourceQueryCache`（含 `TtlEpochCache`）：书源查询 2s TTL + epoch 短时缓存
   - `SourcePreconnectHelper`：列表/目录加载后对前 N 个 URL 并行 HEAD 预连接
3. **配套适配**：
   - `CacheDao.deleteByPrefix`、`CacheManager.deleteMemoryByPrefix` 新增（m 源依赖）
   - `AppConfig` 新增 `sourceQueryCacheEnabled`（默认开）、`bookSourceCacheScoped`（默认关），实时读偏好；PreferKey 补两个常量
   - `HttpHelper.warmUpConnection` 移植（工作区 AppLog 无 putSampled，改为手动 1/20 采样计数）
4. **SourceHelp 接线**：
   - 读路径：两个 `getSource` 重载包进 `SourceQueryCache.get("any:/book:/rss:")`
   - 失效出口 6 处：删除书源/订阅源（internal 两处）、`enableSource`、`insertRssSource`、`insertBookSource`
   - 删源联动 `BookSourceCacheStore.clear`（runCatching 包裹，失败不阻断删源）

## 与 m 源的偏差记录

| 偏差 | 原因 | 后续 |
|------|------|------|
| AppLog.putDebugWithTag/putWarn → AppLog.put | 工作区 AppLog 为精简版（m 源的分级/采样/Tag 日志属 m 自有演进） | 若后续引入 m 日志体系再统一 |
| sourceQueryCacheEnabled 用 `get()` 实时读 | m 为 var + 监听刷新；实时读免去监听接线且语义一致 | 保持现状 |
| m 删除逻辑中的回收站/ConcurrentRateLimiter/会话缓存接线未带 | 属 m 其他工作项（B7/F-P1-C4 等），超出 D6 范围 | 对应功能移植日再接线 |

## 遗留（后续批次补齐）

- `SourceQueryCache.invalidate()` 六类写源入口中，Restore（备份恢复）、ImportOldData、SourceRecycleBinHelp、两个 SourceEditViewModel 未接线（SourceHelp 已覆盖增删改主路径；TTL 2s 兜底自愈，风险低）
- `scriptCacheObject()`（AnalyzeRule/AnalyzeUrl JS cache 绑定接线）与 `BookSourceFileAccessPolicy` 消费方属 m 的沙箱/脚本隔离功能，随对应批次移植
- `SourcePreconnectHelper` 消费方（Rss.kt / BookChapterList 预连接调用）在 D11（2.2.2 订阅源并行化）接线

## 验证

- `assembleDebug` BUILD SUCCESSFUL（5m 2s，增量）
- Room schema 无变化（仅新增 DAO 方法，无实体/版本变更，无需迁移）
