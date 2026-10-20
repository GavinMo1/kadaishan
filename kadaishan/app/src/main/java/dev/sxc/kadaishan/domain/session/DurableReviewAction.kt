package dev.sxc.kadaishan.domain.session

/** Main-thread gate: overlapping gestures are rejected; memory changes only after commit. */
class DurableReviewAction {
    var busy: Boolean = false
        private set

    fun begin(): Boolean {
        if (busy) return false
        busy = true
        return true
    }

    suspend fun complete(commit: suspend () -> Unit, apply: () -> Unit) {
        try {
            commit()
            apply()
        } finally { busy = false }
    }
}
