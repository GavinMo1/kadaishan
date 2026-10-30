package dev.sxc.kadaishan.data

import dev.sxc.kadaishan.domain.cultivation.*
import org.junit.Assert.*
import org.junit.Test

class CultivationCodecTest {
    @Test fun fullStateRoundTripsIncludingLongOfflineReportAndReincarnation() {
        val s = CultivationState(lastMillis = 123456789, seed = Long.MIN_VALUE, level = 12,
            map = 4, unlockedMap = 5, bosses = setOf(1,2,3,4), cultivationTenths = 98347,
            stones = 9876543210, practice = Practice.LIFE, paused = true,
            enhancements = mapOf(GearSlot.WEAPON to 8), heirloomId = -1,
            reincarnations = 3, world = 4, highestLevel = 18, carryMillis = 59000,
            collection = setOf("1:WEAPON:BLUE"), giftMillis = 90000, consumedRound = 22,
            sequence = 43200, dryWins = 19, dullDrops = 49,
            report = Earnings(43200,43200,129600,96000,400,388,3, battles = 43200))
        assertEquals(s, CultivationCodec.decode(CultivationCodec.encode(s)))
    }
    @Test fun nullableFieldsAndDefaultsRoundTrip() {
        val s = CultivationState(lastMillis = 0)
        assertEquals(s, CultivationCodec.decode(CultivationCodec.encode(s)))
    }
    @Test fun malformedAndFutureStateFailWithoutSilentlyResetting() {
        listOf("{}", "not json", CultivationCodec.encode(CultivationState(lastMillis=0)).replace("\"version\":2", "\"version\":99"),
            CultivationCodec.encode(CultivationState(lastMillis=0)).replace("\"level\":1", "\"level\":900")).forEach { raw ->
            try { CultivationCodec.decode(raw); fail("corrupt save accepted") } catch (_: Exception) { }
        }
    }
}
