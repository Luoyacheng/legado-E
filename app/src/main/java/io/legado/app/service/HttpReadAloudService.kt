package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "httpTTS" + File.separator
    }
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()
    @Volatile private var downloadGeneration = 0L
    @Volatile private var activeParagraphDownloader: Downloader? = null

    private data class ParagraphDownload(
        val chapter: TextChapter,
        val httpTts: HttpTTS,
        val rate: Int,
        val session: Long,
        val generation: Long
    )

    private fun cancelDownloads() {
        downloadGeneration++
        downloadTask?.cancel()
        activeParagraphDownloader?.cancel()
    }

    private fun captureParagraphDownload(): ParagraphDownload? {
        if (!paragraphMode) return null
        val chapter = textChapter ?: return null
        val httpTts = ReadAloud.httpTTS?.copy() ?: return null
        return ParagraphDownload(chapter, httpTts, speechRate, paragraphSession, downloadGeneration)
    }

    private fun checkParagraphDownload(request: ParagraphDownload) {
        if (!isRun || !paragraphMode || paragraphWaitingForChapter
            || request.session != paragraphSession || request.generation != downloadGeneration
            || ReadBook.book?.bookUrl != request.chapter.chapter.bookUrl
            || ReadBook.durChapterIndex != request.chapter.chapter.index
            || speechRate != request.rate || ReadAloud.httpTTS?.equal(request.httpTts) != true
        ) throw CancellationException("Paragraph audio download is no longer current")
    }

    private suspend fun nextParagraphAudio(request: ParagraphDownload): Pair<TextChapter, List<String>>? {
        val next = withContext(Main) {
            checkParagraphDownload(request)
            // Inspect readiness once; a later layout completion does not schedule another attempt.
            val next = ReadBook.nextTextChapter ?: return@withContext null
            if (!next.isCompleted || next.chapter.bookUrl != request.chapter.chapter.bookUrl
                || next.chapter.index != request.chapter.chapter.index + 1
            ) return@withContext null
            next
        } ?: return null
        return next to ParagraphSpeechQueue.buildPrefetch(next.paragraphItems, paragraphSpeechLimit)
            .map { it.text }
    }

    private suspend fun writeParagraphAudio(
        fileName: String,
        stream: InputStream,
        request: ParagraphDownload
    ) {
        val context = currentCoroutineContext()
        stream.use {
            downloadCancellable(download = {
                HttpTtsFileCache.write(getSpeakFileAsMd5(fileName), it) {
                    context.ensureActive()
                    checkParagraphDownload(request)
                }
            }, cancel = it::close)
        }
    }

    private suspend fun downloadParagraphStreamAudio(
        downloader: Downloader,
        request: ParagraphDownload
    ) {
        currentCoroutineContext().ensureActive()
        checkParagraphDownload(request)
        activeParagraphDownloader = downloader
        try {
            currentCoroutineContext().ensureActive()
            checkParagraphDownload(request)
            downloadCancellable({ downloader.download(null) }, downloader::cancel)
        } finally {
            if (activeParagraphDownloader === downloader) activeParagraphDownloader = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        cancelDownloads()
        super.onDestroy()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        if (paragraphMode && paragraphWaitingForChapter) return
        if (paragraphMode) restartParagraphSession()
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) {
            if (paragraphMode) pauseReadAloud()
            return
        }
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        if (paragraphMode) {
            cancelDownloads()
            exoPlayer.clearMediaItems()
        }
        exoPlayer.stop()
        playIndexJob?.cancel()
    }

    private fun updateNextPos() {
        if (paragraphMode) {
            paragraphFinished(nowSpeak)
            return
        }
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter()
        }
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        cancelDownloads()
        val paragraphPlayback = paragraphMode
        val paragraphRequest = captureParagraphDownload()
        val speechSession = paragraphSession
        val firstSpeak = nowSpeak
        val speechContents = contentList
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = paragraphRequest?.httpTts ?: ReadAloud.httpTTS
                    ?: throw NoStackTraceException("tts is null")
                if (paragraphPlayback && paragraphRequest == null) {
                    throw NoStackTraceException("Paragraph chapter is not ready")
                }
                speechContents.forEachIndexed { index, content ->
                    ensureActive()
                    paragraphRequest?.let(::checkParagraphDownload)
                    if (index < firstSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val fileName = if (paragraphRequest != null) {
                        md5SpeakFileName(text, paragraphRequest.chapter, httpTts, paragraphRequest.rate)
                    } else md5SpeakFileName(text)
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
                        createSilentSound(fileName)
                    } else if (!hasSpeakFile(fileName)) {
                        runCatching {
                            val inputStream = getSpeakStream(httpTts, speakText,
                                requestRate = paragraphRequest?.rate ?: speechRate)
                            if (inputStream != null) {
                                if (paragraphRequest != null) {
                                    writeParagraphAudio(fileName, inputStream, paragraphRequest)
                                } else createSpeakFile(fileName, inputStream)
                            } else {
                                createSilentSound(fileName)
                            }
                        }.onFailure {
                            when (it) {
                                is CancellationException -> Unit
                                else -> if (paragraphPlayback) {
                                    pauseParagraphDownloadFailure(speechSession, paragraphRequest?.generation)
                                } else {
                                    pauseReadAloud()
                                }
                            }
                            return@execute
                        }
                    }
                    val file = getSpeakFileAsMd5(fileName)
                    val mediaItem = MediaItem.Builder().setUri(Uri.fromFile(file)).apply {
                        if (paragraphPlayback) setMediaId("paragraph:$speechSession:$index")
                    }.build()
                    launch(Main) {
                        if (paragraphPlayback && speechSession != paragraphSession) return@launch
                        exoPlayer.addMediaItem(mediaItem)
                    }
                }
                if (paragraphRequest != null) preDownloadParagraphAudio(paragraphRequest)
                else preDownloadAudios(httpTts)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
            if (paragraphPlayback && it !is CancellationException) {
                pauseParagraphDownloadFailure(speechSession, paragraphRequest?.generation)
            }
        }
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        if (paragraphMode) return
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(10)
            .toList()
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val fileName = md5SpeakFileName(content, textChapter)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                runCatching {
                    val inputStream = getSpeakStream(httpTts, speakText)
                    if (inputStream != null) {
                        createSpeakFile(fileName, inputStream)
                    } else {
                        createSilentSound(fileName)
                    }
                }
            }
        }
    }

    private suspend fun preDownloadParagraphAudio(request: ParagraphDownload) {
        val (next, contents) = nextParagraphAudio(request) ?: return
        try {
            for (content in contents) {
                currentCoroutineContext().ensureActive()
                checkParagraphDownload(request)
                val fileName = md5SpeakFileName(content, next, request.httpTts, request.rate)
                if (hasSpeakFile(fileName)) continue
                val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                val stream = getSpeakStream(request.httpTts, speakText,
                    prefetch = true, requestRate = request.rate) ?: return
                writeParagraphAudio(fileName, stream, request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("下一章朗读音频预下载失败\n${e.localizedMessage}", e)
        }
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        cancelDownloads()
        val paragraphPlayback = paragraphMode
        val speechSession = paragraphSession
        val firstSpeak = nowSpeak
        val speechContents = contentList
        if (paragraphPlayback) {
            val request = captureParagraphDownload() ?: return
            downloadParagraphAudiosStream(request, speechContents, firstSpeak)
            return
        }
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val downloaderChannel = Channel<Downloader>()
                launch {
                    for (downloader in downloaderChannel) {
                        downloader.download(null)
                    }
                }
                speechContents.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < firstSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$speakText")
                    }
                    val fileName = md5SpeakFileName(text)
                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                    val downloader = createDownloader(dataSourceFactory, fileName)
                    downloaderChannel.send(downloader)
                    val mediaSource = createMediaSource(dataSourceFactory, fileName,
                        if (paragraphPlayback) "paragraph:$speechSession:$index" else null)
                    launch(Main) {
                        if (paragraphPlayback && speechSession != paragraphSession) return@launch
                        exoPlayer.addMediaSource(mediaSource)
                    }
                }
                preDownloadAudiosStream(httpTts, downloaderChannel)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
            if (paragraphPlayback && it !is CancellationException) {
                lifecycleScope.launch(Main) {
                    if (speechSession == paragraphSession && !paragraphWaitingForChapter) {
                        pauseReadAloud()
                    }
                }
            }
        }
    }

    private fun downloadParagraphAudiosStream(
        request: ParagraphDownload,
        contents: List<String>,
        firstSpeak: Int
    ) {
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                val downloaders = Channel<Downloader>()
                val worker = launch {
                    for (downloader in downloaders) {
                        ensureActive()
                        checkParagraphDownload(request)
                        downloadParagraphStreamAudio(downloader, request)
                    }
                }
                try {
                    contents.forEachIndexed { index, content ->
                        ensureActive()
                        checkParagraphDownload(request)
                        if (index < firstSpeak) return@forEachIndexed
                        val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                        val fileName = md5SpeakFileName(content, request.chapter,
                            request.httpTts, request.rate)
                        val downloadFactory = createDataSourceFactory(request.httpTts, speakText,
                            requestRate = request.rate, requestJob = coroutineContext[Job],
                            paragraphRequest = request)
                        downloaders.send(createDownloader(downloadFactory, fileName))
                        val playbackFactory = createDataSourceFactory(request.httpTts, speakText,
                            requestRate = request.rate, paragraphRequest = request)
                        val mediaSource = createMediaSource(playbackFactory, fileName,
                            "paragraph:${request.session}:$index")
                        launch(Main) {
                            if (request.generation != downloadGeneration
                                || request.session != paragraphSession
                            ) return@launch
                            exoPlayer.addMediaSource(mediaSource)
                        }
                    }
                } finally {
                    downloaders.close()
                }
                // The last send only hands off a downloader; join waits for its audio to finish.
                worker.join()
                ensureActive()
                checkParagraphDownload(request)
                preDownloadParagraphAudioStream(request)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
            if (it !is CancellationException) {
                pauseParagraphDownloadFailure(request.session, request.generation)
            }
        }
    }

    private suspend fun preDownloadParagraphAudioStream(request: ParagraphDownload) {
        val (next, contents) = nextParagraphAudio(request) ?: return
        try {
            for (content in contents) {
                currentCoroutineContext().ensureActive()
                checkParagraphDownload(request)
                val fileName = md5SpeakFileName(content, next, request.httpTts, request.rate)
                val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                val factory = createDataSourceFactory(request.httpTts, speakText,
                    prefetch = true, requestRate = request.rate,
                    requestJob = currentCoroutineContext()[Job], paragraphRequest = request)
                val downloader = createDownloader(factory, fileName)
                downloadParagraphStreamAudio(downloader, request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("下一章朗读音频预下载失败\n${e.localizedMessage}", e)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        if (paragraphMode) return
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(10)
            .toList()
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val fileName = md5SpeakFileName(content, textChapter)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
            val downloader = createDownloader(dataSourceFactory, fileName)
            downloaderChannel.send(downloader)
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String,
        prefetch: Boolean = false,
        requestRate: Int = speechRate,
        requestJob: Job? = null,
        paragraphRequest: ParagraphDownload? = null
    ): CacheDataSource.Factory {
        val paragraphPlayback = paragraphMode
        val speechSession = paragraphRequest?.session ?: paragraphSession
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(requestJob ?: lifecycleScope.coroutineContext[Job]!!) {
                            paragraphRequest?.let(::checkParagraphDownload)
                            getSpeakStream(httpTts, speakText, prefetch, requestRate)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> if (prefetch) {
                                // A speculative download must never pause the current chapter.
                            } else if (paragraphPlayback) {
                                pauseParagraphDownloadFailure(speechSession, paragraphRequest?.generation)
                            } else {
                                pauseReadAloud()
                            }
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun pauseParagraphDownloadFailure(speechSession: Long, generation: Long? = null) {
        lifecycleScope.launch(Main.immediate) {
            if (paragraphMode && speechSession == paragraphSession && !paragraphWaitingForChapter
                && (generation == null || generation == downloadGeneration)
            ) {
                pauseReadAloud()
            }
        }
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(
        factory: DataSource.Factory,
        fileName: String,
        speechId: String? = null
    ): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(MediaItem.Builder().setUri(fileName).apply {
                if (speechId != null) setMediaId(speechId)
            }.build())
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        prefetch: Boolean = false,
        requestRate: Int = speechRate
    ): InputStream? {
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = requestRate,
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                val checkJs = httpTts.loginCheckJs
                val response = kotlin.runCatching {
                    analyzeUrl.getResponseAwait().let {
                        currentCoroutineContext().ensureActive()
                        if (!checkJs.isNullOrBlank()) {
                            analyzeUrl.evalJS(checkJs, it) as Response
                        } else {
                            it
                        }
                    }
                }.getOrElse { throwable ->
                    currentCoroutineContext().ensureActive()
                    if (!checkJs.isNullOrBlank()) {
                        val errResponse = analyzeUrl.getErrResponse(throwable)
                        try {
                            (analyzeUrl.evalJS(checkJs, errResponse) as Response).also {
                                if (it.code == 500) {
                                    throw throwable
                                }
                            }
                        } catch (_: Throwable) {
                            throw throwable
                        }
                    } else {
                        throw throwable
                    }
                }
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                response.body.byteStream().let { stream ->
                    if (prefetch) {
                        return checkedAudioStream(stream)
                    }
                    downloadErrorNo = 0
                    return stream
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (prefetch) throw e
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    private fun md5SpeakFileName(
        content: String,
        textChapter: TextChapter? = this.textChapter,
        httpTts: HttpTTS? = ReadAloud.httpTTS,
        rate: Int = speechRate
    ): String {
        return MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
                MD5Utils.md5Encode16("${httpTts?.url}-|-$rate-|-$content")
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
    }

    /**
     * 移除缓存文件
     */
    private fun removeCacheFile() {
        val titleMd5 = MD5Utils.md5Encode16(textChapter?.title ?: "")
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L
            if ((!it.name.startsWith(titleMd5)
                        && System.currentTimeMillis() - it.lastModified() > 600000)
                || isSilentSound
            ) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        if (paragraphMode) {
            paragraphIndex(exoPlayer.currentMediaItem?.mediaId)?.let { paragraphStarted(it) }
            return
        }
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        cancelDownloads()
        if (paragraphMode) restartParagraphSession()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        if (paragraphMode) {
            val index = paragraphIndex(exoPlayer.currentMediaItem?.mediaId) ?: return
            when (playbackState) {
                Player.STATE_READY -> {
                    exoPlayer.play()
                    paragraphStarted(index)
                }
                Player.STATE_ENDED -> {
                    val speechSession = paragraphSession
                    val hasMore = index < paragraphSpeechItems.lastIndex
                    paragraphFinished(index)
                    // Downloads may append the next audio after this short playlist ends.
                    if (hasMore && speechSession == paragraphSession && !paragraphWaitingForChapter) {
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                    }
                }
            }
            return
        }
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (paragraphMode) {
            paragraphIndex(mediaItem?.mediaId)?.let { paragraphStarted(it) }
            return
        }
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        updateNextPos()
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        if (paragraphMode && paragraphIndex(exoPlayer.currentMediaItem?.mediaId) == null) return
        AppLog.put("朗读错误\n${contentList[nowSpeak]}", error)
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}
