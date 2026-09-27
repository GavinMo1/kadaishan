package dev.sxc.kadaishan.domain.settle

import android.net.Uri
import dev.sxc.kadaishan.model.DeleteMode
import dev.sxc.kadaishan.model.RecheckRow
import dev.sxc.kadaishan.model.SettlementItemStatus
import dev.sxc.kadaishan.model.SnapItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SettlementVerifierTest {
    private val first = SnapItem(1, Uri.parse("content://media/1"), "a.jpg", 10)
    private val second = SnapItem(2, Uri.parse("content://media/2"), "b.jpg", 20)

    @Test fun `trash recheck distinguishes trashed unexecuted and unknown`() {
        val actual = verifySettlement(
            listOf(first, second, SnapItem(3, Uri.parse("content://media/3"), "c.jpg", 30)),
            DeleteMode.TRASH,
            listOf(RecheckRow(1, true, 99), RecheckRow(2, false, null)),
            scopeConsistent = true,
        )
        assertEquals(
            listOf(SettlementItemStatus.TRASHED, SettlementItemStatus.NOT_EXECUTED, SettlementItemStatus.UNKNOWN),
            actual.map { it.status },
        )
        assertEquals(99, actual.first().dateExpiresSec)
    }

    @Test fun `permanent absence is deleted but scope change stays unknown`() {
        assertEquals(
            SettlementItemStatus.DELETED,
            verifySettlement(listOf(first), DeleteMode.PERMANENT, emptyList(), true).single().status,
        )
        assertEquals(
            SettlementItemStatus.UNKNOWN,
            verifySettlement(listOf(first), DeleteMode.PERMANENT, emptyList(), false).single().status,
        )
    }
}
