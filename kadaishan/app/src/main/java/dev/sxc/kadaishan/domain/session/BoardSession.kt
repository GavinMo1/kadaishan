package dev.sxc.kadaishan.domain.session

import dev.sxc.kadaishan.model.MediaKey

// ═══ 板块会话（PRD §4.1/§4.4 池子与轮次语义；架构 §2 BoardSession）═══
//
// 调用契约（UI 切片逐字对齐）：
//  · 一次「让会话首次看到新条目」= 恰好一次 advance() 或对当前项的 mark(id)/skip(id) —— 三者都消耗当前项；
//    对当前项的 mark/skip 内部已含推进，不得再补 advance()（会双重消耗）。
//  · 回看 mark（本窗口 [轮起点, 指针) 已展示的旧卡）= 纯状态：指针与轮计数不动，帧 at = 该卡下标；
//    skip 只对当前项。mark/unmark 对已标记 id 均为幂等无操作（按钮态自检读 markedIds）。
//  · mark/skip 对非当前且非本窗口的 id（含上轮项）、轮末、池尽 = 无操作（不越界、不抛异常）。
//  · unmark(id) = 纯状态取消标记（回看口，与回看 mark 对称）：不动指针、轮计数不变。
//  · 指针只前进、undo 只降不升（不越轮起点）：已展示项不重入本轮、跳过项会话级出池由单调指针结构性保证；
//    startNewRound() 不回拨指针；换新排序 = 构造新 BoardSession。本类不持久化（退出即弃）。
//  · 纯 JVM：不依赖 android.*；MediaKey = Long。

/** 会话动作（撤回栈元素；消费方是 BoardSession 内部栈 —— UI 不构造、不接触本类型）。 */
sealed interface SessionAction {
    data class Mark(val id: MediaKey) : SessionAction
    data class Skip(val id: MediaKey) : SessionAction
}

/** 板块会话：排序快照上的轮次推进 + 栈式撤回。main 单写者（架构 §2 并发约定）。 */
class BoardSession(idOrder: List<MediaKey>, val roundSize: Int) {

    init {
        require(roundSize in 1..300) { "roundSize 必须在 1..300，实际 $roundSize" }
    }

    /** 会话级排序快照（调用方已按 PRD §4.4 排好：shuffle / 排序列序）。 */
    private val order: List<MediaKey> = idOrder

    /** 当前展示项下标；单调前进，唯一回拨点是 undo()。 */
    private var pointer = 0

    /** 本轮起点下标（startNewRound 前移到 pointer，不回拨）；本轮已消耗 = pointer - roundStart。 */
    private var roundStart = 0

    /** 撤回栈元素：动作 + 动作发生时的指针 —— 撤销 = 拨回该处（其后的普通 advance 一并回退）。 */
    private class Frame(val action: SessionAction, val at: Int)

    /** 撤回栈（轮内；startNewRound 清空 —— 撤回不跨轮，跨轮撤回会把上轮项重入本轮）。 */
    private val undoStack = ArrayDeque<Frame>()

    /** 本轮标记（仅内存；未结算 = 未删除；startNewRound 丢弃）。 */
    private val marks = LinkedHashSet<MediaKey>()

    /** 当前应展示项；null = 轮末（roundEnded()）或池已穷尽（含空会话）。
     *  轮末主动置 null：不泄漏本轮之外的下一条、不留可滑僵尸卡；调用方据 roundEnded() 分辨两种 null。 */
    val current: MediaKey?
        get() = if (roundEnded()) null else order.getOrNull(pointer)

    /** 本轮仍处标记态的条目数（undo 递减，startNewRound 归 0）。 */
    val markedCount: Int
        get() = marks.size

    /** 本轮标记项（标记顺序，活视图）：结算逐项取数口（架构 §2 markedIds）。 */
    val markedIds: Set<MediaKey>
        get() = marks

    /** 无标记推进（浏览即过）。轮末 / 池尽 = 无操作。 */
    fun advance() {
        if (exhausted()) return
        pointer++
    }

    /** 标记删除（仅内存；PRD §4.2 删除按钮 = 标记/取消双向）：
     *  · 当前项：入标记集与撤回栈，并消耗当前项（内部含推进，调用方勿再补 advance）；
     *  · 回看项（本窗口 [roundStart, pointer) 已展示、未标记）：纯状态标记 —— 指针与轮计数
     *    完全不动，帧 at = 该卡下标；
     *  · 已标记 / 非本窗口（含上轮项）/ 轮末 = 无操作。取消用 unmark(id) 或 undo()。 */
    fun mark(id: MediaKey) {
        if (isCurrent(id)) {
            marks.add(id)
            undoStack.addLast(Frame(SessionAction.Mark(id), pointer))
            pointer++
            return
        }
        if (roundEnded() || marks.contains(id)) return
        val at = displayedIndex(id)
        if (at < 0) return
        marks.add(id)
        undoStack.addLast(Frame(SessionAction.Mark(id), at))
    }

    /** 取消标记（PRD §4.2「再点 = 取消标记」）：移出标记集并从撤回栈摘除该 Mark 帧，
     *  **指针不动**（该卡的消耗保留为普通浏览，轮计数与其余栈帧快照不受影响；后续 undo() 撤的是
     *  剩余栈顶）。纯状态操作，轮末/池尽/任意展示位均可调；与回看 mark 对称 —— 取消后可随时重新 mark。
     *  true = 已取消；false = 该 id 未标记（无操作，幂等）。 */
    fun unmark(id: MediaKey): Boolean {
        if (!marks.remove(id)) return false
        val frame = undoStack.firstOrNull { it.action is SessionAction.Mark && it.action.id == id }
        if (frame != null) undoStack.remove(frame)
        return true
    }

    /** 跳过（保留）：消耗当前项 = 会话级出池（本会话后续轮次不再出现）；
     *  undo() 撤回跳过 = 拨回该处 = 重新入池、可再滑。非当前项 / 轮末 / 池尽 = 无操作。 */
    fun skip(id: MediaKey) {
        if (!isCurrent(id)) return
        undoStack.addLast(Frame(SessionAction.Skip(id), pointer))
        pointer++
    }

    /** 栈式连续撤回：逆转栈顶动作（标记出集；跳过重新入池）并把展示拨回被撤项（帧 at）。
     *  指针只降不升：回看帧与更旧帧连续撤回后 at 可能 ≥ 当前指针，此时仅撤标记、不前跳。
     *  false = 栈空（撤回按钮置灰判据，与轮末相互独立）。 */
    fun undo(): Boolean {
        val frame = undoStack.removeLastOrNull() ?: return false
        if (frame.action is SessionAction.Mark) marks.remove(frame.action.id)
        if (frame.at < pointer) pointer = frame.at
        return true
    }

    /** 本轮条数已走完：恰好 roundSize 条消耗（护栏保证不会超过，故用 ==）。 */
    fun roundEnded(): Boolean = pointer - roundStart == roundSize

    /** 开新轮：轮内计数与标记重置（上轮未结算标记丢弃 = 未删除）、撤回栈清空；
     *  指针与 idOrder 快照不动 —— 已展示项与跳过项结构性不重入。 */
    fun startNewRound() {
        roundStart = pointer
        marks.clear()
        undoStack.clear()
    }

    /** 轮末或池尽：禁止再消耗。 */
    private fun exhausted(): Boolean = roundEnded() || pointer >= order.size

    /** id 在本窗口已展示区间 [roundStart, pointer) 内的下标；-1 = 不在（原始 Long 比较，无装箱）。 */
    private fun displayedIndex(id: MediaKey): Int {
        var i = roundStart
        while (i < pointer) {
            if (order[i] == id) return i
            i++
        }
        return -1
    }

    /** id 是否正是当前展示项（同时覆盖轮末 / 池尽的否定）。原始 Long 比较，无装箱。 */
    private fun isCurrent(id: MediaKey): Boolean = !exhausted() && order[pointer] == id
}
