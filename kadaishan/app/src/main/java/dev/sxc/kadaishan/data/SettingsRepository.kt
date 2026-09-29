package dev.sxc.kadaishan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.BoardAction
import dev.sxc.kadaishan.model.Gesture
import dev.sxc.kadaishan.model.GestureMapping
import dev.sxc.kadaishan.model.SortClause
import dev.sxc.kadaishan.model.SortSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 唯一 settings DataStore 实例（文件名 "settings"；Preferences DataStore，架构 §2 配置清单）。 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 设置持久化（架构 §2 配置清单 4 组 key）：手势映射 / 分板块每轮数量 / 排序 / 永久删除解锁标志 + 图片板块模式。
 *  标记与分组不落盘；边界校验见伴生对象纯函数。 */
class SettingsRepository(context: Context) {

    private val dataStore: DataStore<Preferences> = context.settingsDataStore

    /** 手势映射；坏数据（键缺失/动作或手势非法/非双射/含 DOWN）一律回默认映射。 */
    val gestureMapping: Flow<GestureMapping> = dataStore.data.map { prefs ->
        parseGestureMapping(prefs[KEY_GESTURE])
    }

    /** 分板块每轮数量；缺省 PHOTO/SCREENSHOT/SAVED=20、VIDEO=10（PRD §4.4）。 */
    val perBoardRoundSize: Flow<Map<Board, Int>> = dataStore.data.map { prefs ->
        Board.entries.associateWith { board -> prefs[roundSizeKey(board)] ?: defaultRoundSize(board) }
    }

    /** 排序策略；缺省/坏数据回 SortSpec.Random。 */
    val sortStrategy: Flow<SortSpec> = dataStore.data.map { prefs ->
        parseSortStrategy(prefs[KEY_SORT])
    }

    /** 「立即永久删除」解锁标志（PRD §4.6：2026-09-27 用户拍板默认已解锁，找回演练降为建议路径）。缺省 true。 */
    val permanentDeleteUnlocked: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PERM_UNLOCKED] ?: true
    }

    /** 图片板块模式（PRD §3.1）：true（缺省）= 全部图片统一归「照片」板；false = 拆 照片/截图/已保存 三板。 */
    val imageBoardsMerged: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_IMAGE_BOARDS_MERGED] ?: true
    }

    suspend fun setGestureMapping(m: GestureMapping) {
        dataStore.edit { it[KEY_GESTURE] = encodeGestureMapping(m) }
    }

    suspend fun setRoundSize(board: Board, size: Int) {
        val checked = requireRoundSize(size)
        dataStore.edit { it[roundSizeKey(board)] = checked }
    }

    suspend fun setSortStrategy(s: SortSpec) {
        dataStore.edit { it[KEY_SORT] = encodeSortStrategy(s) }
    }

    suspend fun setImageBoardsMerged(merged: Boolean) {
        dataStore.edit { it[KEY_IMAGE_BOARDS_MERGED] = merged }
    }

    suspend fun setPermanentDeleteUnlocked(v: Boolean) {
        dataStore.edit { it[KEY_PERM_UNLOCKED] = v }
    }

    companion object {
        private val KEY_GESTURE = stringSetPreferencesKey("gesture_mapping")
        private val KEY_SORT = stringPreferencesKey("sort_strategy")
        private val KEY_PERM_UNLOCKED = booleanPreferencesKey("permanent_delete_unlocked")
        private val KEY_IMAGE_BOARDS_MERGED = booleanPreferencesKey("image_boards_merged")
        private fun roundSizeKey(board: Board): Preferences.Key<Int> =
            intPreferencesKey("round_size_${board.name}")

        /** 每轮数量缺省（PRD §4.4）：图片三板块 20 / 视频 10。 */
        fun defaultRoundSize(board: Board): Int = if (board == Board.VIDEO) 10 else 20

        /** 四板块缺省每轮数量快照（UI collectAsState 初值用）。 */
        fun defaultRoundSizes(): Map<Board, Int> =
            Board.entries.associateWith { defaultRoundSize(it) }

        /** 每轮数量边界（1..300）；越界抛 IllegalArgumentException。 */
        fun requireRoundSize(size: Int): Int {
            require(size in 1..300) { "每轮数量须在 1..300：$size" }
            return size
        }

        /** 持久化编码："LEFT=MARK_DELETE" 形式 stringSet。 */
        fun encodeGestureMapping(m: GestureMapping): Set<String> =
            m.map.map { (gesture, action) -> "${gesture.name}=${action.name}" }.toSet()

        /** 读取解析：坏数据（null / 非法枚举 / 非双射 / 缺手势 / 含 DOWN）→ GestureMapping.default()。 */
        fun parseGestureMapping(raw: Set<String>?): GestureMapping {
            if (raw.isNullOrEmpty()) return GestureMapping.default()
            return try {
                val map = raw.associate { entry ->
                    val parts = entry.split("=", limit = 2)
                    require(parts.size == 2) { "坏数据：$entry" }
                    Gesture.valueOf(parts[0]) to BoardAction.valueOf(parts[1])
                }
                GestureMapping(map) // init 校验不通过（冲突/缺手势/含 DOWN）抛 IAE → 兜底
            } catch (e: IllegalArgumentException) {
                GestureMapping.default()
            }
        }

        /** 排序持久化编码："RANDOM" / SortClause 名。 */
        fun encodeSortStrategy(s: SortSpec): String = when (s) {
            SortSpec.Random -> "RANDOM"
            is SortSpec.SqlOrder -> s.clause.name
        }

        /** 排序读取解析；缺省/坏数据 → SortSpec.Random。 */
        fun parseSortStrategy(raw: String?): SortSpec {
            if (raw == null || raw == "RANDOM") return SortSpec.Random
            return try {
                SortSpec.SqlOrder(SortClause.valueOf(raw))
            } catch (e: IllegalArgumentException) {
                SortSpec.Random
            }
        }
    }
}
