package dev.sxc.kadaishan.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded local evidence; no names, paths, media content or network. */
object ReviewDiagnostics {
    private var remaining = 80
    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Mutex()

    @Synchronized fun initialize(context: Context) {
        file = File(context.applicationContext.filesDir, "review-diagnostics.log")
    }

    @Synchronized fun record(message: String) {
        if (remaining <= 0) return
        val line = "${System.currentTimeMillis()} #${80 - remaining--} $message"
        android.util.Log.i("ReviewHistory", line)
        val target = file ?: return
        scope.launch {
            fileLock.withLock {
                runCatching {
                    if (target.length() > 64 * 1024) target.writeText("")
                    target.appendText(line + "\n")
                }
            }
        }
    }
}
