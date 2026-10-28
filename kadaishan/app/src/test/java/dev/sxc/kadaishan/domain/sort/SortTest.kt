package dev.sxc.kadaishan.domain.sort

import dev.sxc.kadaishan.model.SortClause
import dev.sxc.kadaishan.model.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** ORDER BY 白名单映射（PRD §4.4 / 架构 §6 #3）+ Fisher-Yates 纯函数（架构 §8 可测性口径）。 */
class SortTest {

    @Test
    fun `FILE_SIZE_DESC maps to size DESC`() {
        assertEquals("size DESC", SortSpec.SqlOrder(SortClause.FILE_SIZE_DESC).toOrderBy())
    }

    @Test
    fun `DATE_TAKEN_DESC maps to exact CASE expression with DESC`() {
        assertEquals(
            "CASE WHEN date_taken IS NULL THEN date_added*1000 ELSE date_taken END DESC",
            SortSpec.SqlOrder(SortClause.DATE_TAKEN_DESC).toOrderBy(),
        )
    }

    @Test
    fun `DATE_TAKEN_ASC maps to exact CASE expression with ASC`() {
        assertEquals(
            "CASE WHEN date_taken IS NULL THEN date_added*1000 ELSE date_taken END ASC",
            SortSpec.SqlOrder(SortClause.DATE_TAKEN_ASC).toOrderBy(),
        )
    }

    @Test
    fun `Random maps to stable _ID ASC`() {
        assertEquals("_ID ASC", SortSpec.Random.toOrderBy())
        assertEquals("_ID ASC", SortSpec.Smart.toOrderBy())
    }

    @Test
    fun `fisherYates same seed replays identical result`() {
        val items = (1..100).toList()
        assertEquals(fisherYates(items, Random(42)), fisherYates(items, Random(42)))
    }

    @Test
    fun `fisherYates result is a permutation of input`() {
        val items = listOf(3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9)
        val shuffled = fisherYates(items, Random(7))
        assertEquals(items.size, shuffled.size)
        assertEquals(items.sorted(), shuffled.sorted())
    }

    @Test
    fun `fisherYates does not mutate the input list`() {
        val items = (1..30).toList()
        val snapshot = items.toList()
        fisherYates(items, Random(1))
        assertEquals(snapshot, items)
    }

    @Test
    fun `fisherYates actually shuffles for a fixed seed`() {
        val items = (0..99).toList()
        val shuffled = fisherYates(items, Random(42))
        assertNotEquals(items, shuffled)
        assertTrue(shuffled.toSet() == items.toSet())
    }
}
