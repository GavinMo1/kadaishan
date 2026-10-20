package dev.sxc.kadaishan.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD §4.1/§4.4 池子与轮次语义 + 架构 §2 BoardSession（纯 JVM，断言具体值）。 */
class BoardSessionTest {

    private fun ids(n: Int): List<Long> = (1..n).map { it.toLong() }

    @Test
    fun `displayed items never re-enter this round or later rounds`() {
        val s = BoardSession(ids(9), 3)

        s.mark(1L)
        assertEquals(2L, s.current!!)
        s.skip(2L)
        assertEquals(3L, s.current!!)
        s.advance()
        assertTrue(s.roundEnded())
        assertNull(s.current) // 轮末不泄漏下一条

        s.startNewRound()
        assertEquals(4L, s.current!!)
        s.skip(4L)
        s.mark(5L)
        s.advance()
        assertTrue(s.roundEnded())

        s.startNewRound()
        // 第三轮 = 7、8、9：1..6 无一重入；跳过项 2、4 跨轮不再出现
        assertEquals(7L, s.current!!)
        s.advance()
        assertEquals(8L, s.current!!)
        s.advance()
        assertEquals(9L, s.current!!)
        s.advance()
        assertTrue(s.roundEnded())
        assertNull(s.current)
    }

    @Test
    fun `undo rewinds consecutive mark and skip back to round start`() {
        val s = BoardSession(ids(10), 5)

        s.mark(1L)
        s.skip(2L)
        s.mark(3L)
        assertEquals(4L, s.current!!)
        assertEquals(2, s.markedCount)
        assertEquals(setOf(1L, 3L), s.markedIds)

        assertTrue(s.undo())
        assertEquals(3L, s.current!!)
        assertEquals(1, s.markedCount)
        assertEquals(setOf(1L), s.markedIds)

        assertTrue(s.undo())
        assertEquals(2L, s.current!!) // 撤回跳过 = 重新入池（回退可再滑）

        assertTrue(s.undo())
        assertEquals(1L, s.current!!)
        assertEquals(0, s.markedCount)
        assertTrue(s.markedIds.isEmpty())

        assertFalse(s.undo()) // 栈空 = 回到本轮开头（按钮置灰判据）
        assertEquals(1L, s.current!!)
    }

    @Test
    fun `undoing a skip makes the item displayable and actionable again`() {
        val s = BoardSession(ids(3), 2)

        s.skip(1L)
        assertEquals(2L, s.current!!)
        assertTrue(s.undo())
        assertEquals(1L, s.current!!) // 重新入池
        s.mark(1L) // 可再次操作
        assertEquals(2L, s.current!!)
        assertEquals(1, s.markedCount)
    }

    @Test
    fun `undo rewinds to the acted card even with plain advances after it`() {
        val s = BoardSession(listOf(5L, 6L, 7L, 8L, 9L, 10L), 5)

        s.mark(5L)
        assertEquals(6L, s.current!!)
        s.advance() // 普通过卡（视频上滑）
        assertEquals(7L, s.current!!)

        assertTrue(s.undo()) // 撤回标记 5：连同其后的 advance 一并回退
        assertEquals(5L, s.current!!)
        assertEquals(0, s.markedCount)
        assertFalse(s.roundEnded()) // 消耗数同步回退

        assertFalse(s.undo()) // 栈空
        assertEquals(5L, s.current!!)
    }

    @Test
    fun `unmark cancels a buried mark without moving the pointer, undo still targets top`() {
        val s = BoardSession(ids(6), 5)

        s.mark(1L) // at=0 → 指针 1
        s.mark(2L) // at=1 → 指针 2
        assertEquals(2, s.markedCount)
        assertEquals(3L, s.current!!)

        assertTrue(s.unmark(1L)) // 回看取消 1：中部摘帧，指针不动
        assertEquals(3L, s.current!!)
        assertEquals(setOf(2L), s.markedIds)
        assertEquals(1, s.markedCount)
        assertFalse(s.roundEnded()) // 轮计数不受影响

        assertFalse(s.unmark(1L)) // 已未标记 → 无操作
        assertFalse(s.unmark(9L)) // 从未标记 → 无操作

        assertTrue(s.undo()) // 栈顶只剩 Mark(2) → 撤的是 2
        assertEquals(2L, s.current!!)
        assertEquals(0, s.markedCount)

        assertFalse(s.undo()) // Mark(1) 帧已摘除 → 栈空
        assertEquals(2L, s.current!!)
    }

    @Test
    fun `round size 1 and 300 accepted, 0 and 301 rejected with IAE`() {
        assertEquals(1, BoardSession(ids(3), 1).roundSize)
        assertEquals(300, BoardSession(ids(3), 300).roundSize)
        assertThrows(IllegalArgumentException::class.java) { BoardSession(ids(3), 0) }
        assertThrows(IllegalArgumentException::class.java) { BoardSession(ids(3), 301) }
    }

    @Test
    fun `updateRoundSize 热生效：放大继续、缩到已耗值以内立即轮末、轮末放大复活本轮`() {
        val s = BoardSession(ids(9), 5)
        s.mark(1L)
        s.skip(2L)
        s.advance() // 已消耗 3

        s.updateRoundSize(8) // 放大：本轮继续
        assertFalse(s.roundEnded())
        assertEquals(4L, s.current!!)
        assertEquals(8, s.roundSize)

        s.updateRoundSize(3) // 缩到已消耗值：本轮立即结束（不超额）
        assertTrue(s.roundEnded())
        assertNull(s.current)
        assertEquals(3, s.consumed)

        s.updateRoundSize(6) // 轮末再放大：本轮继续（消耗护栏 == 判定成立）
        assertFalse(s.roundEnded())
        assertEquals(4L, s.current!!)
        assertEquals(6, s.roundSize)

        assertThrows(IllegalArgumentException::class.java) { s.updateRoundSize(0) }
        assertThrows(IllegalArgumentException::class.java) { s.updateRoundSize(301) }
        assertEquals(6, s.roundSize) // 非法值不改现值
    }

    @Test
    fun `updateRoundSize 缩到严格低于已消耗值：本轮立即结束（== 语义的回归护栏）`() {
        // 回归场景：每轮 20 滑了 15 张，设置页把滑条拖到 10 —— consumed(15) 恒不等于 10，
        // == 语义下本轮永不结束（结算不再触发）；>= 语义下立即轮末。
        val s = BoardSession(ids(9), 5)
        s.advance()
        s.advance()
        s.advance() // 已消耗 3

        s.updateRoundSize(2) // 缩到严格低于已消耗值
        assertTrue(s.roundEnded())
        assertNull(s.current) // 轮末不泄漏下一条
        assertEquals(3, s.consumed) // 不回拨、不超额重置

        s.updateRoundSize(6) // 轮末再放大：本轮复活（consumed < 新值）
        assertFalse(s.roundEnded())
        assertEquals(4L, s.current!!)
    }

    @Test
    fun `startNewRound 沿用热调后的 roundSize 且标记撤回栈保留到收尾`() {
        val s = BoardSession(ids(6), 5)
        s.mark(1L)
        s.updateRoundSize(2)
        s.skip(2L) // 已消耗 2 = 热调后的轮值 → 轮末（标记 1 保留）
        assertTrue(s.roundEnded())
        assertEquals(listOf(1L), s.markedIds.toList())

        s.startNewRound() // 新轮沿用 roundSize=2
        assertEquals(2, s.roundSize)
        s.advance()
        assertFalse(s.roundEnded()) // 1 条 ≠ 2 条
        s.advance()
        assertTrue(s.roundEnded())
    }

    @Test
    fun `roundEnded is exact at roundSize`() {
        val s = BoardSession(ids(4), 2)

        assertFalse(s.roundEnded())
        s.mark(1L)
        assertFalse(s.roundEnded()) // 恰好差一条
        s.skip(2L)
        assertTrue(s.roundEnded()) // 恰好 roundSize 条
        assertNull(s.current)

        s.startNewRound()
        assertFalse(s.roundEnded())
        assertEquals(3L, s.current!!)
    }

    @Test
    fun `startNewRound drops previous marks and undo stack`() {
        val s = BoardSession(ids(6), 2)

        s.mark(1L)
        s.skip(2L)
        assertTrue(s.roundEnded())
        assertEquals(listOf(1L), s.markedIds.toList())
        assertEquals(1, s.markedCount)

        s.startNewRound()
        assertEquals(0, s.markedCount)
        assertTrue(s.markedIds.isEmpty())
        assertFalse(s.undo()) // 上轮撤回栈已清空
        assertEquals(3L, s.current!!) // 指针不回拨：1、2 不重入
    }

    @Test
    fun `markedCount rolls back with each undo`() {
        val s = BoardSession(ids(10), 5)

        s.mark(1L)
        s.mark(2L)
        s.mark(3L)
        assertEquals(listOf(1L, 2L, 3L), s.markedIds.toList())
        assertEquals(3, s.markedCount)
        assertEquals(4L, s.current!!)

        assertTrue(s.undo())
        assertEquals(2, s.markedCount)
        assertEquals(listOf(1L, 2L), s.markedIds.toList())
        assertTrue(s.undo())
        assertEquals(1, s.markedCount)
        assertTrue(s.undo())
        assertEquals(0, s.markedCount)
        assertFalse(s.undo())
        assertEquals(1L, s.current!!)
    }

    @Test
    fun `old-card mark unmark re-mark keeps pointer and count and undo pops top only`() {
        val s = BoardSession(listOf(1L, 2L, 3L, 4L, 5L), 4)

        s.mark(1L) // 消耗 → 指针 1
        s.mark(2L) // 消耗 → 指针 2
        s.advance() // 浏览 3 → 指针 3，3 已展示未标记
        assertEquals(4L, s.current!!)
        assertFalse(s.roundEnded())

        s.mark(3L) // 回看标记 3：纯状态，不二次消耗
        assertEquals(setOf(1L, 2L, 3L), s.markedIds)
        assertEquals(3, s.markedCount)
        assertEquals(4L, s.current!!) // 指针不动
        assertFalse(s.roundEnded()) // 轮计数不动

        s.mark(3L) // 重复标记幂等
        assertEquals(3, s.markedCount)

        assertTrue(s.unmark(3L))
        assertEquals(setOf(1L, 2L), s.markedIds)
        assertEquals(4L, s.current!!)
        assertFalse(s.roundEnded())

        s.mark(3L) // 再标记
        assertEquals(setOf(1L, 2L, 3L), s.markedIds)
        assertEquals(3, s.markedCount)
        assertEquals(4L, s.current!!)
        assertFalse(s.roundEnded())

        assertTrue(s.undo()) // 撤栈顶 = Mark(3)，1/2 不受误伤
        assertEquals(setOf(1L, 2L), s.markedIds)
        assertEquals(2, s.markedCount)
        assertEquals(3L, s.current!!) // 拨回被撤项 3
        assertFalse(s.roundEnded())
    }

    @Test
    fun `mark or skip outside the current round window is ignored`() {
        val s = BoardSession(ids(5), 3)

        s.mark(9L) // 不在 idOrder 中
        assertEquals(1L, s.current!!)
        assertEquals(0, s.markedCount)
        s.skip(7L)
        assertEquals(1L, s.current!!)
        assertFalse(s.roundEnded())

        s.mark(1L)
        s.skip(2L)
        s.advance()
        assertTrue(s.roundEnded())
        s.startNewRound() // 窗口前移到 3，1、2 已出窗

        s.mark(1L) // 上轮已展示项：不可回看标记
        assertEquals(0, s.markedCount)
        assertTrue(s.markedIds.isEmpty())
        assertEquals(4L, s.current!!)
    }

    @Test
    fun `actions after round end consume nothing`() {
        val s = BoardSession(ids(5), 1)

        s.mark(1L)
        assertTrue(s.roundEnded())
        assertNull(s.current)
        s.advance() // 轮末无操作
        s.mark(2L) // 轮末标记亦无操作
        s.startNewRound()
        assertEquals(2L, s.current!!) // 2 仍在池中、未被吃掉
        assertEquals(0, s.markedCount)
    }

    @Test
    fun `pool exhausted ends display without ending the round`() {
        val s = BoardSession(ids(2), 5)

        s.advance()
        s.advance()
        assertNull(s.current)
        assertFalse(s.roundEnded())
        s.advance() // 池尽无操作
        assertNull(s.current)
    }

    @Test
    fun `peekUpcoming窥视未来项含当前且不改状态`() {
        val s = BoardSession(ids(4), 2)

        assertEquals(ids(3), s.peekUpcoming(3))
        s.mark(s.current!!) // 消耗 1 项
        assertEquals(listOf(2L, 3L, 4L), s.peekUpcoming(3))
        assertEquals(1, s.consumed) // 窥视不动指针
    }
}
