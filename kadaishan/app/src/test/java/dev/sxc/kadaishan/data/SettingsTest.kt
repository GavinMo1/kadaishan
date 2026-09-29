package dev.sxc.kadaishan.data

import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.BoardAction
import dev.sxc.kadaishan.model.Gesture
import dev.sxc.kadaishan.model.GestureMapping
import dev.sxc.kadaishan.model.SortClause
import dev.sxc.kadaishan.model.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 关键单测：手势映射双向单射校验（架构 §8）+ 设置边界纯函数 + 持久化编解码兜底。 */
class SettingsTest {

    // —— GestureMapping 构造校验 ——

    @Test
    fun `任意合法置换构造通过`() {
        val m = GestureMapping(
            mapOf(
                Gesture.LEFT to BoardAction.SKIP,
                Gesture.RIGHT to BoardAction.MARK_DELETE,
                Gesture.UP to BoardAction.SHOW_INFO,
            ),
        )
        assertEquals(BoardAction.SKIP, m.map[Gesture.LEFT])
        assertEquals(BoardAction.MARK_DELETE, m.map[Gesture.RIGHT])
        assertEquals(BoardAction.SHOW_INFO, m.map[Gesture.UP])
    }

    @Test
    fun `默认映射构造通过`() {
        val m = GestureMapping.default()
        assertEquals(BoardAction.MARK_DELETE, m.map[Gesture.LEFT])
        assertEquals(BoardAction.SKIP, m.map[Gesture.RIGHT])
        assertEquals(BoardAction.SHOW_INFO, m.map[Gesture.UP])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `两个手势同一动作抛 IAE`() {
        GestureMapping(
            mapOf(
                Gesture.LEFT to BoardAction.SKIP,
                Gesture.RIGHT to BoardAction.SKIP,
                Gesture.UP to BoardAction.SHOW_INFO,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `缺手势抛 IAE`() {
        GestureMapping(
            mapOf(
                Gesture.LEFT to BoardAction.MARK_DELETE,
                Gesture.RIGHT to BoardAction.SKIP,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `map 含 Gesture DOWN 抛 IAE`() {
        GestureMapping(
            mapOf(
                Gesture.LEFT to BoardAction.MARK_DELETE,
                Gesture.RIGHT to BoardAction.SKIP,
                Gesture.UP to BoardAction.SHOW_INFO,
                Gesture.DOWN to BoardAction.SKIP,
            ),
        )
    }

    // —— 边界校验纯函数 ——

    @Test
    fun `requireRoundSize 边界内通过`() {
        assertEquals(1, SettingsRepository.requireRoundSize(1))
        assertEquals(300, SettingsRepository.requireRoundSize(300))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `requireRoundSize 0 抛 IAE`() {
        SettingsRepository.requireRoundSize(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `requireRoundSize 301 抛 IAE`() {
        SettingsRepository.requireRoundSize(301)
    }

    // —— 每轮数量缺省（PRD §4.4：图片三板块 20 / 视频 10）——

    @Test
    fun `每轮数量缺省为图 20 视 10`() {
        val defaults = SettingsRepository.defaultRoundSizes()
        assertEquals(20, defaults[Board.PHOTO])
        assertEquals(20, defaults[Board.SCREENSHOT])
        assertEquals(20, defaults[Board.SAVED])
        assertEquals(10, defaults[Board.VIDEO])
    }

    // —— 持久化编解码 ——

    @Test
    fun `手势映射编解码往返`() {
        val original = GestureMapping.default()
        val decoded = SettingsRepository.parseGestureMapping(SettingsRepository.encodeGestureMapping(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `手势映射坏数据回默认`() {
        val default = GestureMapping.default()
        assertEquals(default, SettingsRepository.parseGestureMapping(null))
        assertEquals(default, SettingsRepository.parseGestureMapping(emptySet()))
        // 非法枚举名
        assertEquals(default, SettingsRepository.parseGestureMapping(setOf("LEFT=NOPE")))
        // 冲突（两手势同动作）
        assertEquals(
            default,
            SettingsRepository.parseGestureMapping(setOf("LEFT=SKIP", "RIGHT=SKIP", "UP=SHOW_INFO")),
        )
        // 缺手势（仅左/右，无上）
        assertEquals(default, SettingsRepository.parseGestureMapping(setOf("LEFT=MARK_DELETE", "RIGHT=SKIP")))
        // 含 DOWN
        assertEquals(
            default,
            SettingsRepository.parseGestureMapping(
                setOf("LEFT=MARK_DELETE", "RIGHT=SKIP", "UP=SHOW_INFO", "DOWN=SKIP"),
            ),
        )
        // 无 "=" 分隔符
        assertEquals(default, SettingsRepository.parseGestureMapping(setOf("LEFT_MARK_DELETE")))
    }

    @Test
    fun `排序编解码往返`() {
        assertEquals("RANDOM", SettingsRepository.encodeSortStrategy(SortSpec.Random))
        assertEquals(
            "FILE_SIZE_DESC",
            SettingsRepository.encodeSortStrategy(SortSpec.SqlOrder(SortClause.FILE_SIZE_DESC)),
        )
        assertEquals(
            SortSpec.SqlOrder(SortClause.DATE_TAKEN_DESC),
            SettingsRepository.parseSortStrategy(
                SettingsRepository.encodeSortStrategy(SortSpec.SqlOrder(SortClause.DATE_TAKEN_DESC)),
            ),
        )
    }

    @Test
    fun `排序缺省与坏数据回 Random`() {
        assertEquals(SortSpec.Random, SettingsRepository.parseSortStrategy(null))
        assertEquals(SortSpec.Random, SettingsRepository.parseSortStrategy("RANDOM"))
        assertEquals(SortSpec.Random, SettingsRepository.parseSortStrategy("NOT_A_STRATEGY"))
        assertEquals(SortSpec.Random, SettingsRepository.parseSortStrategy(""))
    }

    @Test
    fun `每轮数量缺省映射覆盖四板块`() {
        val defaults = SettingsRepository.defaultRoundSizes()
        assertEquals(Board.entries.toSet(), defaults.keys)
        assertTrue(defaults.values.all { it in 1..300 })
    }
}
