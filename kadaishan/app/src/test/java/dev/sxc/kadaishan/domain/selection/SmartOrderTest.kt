package dev.sxc.kadaishan.domain.selection

import dev.sxc.kadaishan.model.ImageCategory
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SmartOrderTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")
    private fun row(id: Long, days: Long = 5, category: ImageCategory = ImageCategory.CAMERA,
                    origin: String = "camera", time: Long? = now - days * DAY_MS, added: Long = 0) =
        SelectionRow(id, category, origin, time, added)
    private fun order(rows: List<SelectionRow>, seed: Int = 7, zone: ZoneId = utc) =
        smartOrder(rows, now, zone, Random(seed))

    @Test fun `three days thirty days and ninety days have exact exclusive boundaries`() {
        assertEquals(SelectionLane.OTHER, row(1, 2, ImageCategory.SCREENSHOT).lane(now))
        assertEquals(SelectionLane.SCREENSHOTS, row(1, 3, ImageCategory.SCREENSHOT).lane(now))
        assertEquals(SelectionLane.SCREENSHOTS, row(1, 89, ImageCategory.SCREENSHOT).lane(now))
        assertEquals(SelectionLane.OTHER, row(1, 90, ImageCategory.SCREENSHOT).lane(now))
        assertEquals(SelectionLane.RECENT, row(1, 29).lane(now))
        assertEquals(SelectionLane.OTHER, row(1, 30).lane(now))
        assertEquals(SelectionLane.RECENT, row(1, 0).lane(now))
        assertEquals(SelectionLane.OTHER, row(1, time = null).lane(now))
    }

    @Test fun `timestamps reject zero future and overflowing seconds with safe fallback`() {
        assertEquals(now - DAY_MS, row(1, time = now - DAY_MS, added = 1).effectiveTime(now))
        for (bad in listOf(null, 0, -1, now + 1, Long.MAX_VALUE)) {
            assertEquals(now - DAY_MS, row(1, time = bad, added = (now - DAY_MS) / 1000).effectiveTime(now))
        }
        for (bad in listOf(0L, -1L, Long.MAX_VALUE, now / 1000 + 1)) {
            assertNull(row(1, time = null, added = bad).effectiveTime(now))
        }
    }

    @Test fun `path normalization and category fallback share the established rules`() {
        assertEquals("dcim/camera", selectionOrigin("  DCIM\\Camera/ ", "wrong", ImageCategory.CAMERA))
        assertEquals("screenshots", selectionOrigin("/", " Screenshots/ ", ImageCategory.SCREENSHOT))
        assertEquals("SAVED", selectionOrigin("", "", ImageCategory.SAVED))
    }

    @Test fun `first default round includes all three lanes and old images`() {
        val a = (1L..25).map { row(it, category = ImageCategory.SCREENSHOT) }
        val b = (101L..125).map { row(it) }
        val c = (201L..225).map { row(it, days = 100) }
        val result = order(a + b + c)
        assertEquals((1L..6).toList() + (101L..106).toList() + (201L..206).toList() + (7L..12).toList(), result.take(24))
        assertTrue(result.take(20).any { it >= 201 })
        assertEquals((1L..25).toList(), result.filter { it < 100 })
        assertEquals(75, result.toSet().size)
    }

    @Test fun `small and missing lanes finish without loss or duplicates`() {
        for (a in 0..8) for (b in 0..8) for (c in 0..8) {
            val rows = (1L..a.toLong()).map { row(it, category = ImageCategory.SCREENSHOT) } +
                (101L until 101L + b).map { row(it) } + (201L until 201L + c).map { row(it, days = 100) }
            val result = order(rows)
            assertEquals(rows.map { it.id }.toSet(), result.toSet())
            assertEquals(rows.size, result.size)
            if (a > 0 && b > 0 && c > 0) {
                assertTrue(result.take(20).any { it < 100 })
                assertTrue(result.take(20).any { it in 101..199 })
                assertTrue(result.take(20).any { it >= 201 })
            }
        }
        assertEquals((1L..25).toList(), order((1L..25).map { row(it) }))
    }

    @Test fun `same input set and seed reproduce independently of enumeration or duplicate rows`() {
        val rows = (1L..100).map { row(it, days = it, origin = "source-${it % 4}") }
        assertEquals(order(rows), order(rows.shuffled(Random(3)) + rows.take(15)))
        val conflicting = rows + row(1, days = 50, origin = "different")
        assertEquals(order(conflicting), order(conflicting.reversed()))
        assertNotEquals(order(rows, 1), order(rows, 2))
    }

    @Test fun `time order uses id as tie breaker and unknown time does not form an event`() {
        val rows = listOf(row(3), row(1), row(2))
        assertEquals(listOf(1L, 2L, 3L), order(rows))
        val unknown = rows.map { it.copy(dateTakenMs = null) }
        assertTrue((0..20).map { order(unknown, it) }.toSet().size > 1)
    }

    private fun twoEventOrders(first: SelectionRow, second: SelectionRow, zone: ZoneId = utc) =
        (0..20).map { order(listOf(first, second), it, zone) }.toSet()

    @Test fun `gap equality joins but beyond gap splits and different origins never join`() {
        val first = row(1)
        assertEquals(setOf(listOf(1L, 2L)), twoEventOrders(first, row(2, time = first.dateTakenMs!! + 30 * 60_000)))
        assertEquals(2, twoEventOrders(first, row(2, time = first.dateTakenMs!! + 30 * 60_000 + 1)).size)
        assertEquals(2, twoEventOrders(first, row(2, origin = "downloads")).size)
        assertEquals(2, twoEventOrders(first, row(2, category = ImageCategory.SAVED)).size)
    }

    @Test fun `whole day chain is split after three hour span even with small adjacent gaps`() {
        val base = Instant.parse("2026-10-20T01:00:00Z").toEpochMilli()
        val seven = (1L..7).map { row(it, time = base + (it - 1) * 30 * 60_000) }
        for (seed in 0..20) assertEquals((1L..7).toList(), order(seven, seed))
        val eight = seven + row(8, time = base + 3 * 60 * 60_000 + 30 * 60_000)
        assertTrue((0..20).map { order(eight, it) }.any { it.first() == 8L })
    }

    @Test fun `natural day uses local zone while elapsed time handles daylight saving`() {
        val t = Instant.parse("2026-10-20T23:55:00Z").toEpochMilli()
        assertEquals(2, twoEventOrders(row(1, time = t), row(2, time = t + 10 * 60_000)).size)
        assertEquals(1, twoEventOrders(row(1, time = t), row(2, time = t + 10 * 60_000), ZoneId.of("Asia/Shanghai")).size)
        val dstNow = Instant.parse("2025-11-04T12:00:00Z").toEpochMilli()
        val early = row(1, time = Instant.parse("2025-11-02T05:15:00Z").toEpochMilli())
        val late = row(2, time = Instant.parse("2025-11-02T06:15:00Z").toEpochMilli())
        assertEquals(2, (0..20).map { smartOrder(listOf(early, late), dstNow, ZoneId.of("America/New_York"), Random(it)) }.toSet().size)
    }

    @Test(expected = CancellationException::class) fun `large sort cooperates with cancellation during comparisons`() {
        var checks = 0
        smartOrder((1L..50_000L).reversed().map { row(it) }, now, utc, Random(7)) {
            if (++checks == 3) throw CancellationException("cancel during work")
        }
    }

    @Test fun `large input retains every candidate without quadratic queue draining`() {
        val rows = (1L..50_000L).map { row(it, time = null) }
        val started = System.nanoTime()
        val result = order(rows)
        assertEquals(50_000, result.size)
        assertEquals(rows.map { it.id }.toSet(), result.toSet())
        println("SmartOrder 50000 unknown-time metadata rows: ${(System.nanoTime() - started) / 1_000_000} ms (host JVM only)")
    }
}
