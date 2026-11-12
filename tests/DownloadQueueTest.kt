import com.lanyue.reader.source.download.DownloadItem
import com.lanyue.reader.source.download.DownloadQueueConfig
import com.lanyue.reader.source.download.DownloadQueueCoordinator
import com.lanyue.reader.source.download.DownloadStatus

private var pass = 0
private val failures = ArrayList<String>()
private val outFile = java.io.File("/mnt/work/lanyue32/out_download.txt")

private fun eq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) pass++ else failures += "$name\n  期望: $expected\n  实际: $actual"
}

private fun ok(name: String, cond: Boolean) {
    if (cond) pass++ else failures += name
}

private fun report() {
    println("PASS = $pass")
    println("FAIL = ${failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEachIndexed { i, f -> println("${i + 1}. $f") }
        outFile.writeText(failures.joinToString("\n\n"))
        kotlin.system.exitProcess(1)
    }
}

/** 构造下载项 */
private fun item(id: String, title: String = "第${id}章") =
    DownloadItem(id = id, url = "https://example.com/$id", targetPath = "/data/$id.txt", title = title)

fun main() {

    // ============ 1. 基本入队与快照 ============
    run {
        val q = DownloadQueueCoordinator()
        val added = q.enqueue(listOf(item("c1"), item("c2"), item("c3")))
        eq("新增 3 项", added, 3)
        val s = q.snapshot()
        eq("总项数", s.totalItems, 3)
        eq("等待中", s.pendingCount, 3)
        eq("下载中", s.downloadingCount, 0)
        eq("完成", s.completedCount, 0)
        ok("未全部完成", !s.isAllDone)
    }

    // ============ 2. 跳过已下载（preloadCompleted + enqueue） ============
    run {
        val q = DownloadQueueCoordinator()
        q.preloadCompleted(setOf("c1", "c2"))
        val added = q.enqueue(listOf(item("c1"), item("c2"), item("c3")))
        eq("跳过已下载只新增 1", added, 1)
        ok("c1 已完成", q.isCompleted("c1"))
        ok("c2 已完成", q.isCompleted("c2"))
        ok("c3 未完成", !q.isCompleted("c3"))
        eq("队列只有 c3", q.snapshot().totalItems, 1)
    }

    // ============ 3. 跳过重复入队 ============
    run {
        val q = DownloadQueueCoordinator()
        q.enqueue(listOf(item("c1"), item("c2")))
        val added2 = q.enqueue(listOf(item("c1"), item("c3")))
        eq("第二次只新增 c3", added2, 1)
        eq("队列共 3 项", q.snapshot().totalItems, 3)
    }

    // ============ 4. startNext：标记 DOWNLOADING、返回正确项 ============
    run {
        val q = DownloadQueueCoordinator()
        q.enqueue(listOf(item("c1"), item("c2")))
        val next = q.startNext(now = 0L)
        ok("返回非空", next != null)
        eq("标记 DOWNLOADING", next!!.status, DownloadStatus.DOWNLOADING)
        eq("是 c1（先入先出）", next.item.id, "c1")
        eq("尝试次数 1", next.attempts, 1)
        val s = q.snapshot()
        eq("等待中减 1", s.pendingCount, 1)
        eq("下载中 1", s.downloadingCount, 1)
    }

    // ============ 5. startNext：并发上限 ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(maxConcurrent = 2))
        q.enqueue(listOf(item("c1"), item("c2"), item("c3")))
        val n1 = q.startNext(now = 0L)
        val n2 = q.startNext(now = 0L)
        val n3 = q.startNext(now = 0L)
        ok("第一个开始", n1 != null)
        ok("第二个开始", n2 != null)
        eq("第三个被并发上限拦住", n3, null)
        eq("下载中 2", q.snapshot().downloadingCount, 2)
    }

    // ============ 6. startNext：退避到期才返回 ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(initialBackoffMs = 1000))
        q.enqueue(listOf(item("c1")))
        // 首次下载
        val n1 = q.startNext(now = 0L)
        ok("首次可下载", n1 != null)
        // 失败 → 退避 1000ms
        val willRetry = q.finishFailure("c1", "timeout", now = 0L)
        ok("安排重试", willRetry)
        // 在退避期内 → startNext 不应返回此项
        val n2 = q.startNext(now = 500L)
        eq("退避期内不返回", n2, null)
        // 退避到期 → 可返回
        val n3 = q.startNext(now = 1000L)
        ok("退避到期可返回", n3 != null)
        eq("第二次尝试 attempts=2", n3!!.attempts, 2)
    }

    // ============ 7. startNext：多个候选取 nextRetryAt 最早的 ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(maxConcurrent = 1))
        q.enqueue(listOf(item("c1"), item("c2")))
        // c1 先下载失败，退避到 2000
        val n1 = q.startNext(now = 0L)
        eq("先取 c1", n1!!.item.id, "c1")
        q.finishFailure("c1", "err", now = 0L)  // nextRetryAt = 0 + 1000 = 1000
        // c2 下载失败，退避到 2000（attempts=1, backoff=1000, now=2000）
        val n2 = q.startNext(now = 2000L)  // c1 的 nextRetryAt=1000 <= 2000，c2 的 nextRetryAt=0 <= 2000
        // 两者都到期，取 nextRetryAt 最早的：c1=1000, c2=0 → c2 更早
        eq("取 nextRetryAt 最早的", n2!!.item.id, "c2")
    }

    // ============ 8. startNext：空队列返回 null ============
    run {
        val q = DownloadQueueCoordinator()
        eq("空队列返回 null", q.startNext(), null)
    }

    // ============ 9. finishSuccess：标记完成、加入 completedIds ============
    run {
        val q = DownloadQueueCoordinator()
        q.enqueue(listOf(item("c1")))
        q.startNext(now = 0L)
        q.finishSuccess("c1", 1024L)
        val s = q.snapshot()
        eq("完成数 1", s.completedCount, 1)
        eq("下载中 0", s.downloadingCount, 0)
        eq("已下载字节", s.totalDownloadedBytes, 1024L)
        ok("isCompleted", q.isCompleted("c1"))
    }

    // ============ 10. finishFailure：首次失败安排重试 ============
    run {
        val q = DownloadQueueCoordinator()
        q.enqueue(listOf(item("c1")))
        q.startNext(now = 0L)
        val willRetry = q.finishFailure("c1", "network error", now = 100L)
        ok("会重试", willRetry)
        val s = q.snapshot()
        eq("回到 PENDING", s.pendingCount, 1)
        eq("下载中 0", s.downloadingCount, 0)
        eq("失败数 0（未耗尽）", s.failedCount, 0)
    }

    // ============ 11. finishFailure：退避计算（指数） ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(
            maxRetries = 3, initialBackoffMs = 1000, maxBackoffMs = 30_000
        ))
        q.enqueue(listOf(item("c1")))
        // 第一次失败（attempts=1, backoff=1000×2^0=1000）
        q.startNext(now = 0L)
        q.finishFailure("c1", "e1", now = 0L)
        ok("第一次退避后可立即取", q.startNext(now = 1000L) != null)
        // 第二次失败（attempts=2, backoff=1000×2^1=2000）
        q.finishFailure("c1", "e2", now = 1000L)
        eq("退避 2000 内不可取", q.startNext(now = 2500L), null)
        ok("退避 2000 后可取", q.startNext(now = 3000L) != null)
        // 第三次失败（attempts=3, backoff=1000×2^2=4000）
        q.finishFailure("c1", "e3", now = 3000L)
        eq("退避 4000 内不可取", q.startNext(now = 6000L), null)
        ok("退避 4000 后可取", q.startNext(now = 7000L) != null)
    }

    // ============ 12. finishFailure：重试耗尽标记 FAILED ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(maxRetries = 2))
        q.enqueue(listOf(item("c1")))
        // 首次尝试 (attempts=1)
        q.startNext(now = 0L)
        q.finishFailure("c1", "e1", now = 0L)  // retry 1 → backoff
        // 第一次重试 (attempts=2)
        q.startNext(now = 1000L)
        q.finishFailure("c1", "e2", now = 1000L)  // retry 2 → backoff
        // 第二次重试 (attempts=3) → 这是最后一次（maxRetries=2, 总尝试=3）
        q.startNext(now = 3000L)
        val willRetry = q.finishFailure("c1", "e3", now = 3000L)
        ok("重试耗尽不重试", !willRetry)
        eq("标记 FAILED", q.snapshot().failedCount, 1)
        eq("PENDING 为 0", q.snapshot().pendingCount, 0)
    }

    // ============ 13. cancel：单个取消 ============
    run {
        val q = DownloadQueueCoordinator()
        q.enqueue(listOf(item("c1"), item("c2")))
        q.startNext(now = 0L)  // c1 下载中
        q.cancel("c1")
        q.cancel("c2")
        val s = q.snapshot()
        eq("c1 取消", s.cancelledCount, 2)
        eq("无下载中", s.downloadingCount, 0)
        eq("无等待", s.pendingCount, 0)
    }

    // ============ 14. cancelAll：取消所有未完成 ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(maxConcurrent = 2))
        q.enqueue(listOf(item("c1"), item("c2"), item("c3")))
        q.startNext(now = 0L)  // c1 下载中
        q.startNext(now = 0L)  // c2 下载中
        q.finishSuccess("c1", 100L)
        // 现在 c1 完成, c2 下载中, c3 等待
        q.cancelAll()
        val s = q.snapshot()
        eq("完成的不取消", s.completedCount, 1)
        eq("下载中变取消", s.cancelledCount, 2)
        eq("无下载中", s.downloadingCount, 0)
    }

    // ============ 15. retry：手动重试失败项 ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(maxRetries = 0))
        q.enqueue(listOf(item("c1")))
        q.startNext(now = 0L)
        q.finishFailure("c1", "fatal", now = 0L)  // maxRetries=0 → 直接 FAILED
        eq("已失败", q.snapshot().failedCount, 1)
        ok("retry 成功", q.retry("c1"))
        eq("回到 PENDING", q.snapshot().pendingCount, 1)
        eq("失败数归 0", q.snapshot().failedCount, 0)
        ok("可立即取", q.startNext(now = 0L) != null)
    }

    // ============ 16. retry：非失败项不能 retry ============
    run {
        val q = DownloadQueueCoordinator()
        q.enqueue(listOf(item("c1")))
        ok("PENDING 不可 retry", !q.retry("c1"))
        q.startNext(now = 0L)
        ok("DOWNLOADING 不可 retry", !q.retry("c1"))
        q.finishSuccess("c1", 100L)
        ok("COMPLETED 不可 retry", !q.retry("c1"))
    }

    // ============ 17. isAllDone：全部完成 ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(maxConcurrent = 2))
        q.enqueue(listOf(item("c1"), item("c2")))
        q.startNext(now = 0L)
        q.startNext(now = 0L)
        q.finishSuccess("c1", 100L)
        ok("还有一项未完成", !q.isAllDone())
        q.finishSuccess("c2", 200L)
        ok("全部完成", q.isAllDone())
    }

    // ============ 18. isAllDone：有失败项也算完成 ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(maxRetries = 0))
        q.enqueue(listOf(item("c1")))
        q.startNext(now = 0L)
        q.finishFailure("c1", "err", now = 0L)
        ok("失败也算全部处理完", q.isAllDone())
    }

    // ============ 19. snapshot：summary 文案 ============
    run {
        val q = DownloadQueueCoordinator()
        eq("空队列文案", q.snapshot().summary(), "队列为空")
        q.enqueue(listOf(item("c1"), item("c2")))
        ok("进行中文案含等待", q.snapshot().summary().contains("等待"))
        q.startNext(now = 0L)
        q.finishSuccess("c1", 100L)
        q.startNext(now = 0L)
        q.finishSuccess("c2", 200L)
        ok("全完成文案", q.snapshot().summary().contains("全部完成"))
    }

    // ============ 20. snapshot：失败时文案含失败数 ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(maxRetries = 0))
        q.enqueue(listOf(item("c1"), item("c2")))
        q.startNext(now = 0L)
        q.finishSuccess("c1", 100L)
        q.startNext(now = 0L)
        q.finishFailure("c2", "err", now = 0L)
        val s = q.snapshot()
        ok("部分完成文案含完成和失败", s.summary().contains("完成") && s.summary().contains("失败"))
    }

    // ============ 21. clearHistory：清除已完成和已取消 ============
    run {
        val q = DownloadQueueCoordinator()
        q.enqueue(listOf(item("c1"), item("c2"), item("c3")))
        q.startNext(now = 0L)
        q.finishSuccess("c1", 100L)
        q.cancel("c2")
        // c3 仍 PENDING
        q.clearHistory()
        val s = q.snapshot()
        eq("只剩 c3", s.totalItems, 1)
        eq("c3 仍等待", s.pendingCount, 1)
    }

    // ============ 22. clearHistory：保留失败项 ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(maxRetries = 0))
        q.enqueue(listOf(item("c1")))
        q.startNext(now = 0L)
        q.finishFailure("c1", "err", now = 0L)
        q.clearHistory()
        eq("失败项保留", q.snapshot().failedCount, 1)
    }

    // ============ 23. 边界：越界 ID ============
    run {
        val q = DownloadQueueCoordinator()
        q.enqueue(listOf(item("c1")))
        q.finishSuccess("nope", 100L)  // 不存在 → 不崩
        q.finishFailure("nope", "err")  // 不存在 → 不崩
        q.cancel("nope")  // 不存在 → 不崩
        ok("retry 不存在返回 false", !q.retry("nope"))
        ok("isCompleted 不存在返回 false", !q.isCompleted("nope"))
    }

    // ============ 24. 退避上限：不超过 maxBackoffMs ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(
            maxRetries = 10, initialBackoffMs = 1000, maxBackoffMs = 5000
        ))
        q.enqueue(listOf(item("c1")))
        // 连续失败，退避应被夹到 5000
        var now = 0L
        var lastNextRetry = 0L
        for (i in 1..6) {
            val n = q.startNext(now = now)
            ok("第${i}次可取", n != null)
            q.finishFailure("c1", "e", now = now)
            val entry = q.snapshot().entries[0]
            val backoff = entry.nextRetryAt - now
            ok("第${i}次退避=${backoff}ms 不超 5000", backoff <= 5000)
            now = entry.nextRetryAt
        }
    }

    // ============ 25. 多次入队累加 ============
    run {
        val q = DownloadQueueCoordinator()
        eq("第一批 2", q.enqueue(listOf(item("c1"), item("c2"))), 2)
        eq("第二批 3", q.enqueue(listOf(item("c3"), item("c4"), item("c5"))), 3)
        eq("第三批含重复只新增 1", q.enqueue(listOf(item("c1"), item("c6"))), 1)
        eq("队列共 6 项", q.snapshot().totalItems, 6)
    }

    // ============ 26. config 参数校验 ============
    run {
        var threw = false
        try { DownloadQueueCoordinator(DownloadQueueConfig(maxConcurrent = 0)) }
        catch (e: IllegalArgumentException) { threw = true }
        ok("maxConcurrent=0 拒绝", threw)

        threw = false
        try { DownloadQueueCoordinator(DownloadQueueConfig(maxRetries = -1)) }
        catch (e: IllegalArgumentException) { threw = true }
        ok("maxRetries=-1 拒绝", threw)

        threw = false
        try { DownloadQueueCoordinator(DownloadQueueConfig(initialBackoffMs = 0)) }
        catch (e: IllegalArgumentException) { threw = true }
        ok("initialBackoffMs=0 拒绝", threw)

        threw = false
        try { DownloadQueueCoordinator(DownloadQueueConfig(maxBackoffMs = 0)) }
        catch (e: IllegalArgumentException) { threw = true }
        ok("maxBackoffMs=0 拒绝", threw)
    }

    // ============ 27. maxRetries=0：首次失败即 FAILED ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(maxRetries = 0))
        q.enqueue(listOf(item("c1")))
        q.startNext(now = 0L)
        val willRetry = q.finishFailure("c1", "first fail", now = 0L)
        ok("maxRetries=0 不重试", !willRetry)
        eq("直接 FAILED", q.snapshot().failedCount, 1)
    }

    // ============ 28. 端到端：批量下载全流程 ============
    run {
        val q = DownloadQueueCoordinator(DownloadQueueConfig(maxConcurrent = 2, maxRetries = 2))
        q.enqueue(listOf(
            item("c1"), item("c2"), item("c3"), item("c4"), item("c5")
        ))
        eq("入队 5", q.snapshot().totalItems, 5)

        // 模拟下载循环
        var now = 0L
        val results = HashMap<String, Boolean>()  // id → success
        results["c1"] = true
        results["c2"] = false  // 会失败并重试
        results["c3"] = true
        results["c4"] = false  // 会失败到耗尽
        results["c5"] = true

        var guard = 0
        while (!q.isAllDone() && guard++ < 50) {
            val next = q.startNext(now = now)
            if (next == null) {
                now += 5000  // 推进时间让退避到期
                continue
            }
            val id = next.item.id
            if (results[id] == true) {
                q.finishSuccess(id, 1024L)
            } else {
                // c2 第一次失败后成功，c4 一直失败
                if (id == "c2" && next.attempts >= 2) {
                    q.finishSuccess(id, 2048L)
                } else {
                    q.finishFailure(id, "simulated fail", now = now)
                }
            }
        }
        ok("循环有界未死锁", guard < 50)
        val s = q.snapshot()
        eq("完成 4", s.completedCount, 4)
        eq("失败 1（c4）", s.failedCount, 1)
        ok("全部处理完", s.isAllDone)
        eq("总字节 4×1024 + 2048", s.totalDownloadedBytes, 1024L * 3 + 2048L)
    }

    // ============ 29. preloadCompleted 后 enqueue 同 ID 不重复入队 ============
    run {
        val q = DownloadQueueCoordinator()
        q.preloadCompleted(setOf("c1"))
        eq("enqueue 已完成项返回 0", q.enqueue(listOf(item("c1"))), 0)
        eq("队列为空", q.snapshot().totalItems, 0)
    }

    // ============ 30. finishSuccess 后同 ID 再 enqueue 仍跳过 ============
    run {
        val q = DownloadQueueCoordinator()
        q.enqueue(listOf(item("c1")))
        q.startNext(now = 0L)
        q.finishSuccess("c1", 100L)
        // 完成后再 enqueue 同 ID
        eq("完成后同 ID 不入队", q.enqueue(listOf(item("c1"))), 0)
    }

    report()
}
