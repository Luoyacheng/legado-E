package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private var ttsInitPending = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private val TAG = "TTSReadAloudService"
    override val paragraphSpeechLimit: Int get() = TextToSpeech.getMaxSpeechInputLength()

    override fun onCreate() {
        super.onCreate()
        kotlin.runCatching {
            initTts()
        }.onFailure {
            ttsInitPending = false
            if (AppConfig.paragraphReadMode) pauseReadAloud()
            AppLog.put("${getString(R.string.tts_init_failed)}\n$it", it, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTTS()
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        ttsInitPending = true
        val engine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
        ttsInitPending = false
    }

    override fun onInit(status: Int) {
        if (AppConfig.paragraphReadMode) {
            lifecycleScope.launch(Main) { finishTtsInitialization(status) }
        } else {
            finishTtsInitialization(status)
        }
    }

    private fun finishTtsInitialization(status: Int) {
        ttsInitPending = false
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                if (paragraphMode && pause) return
                play()
            }
        } else {
            if (AppConfig.paragraphReadMode) pauseReadAloud()
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        if (paragraphMode && paragraphWaitingForChapter) return
        if (!ttsInitFinish) {
            if (paragraphMode && !ttsInitPending) {
                runCatching {
                    clearTTS()
                    initTts()
                }.onFailure {
                    ttsInitPending = false
                    pauseReadAloud()
                    AppLog.put("初始化朗读出错\n${it.localizedMessage}", it, true)
                }
            }
            return
        }
        if (!requestFocus()) {
            if (paragraphMode) pauseReadAloud()
            return
        }
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
            return
        }
        super.play()
        if (paragraphMode) restartParagraphSession()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakJob?.cancel()
        val paragraphPlayback = paragraphMode
        val speechSession = paragraphSession
        val firstSpeak = nowSpeak
        speakJob = execute {
            LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
            LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
            val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
            val contentList = contentList
            var isAddedText = false
            for (i in firstSpeak until contentList.size) {
                ensureActive()
                var text = contentList[i]
                if (paragraphStartPos > 0 && i == nowSpeak) {
                    text = text.substring(paragraphStartPos)
                }
                if (!paragraphPlayback && text.matches(AppPattern.notReadAloudRegex)) {
                    continue
                }
                val utteranceId = if (paragraphPlayback) "paragraph:$speechSession:$i"
                    else AppConst.APP_TAG + i
                if (!isAddedText) {
                    val result = tts.runCatching {
                        speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                    }.getOrElse {
                        AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                        TextToSpeech.ERROR
                    }
                    if (result == TextToSpeech.ERROR) {
                        if (paragraphPlayback) {
                            lifecycleScope.launch(Main) {
                                if (speechSession == paragraphSession) {
                                    clearTTS()
                                    pauseReadAloud()
                                }
                            }
                            return@execute
                        }
                        AppLog.put("tts出错 尝试重新初始化")
                        clearTTS()
                        initTts()
                        return@execute
                    }
                } else {
                    val result = tts.runCatching {
                        speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
                    }.getOrElse {
                        AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                        TextToSpeech.ERROR
                    }
                    if (result == TextToSpeech.ERROR) {
                        AppLog.put("tts朗读出错:$text")
                        if (paragraphPlayback) {
                            lifecycleScope.launch(Main) {
                                if (speechSession == paragraphSession) pauseReadAloud()
                            }
                            return@execute
                        }
                    }
                }
                isAddedText = true
            }
            LogUtils.d(TAG, "朗读内容添加完成")
            if (!isAddedText) {
                playStop()
                delay(1000)
                nextChapter()
            }
        }.onError {
            AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
            if (paragraphPlayback) {
                lifecycleScope.launch(Main) {
                    if (speechSession == paragraphSession) pauseReadAloud()
                }
            }
        }
    }

    override fun playStop() {
        if (paragraphMode) speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            if (s.startsWith("paragraph:")) {
                lifecycleScope.launch(Main) {
                    paragraphIndex(s)?.let { paragraphStarted(it) }
                }
                return
            }
            if (paragraphMode) return
            LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
            textChapter?.let {
                if (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex)) {
                    nextParagraph()
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber + 1 > it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                }
                upTtsProgress(readAloudNumber + 1)
            }
        }

        override fun onDone(s: String) {
            if (s.startsWith("paragraph:")) {
                lifecycleScope.launch(Main) {
                    paragraphIndex(s)?.let { paragraphFinished(it) }
                }
                return
            }
            if (paragraphMode) return
            LogUtils.d(TAG, "onDone utteranceId:$s")
            nextParagraph()
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            if (utteranceId?.startsWith("paragraph:") == true || paragraphMode) return
            super.onRangeStart(utteranceId, start, end, frame)
            val msg =
                "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
            LogUtils.d(TAG, msg)
            textChapter?.let {
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber + start > it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + start)
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (utteranceId?.startsWith("paragraph:") == true) {
                lifecycleScope.launch(Main) {
                    paragraphIndex(utteranceId)?.let { paragraphFinished(it) }
                }
                return
            }
            if (paragraphMode) return
            LogUtils.d(
                TAG,
                "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
            )
            nextParagraph()
        }

        private fun nextParagraph() {
            //跳过全标点段落
            do {
                readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
                paragraphStartPos = 0
                nowSpeak++
                if (nowSpeak >= contentList.size) {
                    nextChapter()
                    return
                }
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            if (s.startsWith("paragraph:")) {
                lifecycleScope.launch(Main) {
                    paragraphIndex(s)?.let { paragraphFinished(it) }
                }
                return
            }
            if (paragraphMode) return
            LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
            nextParagraph()
        }

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}
