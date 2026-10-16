package io.legado.app.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HttpTtsDownloadTest {
    @Test
    fun audioProbeRetainsAllBytesAndLeavesSuccessfulStreamOpen() = runBlocking {
        val bytes = byteArrayOf(0, 1, 127, -128, -1)
        val closed = AtomicBoolean()
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() {
                closed.set(true)
                super.close()
            }
        }

        val checked = checkedAudioStream(input)

        assertFalse(closed.get())
        checked.use { assertArrayEquals(bytes, it.readBytes()) }
        assertTrue(closed.get())
    }

    @Test
    fun audioProbeRejectsEmptyResponseAndClosesStream() = runBlocking {
        val closed = AtomicBoolean()
        val input = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() {
                closed.set(true)
                super.close()
            }
        }
        val error = try {
            checkedAudioStream(input)
            null
        } catch (error: IOException) {
            error
        }

        assertEquals("TTS returned empty audio", error?.message)
        assertTrue(closed.get())
    }

    @Test
    fun cancellingBlockedAudioProbeClosesStreamAndWaitsForReaderToExit() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = AtomicBoolean()
        val exited = AtomicBoolean()
        val input = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                try {
                    while (!closed.get()) {
                        if (System.nanoTime() >= deadline) {
                            throw IOException("cancel callback did not close audio stream")
                        }
                        try {
                            released.await(100, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            // The first response byte may wait for stream close, not interruption.
                        }
                    }
                    throw IOException("audio stream closed")
                } finally {
                    exited.set(true)
                }
            }

            override fun close() {
                closed.set(true)
                released.countDown()
            }
        }
        val job = launch { checkedAudioStream(input).close() }

        try {
            withTimeout(5_000) {
                while (entered.count > 0) kotlinx.coroutines.yield()
            }
            job.cancel(CancellationException("chapter changed before first audio byte"))
            withTimeout(5_000) { job.join() }

            assertTrue(job.isCancelled)
            assertTrue(closed.get())
            assertTrue(exited.get())
        } finally {
            input.close()
            job.cancel()
        }
    }

    @Test
    fun completedDownloadReturnsAndClosesCancellationWatcher() = runBlocking {
        val downloadCount = AtomicInteger()
        val cancelCount = AtomicInteger()

        downloadCancellable(
            download = { downloadCount.incrementAndGet() },
            cancel = { cancelCount.incrementAndGet() }
        )

        assertEquals(1, downloadCount.get())
        assertEquals(1, cancelCount.get())
    }

    @Test
    fun downloadFailurePropagatesOriginalError() = runBlocking {
        val expected = IOException("fake download failure")
        val cancelCount = AtomicInteger()
        val actual = try {
            downloadCancellable(
                download = { throw expected },
                cancel = { cancelCount.incrementAndGet() }
            )
            null
        } catch (error: IOException) {
            error
        }

        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
        assertEquals(1, cancelCount.get())
    }

    @Test
    fun cancellationStopsBlockingDownloaderThatIgnoresThreadInterruption() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val exited = AtomicBoolean()
        val cancelCount = AtomicInteger()
        val job = launch {
            downloadCancellable(
                download = {
                    entered.countDown()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    try {
                        while (released.count > 0) {
                            if (System.nanoTime() >= deadline) {
                                throw IOException("cancel callback did not release downloader")
                            }
                            try {
                                released.await(100, TimeUnit.MILLISECONDS)
                            } catch (_: InterruptedException) {
                                // A network downloader may need cancel(), not just a thread interrupt.
                            }
                        }
                    } finally {
                        exited.set(true)
                    }
                },
                cancel = {
                    cancelCount.incrementAndGet()
                    released.countDown()
                }
            )
        }

        try {
            withTimeout(5_000) {
                while (entered.count > 0) kotlinx.coroutines.yield()
            }
            job.cancel(CancellationException("chapter changed"))
            withTimeout(5_000) { job.join() }

            assertTrue(job.isCancelled)
            assertTrue(exited.get())
            assertEquals(1, cancelCount.get())
        } finally {
            released.countDown()
            job.cancel()
        }
    }
}
