package dev.sxc.kadaishan.ui.boards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/** Only a loaded, foreground card may consume the shared opening allowance. */
@Composable
internal fun boardGestureHintVisible(
    hints: GestureHintViewModel,
    board: String,
    eligible: Boolean,
    interaction: Any?,
): Boolean {
    val visibleOwner by hints.visibleOwner.collectAsState()
    val foreground by hints.foreground.collectAsState()
    val canShow = eligible && foreground
    val latestInteraction by rememberUpdatedState(interaction)
    LaunchedEffect(hints, board, canShow) {
        hints.showIfEligible(board, canShow)
        if (canShow) {
            // Ignore the initial snapshot; only a successful state change dismisses the hint.
            snapshotFlow { latestInteraction }.drop(1).first()
            hints.dismiss(board)
        }
    }
    DisposableEffect(hints, board) {
        onDispose { hints.dismiss(board) }
    }
    return canShow && visibleOwner == board
}
