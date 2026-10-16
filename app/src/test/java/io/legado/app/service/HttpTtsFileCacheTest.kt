package io.legado.app.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

class HttpTtsFileCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun target() = File(temporaryFolder.root, "audio.mp3")

    @Test
    fun emptyResponseLeavesNoFinalFileOrTemporaryFile() {
        val target = target()

        expectFailure<IOException> {
            HttpTtsFileCache.write(target, ByteArrayInputStream(byteArrayOf())) {}
        }

        assertFalse(target.exists())
        assertEquals(0, temporaryFolder.root.list()?.size)
    }

    @Test
    fun publishesCompleteAudioWithoutExposingPartiallyCopiedFile() {
        val target = target()
        val bytes = ByteArray(32_769) { (it % 251).toByte() }
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertFalse(target.exists())
                return super.read(buffer, offset, length)
            }
        }

        assertEquals(target, HttpTtsFileCache.write(target, input) {})

        assertArrayEquals(bytes, target.readBytes())
        assertEquals(listOf(target.name), temporaryFolder.root.list()?.toList())
    }

    @Test
    fun interruptedStreamLeavesNoFinalFileOrTemporaryFile() {
        val target = target()
        val input = object : InputStream() {
            private var emitted = false
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (emitted) throw IOException("Network disconnected")
                emitted = true
                buffer[offset] = 42
                return 1
            }
        }

        expectFailure<IOException> { HttpTtsFileCache.write(target, input) {} }

        assertFalse(target.exists())
        assertEquals(0, temporaryFolder.root.list()?.size)
    }

    @Test
    fun cancellationDuringDownloadLeavesNoFinalFileOrTemporaryFile() {
        val target = target()
        var cancelled = false
        val input = object : ByteArrayInputStream(ByteArray(10)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                cancelled = true
                return super.read(buffer, offset, length)
            }
        }

        expectFailure<CancellationException> {
            HttpTtsFileCache.write(target, input) {
                if (cancelled) throw CancellationException("Chapter changed")
            }
        }

        assertFalse(target.exists())
        assertEquals(0, temporaryFolder.root.list()?.size)
    }

    @Test
    fun cancellationAtEndOfStreamStillPreventsPublishing() {
        val target = target()
        var cancelled = false
        val input = object : ByteArrayInputStream(ByteArray(10)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count < 0) cancelled = true
                return count
            }
        }

        expectFailure<CancellationException> {
            HttpTtsFileCache.write(target, input) {
                if (cancelled) throw CancellationException("Stopped before publication")
            }
        }

        assertFalse(target.exists())
        assertEquals(0, temporaryFolder.root.list()?.size)
    }

    @Test
    fun existingAudioIsReusedWithoutReadingOrOverwritingIt() {
        val target = target()
        val existing = byteArrayOf(4, 5, 6)
        target.writeBytes(existing)
        val unreadable = object : InputStream() {
            override fun read(): Int = throw IOException("Must not read")
        }

        assertEquals(target, HttpTtsFileCache.write(target, unreadable) {})

        assertArrayEquals(existing, target.readBytes())
        assertEquals(listOf(target.name), temporaryFolder.root.list()?.toList())
    }

    @Test
    fun anotherCompletedDownloadIsPreservedDuringPublication() {
        val target = target()
        val existing = byteArrayOf(4, 5, 6)
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count < 0) target.writeBytes(existing)
                return count
            }
        }

        HttpTtsFileCache.write(target, input) {}

        assertArrayEquals(existing, target.readBytes())
        assertEquals(listOf(target.name), temporaryFolder.root.list()?.toList())
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
