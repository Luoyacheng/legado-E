package io.legado.app.service

import java.io.File
import java.io.IOException
import java.io.InputStream

/** Publishes a downloaded audio file only after its complete stream has been copied. */
internal object HttpTtsFileCache {
    private val publishLock = Any()

    /** The caller owns [input]; [checkActive] throws if this download is no longer valid. */
    fun write(target: File, input: InputStream, checkActive: () -> Unit): File {
        checkActive()
        if (target.isFile) return target
        val destination = target.absoluteFile
        val folder = destination.parentFile ?: throw IOException("Cache file has no parent")
        if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) {
            throw IOException("Cannot create audio cache directory")
        }
        val temporary = File.createTempFile("http-tts-", ".part", folder)
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    checkActive()
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            if (temporary.length() == 0L) {
                throw IOException("TTS server returned empty audio")
            }
            synchronized(publishLock) {
                checkActive()
                // Another download may have finished the same audio while we were copying.
                if (!destination.isFile && !temporary.renameTo(destination)) {
                    throw IOException("Cannot publish downloaded audio cache")
                }
            }
            return target
        } finally {
            temporary.delete()
        }
    }
}
