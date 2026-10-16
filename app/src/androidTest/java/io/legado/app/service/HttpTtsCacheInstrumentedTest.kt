package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real streaming cache without starting a service or touching reader data. */
@SuppressLint("UnsafeOptInUsageError")
@RunWith(AndroidJUnit4::class)
class HttpTtsCacheInstrumentedTest {
    private val cacheKey = "next-chapter-paragraph-1-rate-0"
    private val uri = Uri.parse(cacheKey)
    private val audio = ByteArray(4096) { (it % 127).toByte() }

    @Test
    fun prefetchedAudioIsReadFromSameKeyWithoutOpeningUpstreamAgain() {
        withIsolatedCache { cache, executor ->
            val opens = AtomicInteger()
            val factory = factory(cache, opens, AtomicBoolean(false))

            prefetch(factory, executor)

            assertTrue(opens.get() > 0)
            assertTrue(cache.isCached(cacheKey, 0, audio.size.toLong()))
            val opensAfterPrefetch = opens.get()
            assertArrayEquals(audio, readAudio(factory))
            assertEquals(opensAfterPrefetch, opens.get())
        }
    }

    @Test
    fun failedUpstreamLeavesNoPlayableReplacementAndSuccessfulRetryCanBeReused() {
        withIsolatedCache { cache, executor ->
            val opens = AtomicInteger()
            val fail = AtomicBoolean(true)
            val factory = factory(cache, opens, fail)

            assertThrows(IOException::class.java) { prefetch(factory, executor) }

            assertEquals(0L, cache.getCachedBytes(cacheKey, 0, audio.size.toLong()))
            assertFalse(cache.isCached(cacheKey, 0, audio.size.toLong()))
            // Failed speculation must not leave a silent placeholder that looks playable.
            assertThrows(IOException::class.java) { readAudio(factory) }

            fail.set(false)
            prefetch(factory, executor)

            val opensAfterRetry = opens.get()
            assertTrue(cache.isCached(cacheKey, 0, audio.size.toLong()))
            assertArrayEquals(audio, readAudio(factory))
            assertEquals(opensAfterRetry, opens.get())
        }
    }

    private fun factory(
        cache: SimpleCache,
        opens: AtomicInteger,
        fail: AtomicBoolean
    ): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory {
            CountingDataSource(ByteArrayDataSource(audio), opens, fail)
        }
        .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))

    private fun prefetch(factory: CacheDataSource.Factory, executor: ExecutorService) {
        val request = DownloadRequest.Builder(cacheKey, uri).build()
        val downloader = DefaultDownloaderFactory(factory, executor).createDownloader(request)
        runBlocking {
            downloadCancellable({ downloader.download(null) }, downloader::cancel)
        }
    }

    private fun readAudio(factory: CacheDataSource.Factory): ByteArray {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(uri).build())
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(257)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        } finally {
            source.close()
        }
    }

    private fun withIsolatedCache(block: (SimpleCache, ExecutorService) -> Unit) {
        // Instrumentation runs as the target UID; use unique paths isolated from reader caches.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testId = "tts-prefetch-${UUID.randomUUID()}"
        val cacheDirectory = File(context.cacheDir, testId)
        val databaseName = "$testId.db"
        val database = IsolatedDatabaseProvider(context, databaseName)
        val cache = SimpleCache(
            cacheDirectory,
            LeastRecentlyUsedCacheEvictor(1024 * 1024),
            database
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            block(cache, executor)
        } finally {
            executor.shutdownNow()
            cache.release()
            database.close()
            context.deleteDatabase(databaseName)
            cacheDirectory.deleteRecursively()
        }
    }

    private class CountingDataSource(
        private val delegate: DataSource,
        private val opens: AtomicInteger,
        private val fail: AtomicBoolean
    ) : DataSource by delegate {
        override fun open(dataSpec: DataSpec): Long {
            opens.incrementAndGet()
            if (fail.get()) throw IOException("Simulated TTS upstream failure")
            return delegate.open(dataSpec)
        }
    }

    private class IsolatedDatabaseProvider(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, 1), DatabaseProvider {
        override fun onCreate(db: SQLiteDatabase) = Unit
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
