package dev.sxc.kadaishan.domain.settle

import dev.sxc.kadaishan.model.DeleteMode
import dev.sxc.kadaishan.model.SettlementItemResult
import dev.sxc.kadaishan.model.SettlementItemStatus

/** Automatic continuation requires a complete, verified success. Empty/partial/unknown results stay visible. */
fun canContinueAfterSettlement(
    results: List<SettlementItemResult>,
    expectedCount: Int,
    mode: DeleteMode,
    error: String?,
): Boolean {
    val expected = if (mode == DeleteMode.TRASH) SettlementItemStatus.TRASHED else SettlementItemStatus.DELETED
    return error == null && expectedCount > 0 && results.size == expectedCount && results.all { it.status == expected }
}
