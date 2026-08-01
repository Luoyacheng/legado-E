package io.legado.app.ui.rss.read

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.URLUtil
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.script.rhino.runScriptWithContext
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.TTS
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_URL
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.rss.Rss
import io.legado.app.utils.ACache
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeBytes
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import splitties.init.appCtx
import java.util.Date


class ReadRssViewModel(application: Application) : BaseViewModel(application) {
    var rssSource: RssSource? = null
    var rssArticle: RssArticle? = null
    var tts: TTS? = null
    val contentLiveData = MutableLiveData<String>()
    val urlLiveData = MutableLiveData<AnalyzeUrl>()
    val htmlLiveData = MutableLiveData<String>()
    var rssStar: RssStar? = null
    val upTtsMenuData = MutableLiveData<Boolean>()
    val upStarMenuData = MutableLiveData<Boolean>()
    val upTitleData = MutableLiveData<String>()
    var headerMap: Map<String, String> = emptyMap()
    var origin: String? = null
    var hasPreloadJs = false

    fun initData(intent: Intent, success: (() -> Unit)? = null) {
        execute {
            val origin = intent.getStringExtra("origin") ?: return@execute
            this@ReadRssViewModel.origin = origin
            val title = intent.getStringExtra("title") ?: rssSource!!.sourceName
            upTitleData.postValue(title)
            val link = intent.getStringExtra("link")
            rssSource = appDb.rssSourceDao.getByKey(origin)?.also {
                hasPreloadJs = !it.preloadJs.isNullOrBlank()
            }
            headerMap = runScriptWithContext {
                rssSource?.getHeaderMap() ?: emptyMap()
            }
            if (link != null) {
                rssStar = appDb.rssStarDao.get(origin, link)
                val sort = intent.getStringExtra("sort")
                rssArticle = rssStar?.toRssArticle()
                    ?: if (sort == null) {
                        appDb.rssArticleDao.getByLink(origin, link)
                    } else {
                        appDb.rssArticleDao.get(origin, link, sort)
                    }
                rssArticle?.let { article ->
                    if (!article.description.isNullOrBlank()) {
                        contentLiveData.postValue(article.description!!)
                    } else {
                        rssSource?.let {
                            val ruleContent = it.ruleContent
                            if (!ruleContent.isNullOrBlank()) {
                                loadContent(article, ruleContent)
                            } else {
                                loadUrl(article.link, article.origin)
                            }
                        } ?: loadUrl(article.link, article.origin)
                    }
                } ?: return@execute
            } else {
                val ruleContent = rssSource?.ruleContent
                val startHtml = intent.getStringExtra("startHtml")
                val openUrl = intent.getStringExtra("openUrl")
                if (startHtml != null) {
                    loadStartHtml(startHtml)
                } else if (ruleContent.isNullOrBlank() || rssSource!!.singleUrl) {
                    loadUrl(openUrl, origin)
                } else if (openUrl != null) {
                    val rssArticle = appDb.rssArticleDao.getByLink(origin, openUrl) ?: RssArticle(
                        origin, title, title, link = openUrl)
                    loadContent(rssArticle, ruleContent)
                }
            }
        }.onSuccess {
            success?.invoke()
        }.onFinally {
            upStarMenuData.postValue(true)
        }
    }

    private suspend fun loadUrl(url: String?, baseUrl: String) {
        val analyzeUrl = AnalyzeUrl(
            mUrl = url ?: baseUrl,
            baseUrl = baseUrl,
            source = rssSource,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        urlLiveData.postValue(analyzeUrl)
    }

    private fun loadContent(rssArticle: RssArticle, ruleContent: String) {
        val source = rssSource ?: return
        Rss.getContent(viewModelScope, rssArticle, ruleContent, source)
            .onSuccess(IO) { body ->
                rssArticle.description = body
                appDb.rssArticleDao.insert(rssArticle)
                rssStar?.let {
                    it.description = body
                    appDb.rssStarDao.insert(it)
                }
                this@ReadRssViewModel.rssArticle = rssArticle
                contentLiveData.postValue(body)
            }.onError {
                contentLiveData.postValue("加载正文失败\n${it.stackTraceToString()}")
            }
    }

    fun refresh(finish: () -> Unit) {
        val rssArticle = rssArticle ?: return finish.invoke()
        if (!rssArticle.description.isNullOrBlank()) {
            return finish.invoke()
        }
        val rssSource = rssSource ?: let {
            appCtx.toastOnUi("订阅源不存在")
            return finish.invoke()
        }
        val ruleContent = rssSource.ruleContent
        if (!ruleContent.isNullOrBlank()) {
            loadContent(rssArticle, ruleContent)
        } else {
            finish.invoke()
        }
    }

    fun favorite() {
        execute {
            rssStar?.let {
                appDb.rssStarDao.delete(it.origin, it.link)
                rssStar = null
            } ?: rssArticle?.toStar()?.let {
                appDb.rssStarDao.insert(it)
                rssStar = it
            }
        }.onSuccess {
            upStarMenuData.postValue(true)
        }
    }

    fun addFavorite() {
        execute {
            rssStar ?: rssArticle?.toStar()?.let {
                appDb.rssStarDao.insert(it)
                rssStar = it
            }
        }.onSuccess {
            upStarMenuData.postValue(true)
        }
    }

    fun updateFavorite() {
        execute {
            rssArticle?.toStar()?.let {
                appDb.rssStarDao.update(it)
                rssStar = it
            }
        }.onSuccess {
            upStarMenuData.postValue(true)
        }
    }

    fun delFavorite() {
        execute {
            rssStar?.let {
                appDb.rssStarDao.delete(it.origin, it.link)
                rssStar = null
            }
        }.onSuccess {
            upStarMenuData.postValue(true)
        }
    }

    fun saveImage(webPic: String?, uri: Uri) {
        webPic ?: return
        execute {
            val fileName = "${AppConst.fileNameFormat.format(Date(System.currentTimeMillis()))}.jpg"
            val byteArray = webData2bitmap(webPic) ?: throw NoStackTraceException("NULL")
            uri.writeBytes(context, fileName, byteArray)
        }.onError {
            ACache.get().remove(imagePathKey)
            context.toastOnUi("保存图片失败:${it.localizedMessage}")
        }.onSuccess {
            context.toastOnUi("保存成功")
        }
    }

    private suspend fun webData2bitmap(data: String): ByteArray? {
        return if (URLUtil.isValidUrl(data)) {
            okHttpClient.newCallResponseBody {
                url(data)
                headerMap.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }.bytes()
        } else {
            Base64.decode(data.split(",").toTypedArray()[1], Base64.DEFAULT)
        }
    }

    fun clHtml(content: String, style: String?): String {
        val htmlBuilder = StringBuilder(content.length + JS_URL.length + 200)
        if (hasPreloadJs) {
            val headIndex = content.indexOf("<head>")
            if (headIndex >= 0) {
                htmlBuilder.append(content, 0, headIndex + 6)
                htmlBuilder.append(JS_URL)
                htmlBuilder.append(content, headIndex + 6, content.length)
            } else {
                htmlBuilder.append("<head>").append(JS_URL).append("</head>")
                htmlBuilder.append(content)
            }
        } else {
            htmlBuilder.append(content)
        }
        val styleEndIndex = htmlBuilder.indexOf("</style>")
        return if (styleEndIndex >= 0) {
            if (!style.isNullOrBlank()) {
                htmlBuilder.insert(styleEndIndex + 8, "<style>$style</style>").toString()
            } else {
                htmlBuilder.toString()
            }
        } else {
            val finalStyle = style.takeIf { !it.isNullOrBlank() } ?:
            "img{max-width:100% !important; width:auto; height:auto;}video{object-fit:fill; max-width:100% !important; width:auto; height:auto;}body{word-wrap:break-word; height:auto;max-width: 100%; width:auto;}"
            val headEndIndex = htmlBuilder.indexOf("</head>")
            if (headEndIndex >= 0) {
                htmlBuilder.insert(headEndIndex, "<style>$finalStyle</style>").toString()
            } else {
                htmlBuilder.insert(0, "<style>$finalStyle</style>").toString()
            }
        }
    }

    private fun loadStartHtml(startHtml: String) {
        val source = rssSource ?: return
        execute {
            val javascript = rssSource?.startJs
            var processedHtml = if (!javascript.isNullOrBlank()) {
                val bodyEndIndex = startHtml.indexOf("</body>")
                if (bodyEndIndex >= 0) {
                    StringBuilder(startHtml.length + javascript.length + 20)
                        .append(startHtml, 0, bodyEndIndex)
                        .append("<script>$javascript</script>")
                        .append(startHtml, bodyEndIndex, startHtml.length)
                        .toString()
                } else {
                    StringBuilder(startHtml.length + javascript.length + 25)
                        .append("<body>")
                        .append(startHtml)
                        .append("<script>$javascript</script>")
                        .append("</body>")
                        .toString()
                }
            } else {
                startHtml
            }
            processedHtml = clHtml(processedHtml, source.startStyle ?: source.style)
            htmlLiveData.postValue(processedHtml)
        }
    }

    @Synchronized
    fun readAloud(text: String) {
        if (tts == null) {
            tts = TTS().apply {
                setSpeakStateListener(object : TTS.SpeakStateListener {
                    override fun onStart() {
                        upTtsMenuData.postValue(true)
                    }

                    override fun onDone() {
                        upTtsMenuData.postValue(false)
                    }
                })
            }
        }
        tts?.speak(text)
    }

    override fun onCleared() {
        super.onCleared()
        tts?.clearTts()
    }

}