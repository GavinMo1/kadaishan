package dev.sxc.kadaishan.domain.session

import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.MediaItem
import dev.sxc.kadaishan.model.MediaKey
import dev.sxc.kadaishan.model.MediaQuery
import dev.sxc.kadaishan.model.Page
import dev.sxc.kadaishan.model.SortClause
import dev.sxc.kadaishan.model.SortSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** SessionController 轮界决策与设置变更判定（纯 JVM；fake MediaSource 记录查询入参）。 */
class SessionControllerTest {

    @Test
    fun `recreated controller excludes kept items but allows undone and pending deletion items`() = runBlocking {
        val kept = mutableSetOf<MediaKey>()
        val history = object : ReviewHistory {
            override suspend fun keptIds(): Set<MediaKey> = kept.toSet()
            override suspend fun setKept(ids: Set<MediaKey>, value: Boolean) {
                if (value) kept.addAll(ids) else kept.removeAll(ids)
            }
        }
        val source = FakeSource(listOf(1L, 2L, 3L))
        fun fresh() = SessionController(source, Board.PHOTO, 0, history = history).apply {
            applySettings(SortSpec.SqlOrder(SortClause.DATE_TAKEN_DESC), true)
        }
        val session = fresh().rebuild(20)
        session.skip(1L)
        history.setKept(session.keptIds, true)
        session.mark(2L) // Pending mark is deliberately not persisted.
        assertEquals(2L, fresh().rebuild(20).current)
        session.undo() // Undo mark.
        val before = session.keptIds
        session.undo() // Undo keep.
        history.setKept(before - session.keptIds, false)
        assertEquals(1L, fresh().rebuild(20).current)
        history.setKept(setOf(1L, 2L, 3L), true)
        assertEquals(null, fresh().rebuild(20).current) // Exhaustion must not silently recycle kept items.
        fresh().resetKeptForMediaKind()
        assertEquals(1L, fresh().rebuild(20).current)
    }

    /** fake：ids 返回固定池，记录最后一次 query 以断言查询构造。 */
    private class FakeSource(private val pool: List<MediaKey>) : MediaSource {
        var lastQuery: MediaQuery? = null

        override suspend fun page(query: MediaQuery, after: MediaKey?, limit: Int): Page<MediaItem> =
            Page(emptyList(), null)

        override suspend fun queryByIds(ids: List<MediaKey>, matchTrashed: Int): List<MediaItem> =
            emptyList()

        override suspend fun ids(query: MediaQuery): List<MediaKey> {
            lastQuery = query
            return pool
        }
    }

    private fun controller(
        pool: List<MediaKey> = (1..9).map { it.toLong() },
        board: Board = Board.PHOTO,
        random: Random = Random(42),
    ): Pair<SessionController, FakeSource> {
        val source = FakeSource(pool)
        return SessionController(source, board, matchTrashed = 0, random = random) to source
    }

    @Test
    fun `rebuild 构造会话：全量 id 入池、roundSize 落位、非随机排序保序`() = runBlocking {
        val (c, source) = controller()
        c.applySettings(SortSpec.SqlOrder(SortClause.DATE_TAKEN_DESC), newMerged = true)
        val s = c.rebuild(20)

        val order = generateSequence(s.current) { s.advance(); s.current }.toList()
        assertEquals((1..9).map { it.toLong() }, order)
        assertEquals(20, s.roundSize)
        assertEquals(Board.PHOTO, source.lastQuery?.board)
        assertTrue(source.lastQuery?.mergeImages == true) // PHOTO + merged → 吸收全部图片
    }

    @Test
    fun `rebuild 随机排序：同一 Random 种子结果可重放，元素集合不变`() = runBlocking {
        val pool = (1..30).map { it.toLong() }
        val (c1, _) = controller(pool = pool, random = Random(7))
        val (c2, _) = controller(pool = pool, random = Random(7))
        val s1 = c1.rebuild(30) // 一轮覆盖全池（roundSize < 池大小时 current 到轮末即止）
        val s2 = c2.rebuild(30)

        val order1 = generateSequence(s1.current) { s1.advance(); s1.current }.toList()
        val order2 = generateSequence(s2.current) { s2.advance(); s2.current }.toList()
        assertEquals(order1, order2) // 同种子同序
        assertEquals(pool.toSet(), order1.toSet()) // 洗牌不漏不重
    }

    @Test
    fun `applySettings 变更判定：排序或板块模式变更返回 true，同值返回 false`() {
        val (c, _) = controller()
        assertTrue(c.applySettings(SortSpec.SqlOrder(SortClause.FILE_SIZE_DESC), newMerged = true))
        assertFalse(c.applySettings(SortSpec.SqlOrder(SortClause.FILE_SIZE_DESC), newMerged = true))
        assertTrue(c.applySettings(SortSpec.SqlOrder(SortClause.FILE_SIZE_DESC), newMerged = false))
    }

    @Test
    fun `decideNextRound：轮正常结束 → START_NEW_ROUND；池尽中断 → REBUILD；设置变更 → REBUILD`() = runBlocking {
        val (c, _) = controller(pool = (1..5).map { it.toLong() })
        val s = c.rebuild(2)
        s.advance()
        s.advance() // 本轮 2 条消耗完
        assertEquals(RoundAdvance.START_NEW_ROUND, c.decideNextRound(settingsChanged = false))
        assertEquals(RoundAdvance.REBUILD, c.decideNextRound(settingsChanged = true))

        s.startNewRound() // 新轮只消耗 0 条（未正常收尾 = 池尽中断语义）
        assertEquals(RoundAdvance.REBUILD, c.decideNextRound(settingsChanged = false))
    }

    @Test
    fun `decideNextRound：无会话 → REBUILD`() {
        val (c, _) = controller()
        assertEquals(RoundAdvance.REBUILD, c.decideNextRound(settingsChanged = false))
    }

    @Test
    fun `查询构造：VIDEO 板不带 mergeImages，分板模式 PHOTO 不合并`() = runBlocking {
        val (vc, vSource) = controller(board = Board.VIDEO)
        vc.rebuild(10)
        assertFalse(vSource.lastQuery?.mergeImages == true)

        val (pc, pSource) = controller()
        pc.applySettings(SortSpec.Random, newMerged = false) // 分板模式
        pc.rebuild(20)
        assertFalse(pSource.lastQuery?.mergeImages == true)
    }
}
