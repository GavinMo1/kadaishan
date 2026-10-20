package dev.sxc.kadaishan.ui.boards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One allowance for an Activity opening; survives configuration, never persisted across openings. */
class GestureHintViewModel : ViewModel() {
    private val active = MutableStateFlow(false)
    val foreground = active.asStateFlow()

    fun setForeground(value: Boolean) {
        active.value = value
        if (!value) dismiss()
    }

    private var consumed = false
    private var timeout: Job? = null
    private val owner = MutableStateFlow<String?>(null)
    val visibleOwner = owner.asStateFlow()

    fun showIfEligible(board: String, eligible: Boolean) {
        if (!eligible || !active.value) {
            dismiss(board)
            return
        }
        if (consumed) return
        consumed = true
        owner.value = board
        timeout = viewModelScope.launch {
            delay(3_000)
            dismiss(board)
        }
    }

    fun dismiss(board: String? = null) {
        if (board != null && owner.value != board) return
        owner.value = null
        timeout?.cancel()
        timeout = null
    }
}
