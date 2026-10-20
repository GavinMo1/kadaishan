package dev.sxc.kadaishan.domain.settle

import dev.sxc.kadaishan.model.SnapItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BatchFlow 批次推进与失败降档（PRD §5.2；纯 JVM）。 */
class BatchFlowTest {

    private fun items(n: Int): List<SnapItem> =
        (1..n).map { SnapItem(it.toLong(), "content://x/$it", "f$it.jpg", 100L) }

    @Test
    fun `按 cap 切批并顺序推进，耗尽后 finished`() {
        val flow = BatchFlow(items(7), batchCap = 3)
        assertEquals(3, flow.totalBatches) // 3/3/1
        assertFalse(flow.finished)

        assertEquals(items(3), flow.current())
        flow.advance()
        assertEquals(listOf(items(7)[3], items(7)[4], items(7)[5]), flow.current())
        flow.advance()
        assertEquals(listOf(items(7)[6]), flow.current())
        flow.advance()
        assertTrue(flow.finished)
        assertNull(flow.current())
        flow.advance() // 耗尽后再推进 = 无操作不越界
        assertEquals(3, flow.index)
    }

    @Test
    fun `降档拆批：原位替换为严格更小子批，重发后推进`() {
        val flow = BatchFlow(items(10), batchCap = 10)
        assertEquals(10, flow.current()?.size)

        val result = flow.degradeCurrent()
        assertTrue(result is DegradeResult.Retrying)
        assertEquals(2, flow.totalBatches) // 10 → 5/5
        assertEquals(0, flow.index) // 原位重发
        assertEquals(items(5), flow.current())

        flow.advance()
        assertEquals(items(10).drop(5), flow.current())
    }

    @Test
    fun `降档下限：5 项拆无可拆 → Skipped 摘除并指向下一批`() {
        val flow = BatchFlow(items(7), batchCap = 5) // 5/2
        val result = flow.degradeCurrent()
        assertTrue(result is DegradeResult.Skipped)
        assertEquals(items(5), (result as DegradeResult.Skipped).batch)
        assertEquals(1, flow.totalBatches)
        assertEquals(items(7).drop(5), flow.current()) // index 不动 → 下一批
    }

    @Test
    fun `递归降档必终止：10 → 5+5 → 各 5 拆无可拆`() {
        val flow = BatchFlow(items(10), batchCap = 10)
        assertTrue(flow.degradeCurrent() is DegradeResult.Retrying) // 10 → 5/5
        val r1 = flow.degradeCurrent() // 第一个 5 → Skipped
        assertTrue(r1 is DegradeResult.Skipped)
        val r2 = flow.degradeCurrent() // 第二个 5 → Skipped
        assertTrue(r2 is DegradeResult.Skipped)
        assertTrue(flow.finished)
    }

    @Test
    fun `abortRest：当前批与剩余批全部计失败并终止`() {
        val flow = BatchFlow(items(12), batchCap = 5) // 5/5/2
        flow.abortRest(outgoingSize = 5)
        assertEquals(12, flow.failedCount)
        assertTrue(flow.finished)
    }

    @Test
    fun `countFailed 累计且忽略非正数`() {
        val flow = BatchFlow(items(3), batchCap = 5)
        flow.countFailed(2)
        flow.countFailed(0)
        flow.countFailed(-1)
        flow.countFailed(3)
        assertEquals(5, flow.failedCount)
    }

    @Test
    fun `空池直接 finished`() {
        val flow = BatchFlow(emptyList(), batchCap = 50)
        assertTrue(flow.finished)
        assertNull(flow.current())
        assertEquals(0, flow.totalBatches)
    }
}
