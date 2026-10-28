package dev.sxc.kadaishan.domain.selection

import dev.sxc.kadaishan.domain.classify.normalizeMediaPath
import dev.sxc.kadaishan.model.ImageCategory
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/** Metadata only: no URI decoding, EXIF, source-App inference or persistence. */
data class SelectionRow(
    val id: Long,
    val category: ImageCategory,
    val origin: String,
    val dateTakenMs: Long?,
    val dateAddedSec: Long,
)

internal fun selectionOrigin(path: String, bucket: String, category: ImageCategory): String =
    normalizeMediaPath(path).ifEmpty { normalizeMediaPath(bucket) }.ifEmpty { category.name }

internal enum class SelectionLane { SCREENSHOTS, RECENT, OTHER }

internal const val DAY_MS = 86_400_000L
internal const val SLICE_SIZE = 6
private const val GAP_MS = 30 * 60_000L
private const val SPAN_MS = 3 * 60 * 60_000L

internal fun SelectionRow.effectiveTime(nowMs: Long): Long? {
    dateTakenMs?.takeIf { it > 0 && it <= nowMs }?.let { return it }
    return if (dateAddedSec > 0 && dateAddedSec <= nowMs / 1000) dateAddedSec * 1000 else null
}

internal fun SelectionRow.lane(nowMs: Long): SelectionLane {
    val time = effectiveTime(nowMs) ?: return SelectionLane.OTHER
    val age = nowMs - time
    return when {
        category == ImageCategory.SCREENSHOT && age >= 3 * DAY_MS && age < 90 * DAY_MS -> SelectionLane.SCREENSHOTS
        age < 30 * DAY_MS && !(category == ImageCategory.SCREENSHOT && age < 3 * DAY_MS) -> SelectionLane.RECENT
        else -> SelectionLane.OTHER
    }
}

/** Pure deterministic scheduling. Cancellation is supplied by the coroutine-owning caller. */
internal fun smartOrder(
    rows: List<SelectionRow>,
    nowMs: Long,
    zone: ZoneId,
    random: Random,
    checkCancelled: () -> Unit = {},
): List<Long> {
    var operations = 0
    fun checkpoint() { if (operations++ % 256 == 0) checkCancelled() }
    checkCancelled()
    // A total order before dedup makes even conflicting duplicate rows independent of cursor order.
    val canonical = compareBy<SelectionRow> { it.id }.thenBy { it.category.ordinal }
        .thenBy { it.origin }.thenBy { it.dateTakenMs }.thenBy { it.dateAddedSec }
    val unique = rows.sortedWith { a, b -> checkpoint(); canonical.compare(a, b) }.distinctBy { checkpoint(); it.id }
    val lanes = Array(3) { mutableListOf<TimedRow>() }
    for (row in unique) {
        checkpoint()
        val time = row.effectiveTime(nowMs)
        lanes[row.lane(nowMs).ordinal].add(TimedRow(row, time, time?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }))
    }
    val queues = lanes.map { lane ->
        val events = mutableListOf<TimeEvent>()
        for (bucket in lane.groupBy { checkpoint(); it.row.category to it.row.origin }.values) {
            val sorted = bucket.sortedWith { a, b ->
                checkpoint()
                compareValues(a.time ?: Long.MAX_VALUE, b.time ?: Long.MAX_VALUE).takeIf { it != 0 }
                    ?: a.row.id.compareTo(b.row.id)
            }
            var current: TimeEvent? = null
            for (row in sorted) {
                checkpoint()
                val event = current
                val first = event?.items?.firstOrNull()
                val last = event?.items?.lastOrNull()
                if (row.time != null && first?.time != null && last?.time != null &&
                    row.day == first.day && row.time - last.time <= GAP_MS && row.time - first.time <= SPAN_MS
                ) {
                    event.items.add(row)
                    event.minId = minOf(event.minId, row.row.id)
                } else {
                    current = TimeEvent(mutableListOf(row), row.row.id)
                    events.add(current)
                }
            }
        }
        val comparator = compareBy<TimeEvent> { it.items.first().time ?: Long.MAX_VALUE }
            .thenBy { it.items.first().row.category.ordinal }.thenBy { it.items.first().row.origin }.thenBy { it.minId }
        events.sortWith { a, b -> checkpoint(); comparator.compare(a, b) }
        // In-place Fisher–Yates, with cancellation for large numbers of single-item events.
        for (i in events.lastIndex downTo 1) {
            checkpoint()
            val j = random.nextInt(i + 1)
            val tmp = events[i]; events[i] = events[j]; events[j] = tmp
        }
        ArrayDeque(events)
    }
    val result = ArrayList<Long>(unique.size)
    val slots = intArrayOf(0, 1, 2, 0)
    while (queues.any { it.isNotEmpty() }) {
        for (lane in slots) {
            checkpoint()
            val event = queues[lane].removeFirstOrNull() ?: continue
            repeat(minOf(SLICE_SIZE, event.items.size - event.offset)) {
                result.add(event.items[event.offset++].row.id)
            }
            if (event.offset < event.items.size) queues[lane].addLast(event)
        }
    }
    checkCancelled()
    return result
}

private data class TimedRow(val row: SelectionRow, val time: Long?, val day: Long?)
private class TimeEvent(val items: MutableList<TimedRow>, var minId: Long, var offset: Int = 0)
