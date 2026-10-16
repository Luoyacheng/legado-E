package io.legado.app.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Cancels the underlying downloader even when its blocking download ignores interruption. */
internal suspend fun downloadCancellable(download: () -> Unit, cancel: () -> Unit) {
    coroutineScope {
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                cancel()
            }
        }
        try {
            runInterruptible(Dispatchers.IO) { download() }
        } finally {
            cancellationWatcher.cancelAndJoin()
        }
    }
}

/** Rejects an empty response without consuming its first byte or blocking cancellation. */
internal suspend fun checkedAudioStream(input: InputStream): InputStream {
    val checked = PushbackInputStream(input, 1)
    val probeCompleted = AtomicBoolean()
    try {
        downloadCancellable(download = {
            val firstByte = checked.read()
            if (firstByte < 0) throw IOException("TTS returned empty audio")
            checked.unread(firstByte)
            probeCompleted.set(true)
        }, cancel = {
            // downloadCancellable also cleans up its watcher after a successful probe.
            if (!probeCompleted.get()) checked.close()
        })
        currentCoroutineContext().ensureActive()
        return checked
    } catch (error: Throwable) {
        runCatching { checked.close() }.onFailure { error.addSuppressed(it) }
        throw error
    }
}
