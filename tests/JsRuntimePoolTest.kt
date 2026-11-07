import com.lanyue.reader.core.engine.EngineErrorCode
import com.lanyue.reader.core.engine.EngineException
import com.lanyue.reader.core.engine.EngineState
import com.lanyue.reader.core.engine.PoolTier
import com.lanyue.reader.core.engine.pool.JsRuntimePool
import com.lanyue.reader.core.engine.pool.RunnerInstance
import com.lanyue.reader.core.observability.LocalMetrics
import java.io.File
import kotlinx.coroutines.runBlocking

private var pass = 0
private var fail = 0

private fun eq(label: String, actual: Any?, expected: Any?) {
    if (actual == expected) { pass++; println("[PASS] $label") }
    else { fail++; println("[FAIL] $label -> actual=$actual expected=$expected") }
}

private fun check(label: String, cond: Boolean, detail: String = "") {
    if (cond) { pass++; println("[PASS] $label") }
    else { fail++; println("[FAIL] $label -> $detail") }
}

private var TMP_SEQ = 0

private fun newPool(lowEnd: Boolean = false, timeoutMs: Long = 300L): JsRuntimePool =
    JsRuntimePool(
        LocalMetrics(File("/tmp/opencode/metrics-pool14-${TMP_SEQ++}")),
        lowEndDevice = lowEnd,
        acquireTimeoutMs = timeoutMs
    )

fun main() = runBlocking {
    // ---------- RunnerInstance 状态机 ----------
    val r1 = RunnerInstance("idle", 0)
    eq("初始IDLE", r1.state, EngineState.IDLE)
    eq("初始代数", r1.generation, 1L)
    check("初始可复用", r1.reusable, "${r1.state}")

    check("cas一次成功", r1.casIdleToAcquired())
    eq("cas后ACQUIRED", r1.state, EngineState.ACQUIRED)
    check("cas二次失败", !r1.casIdleToAcquired())
    check("ACQUIRED不可复用", !r1.reusable, "${r1.state}")

    r1.markRunning()
    eq("RUNNING状态", r1.state, EngineState.RUNNING)
    check("RUNNING回IDLE", r1.backToIdle())
    eq("回池后IDLE", r1.state, EngineState.IDLE)
    check("回池可复用", r1.reusable, "${r1.state}")

    // ACQUIRED 直接回 IDLE
    val r2 = RunnerInstance("acq", 0)
    r2.casIdleToAcquired()
    check("ACQUIRED可直接回IDLE", r2.backToIdle())
    eq("回IDLE后", r2.state, EngineState.IDLE)

    // RUNNING -> POISONED
    val r3 = RunnerInstance("pois", 0)
    r3.casIdleToAcquired()
    r3.markRunning()
    r3.poison()
    eq("RUNNING污染后POISONED", r3.state, EngineState.POISONED)
    check("POISONED不可复用", !r3.reusable, "${r3.state}")
    check("回池双向均失效", !r3.backToIdle(), "state=${r3.state}")

    // ACQUIRED -> POISONED
    val r4 = RunnerInstance("pois2", 0)
    r4.casIdleToAcquired()
    r4.poison()
    eq("ACQUIRED污染后POISONED", r4.state, EngineState.POISONED)

    // DISCARDED 终态
    val r5 = RunnerInstance("disc", 0)
    r5.discard()
    eq("销毁后DISCARDED", r5.state, EngineState.DISCARDED)
    check("销毁终态不可复用", !r5.reusable)
    check("销毁终态cas失败", !r5.casIdleToAcquired())

    // SHUTDOWN
    val r6 = RunnerInstance("shut", 0)
    r6.casIdleToAcquired()
    r6.shutdown()
    eq("关闭SHUTDOWN", r6.state, EngineState.SHUTDOWN)
    check("关闭不可复用", !r6.reusable)

    // bumpGeneration
    val r7 = RunnerInstance("gen", 0)
    r7.discard()
    r7.bumpGeneration()
    eq("gen bump++", r7.generation, 2L)

    // ---------- 池基本收发 ----------
    val poolA = newPool()
    val ia = poolA.acquire(PoolTier.HIGH, "A1")
    eq("取回ACQUIRED", ia.state, EngineState.ACQUIRED)
    eq("高优tier", ia.tier, PoolTier.HIGH.ordinal)
    poolA.release(ia)
    eq("释放后IDLE", ia.state, EngineState.IDLE)
    val ia2 = poolA.acquire(PoolTier.HIGH, "A2")
    eq("复取同实例", ia2.id, ia.id)
    poolA.release(ia2)

    val ib = poolA.acquire(PoolTier.NORMAL, "A3")
    eq("普通tier", ib.tier, PoolTier.NORMAL.ordinal)
    poolA.release(ib)

    // ---------- 同层耗尽：子池不互借 ----------
    val poolB = newPool()
    val heldB = poolB.acquire(PoolTier.HIGH, "B1")
    var errB: EngineException? = null
    try { poolB.acquire(PoolTier.HIGH, "B2") } catch (t: EngineException) { errB = t }
    check("耗尽为EngineException", errB != null, "err=$errB")
    eq("耗尽码POOL_EXHAUSTED", errB?.code, EngineErrorCode.POOL_EXHAUSTED)
    eq("耗尽消息含池名", errB?.message, "no instance in HIGH pool")
    // 普通池不受高档耗尽影响
    val nb = poolB.acquire(PoolTier.NORMAL, "B3")
    eq("普通池正常取用", nb.tier, PoolTier.NORMAL.ordinal)
    poolB.release(nb)
    poolB.release(heldB)
    val retake = poolB.acquire(PoolTier.HIGH, "B4")
    eq("释放后可再取", retake.state, EngineState.ACQUIRED)
    poolB.release(retake)

    // ---------- 低档模式：NORMAL 共享高档池 ----------
    val poolC = newPool(lowEnd = true)
    val lowC = poolC.acquire(PoolTier.NORMAL, "C1")
    eq("低档共享高档", lowC.tier, PoolTier.HIGH.ordinal)
    var errC: EngineException? = null
    try { poolC.acquire(PoolTier.NORMAL, "C2") } catch (t: EngineException) { errC = t }
    eq("低档也耗尽", errC?.code, EngineErrorCode.POOL_EXHAUSTED)
    poolC.release(lowC)

    // ---------- release POISONED：销毁不回池 ----------
    val poolD = newPool()
    val heldD = poolD.acquire(PoolTier.HIGH, "D1")
    heldD.poison()
    poolD.release(heldD)
    eq("poison释放销毁", heldD.state, EngineState.DISCARDED)
    var errD: EngineException? = null
    try { poolD.acquire(PoolTier.HIGH, "D2") } catch (t: EngineException) { errD = t }
    eq("销毁后继续耗尽", errD?.code, EngineErrorCode.POOL_EXHAUSTED)

    // ---------- Reaper 超时剔除 ----------
    val poolE = newPool()
    val rep = poolE.acquire(PoolTier.HIGH, "E1")
    rep.markRunning()
    poolE.reap(rep, taskTimeoutMs = 1_000L, elapsedMs = 1_999L)
    eq("未超不剔除", rep.state, EngineState.RUNNING)
    eq("未超代数不变", rep.generation, 1L)
    poolE.reap(rep, taskTimeoutMs = 1_000L, elapsedMs = 2_001L)
    eq("超2倍剔除", rep.state, EngineState.DISCARDED)
    eq("剔除后代数++", rep.generation, 2L)
    eq("剔除不可复用", rep.reusable, false)

    // ---------- 串行化与池复用 ----------
    val poolF = newPool()
    val hi1 = poolF.acquire(PoolTier.HIGH, "H1")
    var errF: EngineException? = null
    try { poolF.acquire(PoolTier.HIGH, "H2") } catch (t: EngineException) { errF = t }
    eq("占用中二次获取耗尽", errF?.code, EngineErrorCode.POOL_EXHAUSTED)
    check("取用实例不可复用", !hi1.reusable, "state=${hi1.state}")
    poolF.release(hi1)
    eq("释放后回到IDLE", hi1.state, EngineState.IDLE)
    val hi3 = poolF.acquire(PoolTier.HIGH, "H3")
    eq("串行复用同实例", hi3.id, hi1.id)
    poolF.release(hi3)

    val poolG = newPool()
    val ng = poolG.acquire(PoolTier.NORMAL, "G1")
    eq("普通池取实例", ng.tier, PoolTier.NORMAL.ordinal)
    poolG.release(ng)

    // ---------- 缩容 / 低内存 ----------
    val poolH = newPool()
    val keep = poolH.acquire(PoolTier.HIGH, "H4")
    val tail = poolH.acquire(PoolTier.NORMAL, "H5")
    poolH.release(keep)
    poolH.release(tail)
    poolH.shrinkOnBackground()
    eq("缩容保留第一个", keep.state, EngineState.IDLE)
    eq("缩容裁掉第二个", tail.state, EngineState.DISCARDED)
    poolH.onLowMemory()
    eq("低内存回收第一个", keep.state, EngineState.DISCARDED)

    println("===== JS 运行时池与 RunnerInstance =====")
    println("PASS = $pass   FAIL = $fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
