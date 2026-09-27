package dev.sxc.kadaishan.domain.settle

import dev.sxc.kadaishan.model.DeleteMode
import dev.sxc.kadaishan.model.RecheckRow
import dev.sxc.kadaishan.model.SettlementItemResult
import dev.sxc.kadaishan.model.SettlementItemStatus
import dev.sxc.kadaishan.model.SnapItem

/**
 * 回查裁决的纯函数。系统弹窗的 RESULT_OK/CANCELED 从不参与这里的判断：
 * 只相信 MATCH_INCLUDE 的实际 MediaStore 状态。
 */
fun verifySettlement(
    snapshot: List<SnapItem>,
    mode: DeleteMode,
    rows: List<RecheckRow>,
    scopeConsistent: Boolean,
): List<SettlementItemResult> {
    val byId = rows.associateBy { it.id }
    return snapshot.map { item ->
        val row = byId[item.id]
        when {
            !scopeConsistent -> SettlementItemResult(item, SettlementItemStatus.UNKNOWN)
            row == null && mode == DeleteMode.PERMANENT ->
                SettlementItemResult(item, SettlementItemStatus.DELETED)
            row == null -> SettlementItemResult(item, SettlementItemStatus.UNKNOWN)
            row.isTrashed -> SettlementItemResult(item, SettlementItemStatus.TRASHED, row.dateExpiresSec)
            else -> SettlementItemResult(item, SettlementItemStatus.NOT_EXECUTED)
        }
    }
}
