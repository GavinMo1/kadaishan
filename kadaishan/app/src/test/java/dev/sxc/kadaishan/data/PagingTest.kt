package dev.sxc.kadaishan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** collectWindow 分页窗口语义（架构 §7 limit+offset；真实调用方 = MediaStoreDataSource.page 的 cursor 行序列）。 */
class PagingTest {

    @Test
    fun `skip 0 takes first limit rows`() {
        val (window, scanned) = collectWindow((1..10).asSequence(), { true }, skip = 0, limit = 3)
        assertEquals(listOf(1, 2, 3), window)
        assertEquals(3, scanned)
    }

    @Test
    fun `skip in the middle drops exactly skip rows before scanning`() {
        val (window, scanned) = collectWindow((1..10).asSequence(), { true }, skip = 4, limit = 3)
        assertEquals(listOf(5, 6, 7), window)
        assertEquals(3, scanned)
    }

    @Test
    fun `skip beyond total yields empty window and zero scanned`() {
        val (window, scanned) = collectWindow((1..10).asSequence(), { true }, skip = 50, limit = 3)
        assertTrue(window.isEmpty())
        assertEquals(0, scanned)
    }

    @Test
    fun `skip exactly at end yields empty window`() {
        val (window, scanned) = collectWindow((1..10).asSequence(), { true }, skip = 10, limit = 3)
        assertTrue(window.isEmpty())
        assertEquals(0, scanned)
    }

    @Test
    fun `skip landing on last row yields that row only`() {
        val (window, scanned) = collectWindow((1..10).asSequence(), { true }, skip = 9, limit = 3)
        assertEquals(listOf(10), window)
        assertEquals(1, scanned)
    }

    @Test
    fun `limit 1 boundary takes a single row`() {
        val (window, scanned) = collectWindow((1..10).asSequence(), { true }, skip = 0, limit = 1)
        assertEquals(listOf(1), window)
        assertEquals(1, scanned)
    }

    @Test
    fun `limit 0 boundary collects nothing`() {
        val (window, scanned) = collectWindow((1..10).asSequence(), { true }, skip = 4, limit = 0)
        assertTrue(window.isEmpty())
        assertEquals(0, scanned)
    }

    @Test
    fun `limit larger than remaining stops at exhaustion`() {
        val (window, scanned) = collectWindow((1..10).asSequence(), { true }, skip = 8, limit = 5)
        assertEquals(listOf(9, 10), window)
        assertEquals(2, scanned)
    }

    @Test
    fun `all rows rejected yields empty window with full scan`() {
        val (window, scanned) = collectWindow((1..10).asSequence(), { false }, skip = 3, limit = 4)
        assertTrue(window.isEmpty())
        assertEquals(7, scanned)
    }

    @Test
    fun `exactly limit hits stops right at the filling row without over-scan`() {
        val (window, scanned) = collectWindow((0..9).asSequence(), { it % 2 == 0 }, skip = 0, limit = 5)
        assertEquals(listOf(0, 2, 4, 6, 8), window)
        assertEquals(9, scanned)
    }

    @Test
    fun `mixed hits count rejected rows in scanned per spec example`() {
        // skip=5、此后 10 行仅 1 个命中、limit=2 → 窗口 1 个、scanned=10
        val (window, scanned) = collectWindow((0..14).asSequence(), { it == 14 }, skip = 5, limit = 2)
        assertEquals(listOf(14), window)
        assertEquals(10, scanned)
    }

    @Test
    fun `scanned includes rejected rows walked before limit fills`() {
        // 0 命中、1/2 未命中、3 命中 → 窗口满 2，scanned=4（含两行未命中）
        val (window, scanned) = collectWindow((0..99).asSequence(), { it % 3 == 0 }, skip = 0, limit = 2)
        assertEquals(listOf(0, 3), window)
        assertEquals(4, scanned)
    }
}
