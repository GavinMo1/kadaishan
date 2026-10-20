package dev.sxc.kadaishan.domain.settle

import dev.sxc.kadaishan.model.DeleteMode
import dev.sxc.kadaishan.model.SettlementItemResult
import dev.sxc.kadaishan.model.SettlementItemStatus
import dev.sxc.kadaishan.model.SnapItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettlementCompletionTest {
    private fun result(id: Long, status: SettlementItemStatus) =
        SettlementItemResult(SnapItem(id, "content://media/$id", "$id.jpg", 100L), status)

    @Test fun `complete verified results may continue for each deletion mode`() {
        assertTrue(canContinueAfterSettlement(listOf(result(1, SettlementItemStatus.TRASHED)), 1, DeleteMode.TRASH, null))
        assertTrue(canContinueAfterSettlement(listOf(result(1, SettlementItemStatus.DELETED)), 1, DeleteMode.PERMANENT, null))
    }

    @Test fun `denial unknown partial recheck and wrong mode must remain visible`() {
        for (status in SettlementItemStatus.entries) {
            if (status != SettlementItemStatus.TRASHED) {
                assertFalse(canContinueAfterSettlement(listOf(result(1, status)), 1, DeleteMode.TRASH, null))
            }
        }
        assertFalse(canContinueAfterSettlement(emptyList(), 0, DeleteMode.TRASH, null))
        assertFalse(canContinueAfterSettlement(listOf(result(1, SettlementItemStatus.TRASHED)), 2, DeleteMode.TRASH, null))
        assertFalse(canContinueAfterSettlement(listOf(result(1, SettlementItemStatus.TRASHED)), 1, DeleteMode.TRASH, "error"))
        assertFalse(canContinueAfterSettlement(listOf(result(1, SettlementItemStatus.TRASHED)), 1, DeleteMode.PERMANENT, null))
    }

    @Test fun `successful retry must not hide other unknown items`() {
        val mixed = listOf(result(1, SettlementItemStatus.TRASHED), result(2, SettlementItemStatus.UNKNOWN))
        assertFalse(canContinueAfterSettlement(mixed, 2, DeleteMode.TRASH, null))
    }
}
