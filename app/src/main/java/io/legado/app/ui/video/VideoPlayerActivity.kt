package io.legado.app.ui.video

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.textclassifier.TextClassifier
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ActivityVideoPlayerBinding
import io.legado.app.help.GlideImageGetter
import io.legado.app.help.TextViewTagHandler
import io.legado.app.help.WebCacheManager
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.buildUseWebInjection
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebJsExtensions.Companion.wrapUseWebHtml
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewPool.fitInlineContent
import io.legado.app.help.webView.WebViewPool.installInlineContentRefitOnTouch
import io.legado.app.help.webView.WebViewPool.prepareForInlineContent
import io.legado.app.help.webView.WebViewPool.scheduleInlineContentFit
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.VideoPlay
import io.legado.app.service.VideoPlayService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.favorites.RssFavoritesDialog
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.video.config.SettingsDialog
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.text.ScrollTextView
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setHtml
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.setTintMutate
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.app.help.InnerBrowserLinkResolver
import androidx.compose.ui.platform.ComposeView
import io.legado.app.ui.theme.LegadoTheme
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoPlayerActivity : VMBaseActivity<ActivityVideoPlayerBinding, VideoPlayerViewModel>(),
    SettingsDialog.CallBack,RssFavoritesDialog.Callback {
    override val binding by viewBinding(ActivityVideoPlayerBinding::inflate)
    override val viewModel by viewModels<VideoPlayerViewModel>()
    private val playerView: VideoPlayer by lazy { binding.playerView }
    private var starMenuItem: MenuItem? = null
    private var initIntroView = false
    private val introTextView by lazy {
        initIntroView = true
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_book_intro, binding.tvIntroContainer, false) as ScrollTextView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            view.revealOnFocusHint = false
        }
        view
    }
    private var pooledWebView: PooledWebView? = null
    private val imgAvailableWidth by lazy {
        val textView = introTextView
        textView.width - textView.paddingLeft - textView.paddingRight - 8.dpToPx()
    }
    private var initGetter = false
    private val glideImageGetter by lazy {
        initGetter = true
        GlideImageGetter(
            this,
            introTextView,
            lifecycle,
            imgAvailableWidth,
            VideoPlay.source?.getKey()
        )
    }

    private val textViewTagHandler by lazy {
        TextViewTagHandler(object : TextViewTagHandler.OnButtonClickListener {
            override fun onButtonClick(name: String, click: String) {
                viewModel.onButtonClick(this@VideoPlayerActivity, "info button $name" , click)
            }
        })
    }
    private var isNew = true
    private var isStartingNew = false
    private var isFullScreen = false
    private var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var menuCustomBtn: MenuItem? = null
    private val bookSourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource {
                    menuCustomBtn?.isVisible = (VideoPlay.source as? BookSource)?.customButton == true
                }
            }
        }
    private val rssSourceEditResult =
        registerForActivityResult(StartActivityContract(RssSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }
    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            if (it[2] as Boolean) {
                VideoPlay.chapterInVolumeIndex = it[0] as Int
                val durChapterPos = it[1] as Int
                VideoPlay.durVolumeIndex = it[3] as Int
                VideoPlay.chapterInVolumeIndex = it[4] as Int
                VideoPlay.upEpisodes()
                VideoPlay.saveRead(durChapterPos)
                if (VideoPlay.episodes.isNullOrEmpty()) {
                    binding.chapters.visibility = View.GONE
                } else {
                    binding.chapters.visibility = View.VISIBLE
                    val adapter = binding.chapters.adapter as? ChapterAdapter
                    adapter?.updateData(VideoPlay.episodes)
                }
                upView()
                VideoPlay.startPlay(playerView)
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        playerView.enlargeImageRes = R.drawable.ic_fullscreen
        isNew = intent.getBooleanExtra("isNew", true)
        if (isNew) {
            startNewSession()
        } else {
            VideoPlay.clonePlayState(playerView)
            playerView.setSurfaceToPlay()
            playerView.startAfterPrepared()
            binding.titleBar.title = VideoPlay.videoTitle
        }
        setupPlayerView()
        initView()
        // 静音播放视频toast提示
        if (VideoPlay.mutePlay) {
            toastOnUi(R.string.mute_play_enabled)
        }
        upView()
        onBackPressedDispatcher.addCallback(this) {
            if (isFullScreen) {
                toggleFullScreen()
                return@addCallback
            }
            finish()
        }
    }

    /**
     * 处理 singleTask 复用 Activity 时收到的新 Intent。
     * 当从发现列表/书籍详情页切换书籍时，旧 Activity 会被复用，
     * 新的 bookUrl 通过 onNewIntent 传入，需要重新初始化播放。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 如果在全屏模式，先退出全屏
        if (isFullScreen) {
            toggleFullScreen()
        }
        startNewSession()
        initView()
        upView()
    }

    /**
     * 初始化新的播放会话：停止旧播放、重置状态、加载新书源数据、开始播放。
     * 被 onActivityCreated(isNew=true) 和 onNewIntent() 共同调用。
     */
    private fun startNewSession() {
        // 标记新会话启动，跳过 onResume 恢复旧视频
        isStartingNew = true
        // 停止上一次的播放并释放媒体，防止 onResume 时旧视频被恢复
        VideoPlay.stopLoading()
        VideoPlay.stopPlayback()
        // 重置可能残留的上一次播放状态，防止旧链接/标题泄漏到新会话
        VideoPlay.singleUrl = false
        VideoPlay.videoUrl = null
        VideoPlay.videoTitle = null
        intent.getStringExtra("videoUrl")?.let {
            VideoPlay.videoUrl = it
            VideoPlay.singleUrl = true
        }
        intent.getStringExtra("videoTitle")?.let {
            binding.titleBar.title = it
            VideoPlay.videoTitle = it
        }
        val sourceKey = intent.getStringExtra("sourceKey")
        val sourceType = intent.getIntExtra("sourceType", 0)
        val bookUrl = intent.getStringExtra("bookUrl")
        val record = intent.getStringExtra("record")
        VideoPlay.inBookshelf = intent.getBooleanExtra("inBookshelf", true)
        if (!VideoPlay.initSource(sourceKey, sourceType, bookUrl, record)) {
            finish()
            return
        }
        VideoPlay.startPlay(playerView)
        VideoPlay.saveRead()
    }

    private fun initView() {
        viewModel.upStarMenuData.observe(this) { upStarMenu() }
        binding.root.setBackgroundColor(backgroundColor)
        val book = VideoPlay.book
        if (book == null) {
            binding.data.invisible()
            binding.chaptersContainer.invisible()
            binding.quickJumpButtons.gone()
            return
        }
        showBook(book)
        setupQuickJumpButtons()
        if (VideoPlay.episodes.isNullOrEmpty()) {
            binding.chapters.gone()
        } else {
            binding.chapters.visible()
            showToc(VideoPlay.episodes!!)
        }
        if (VideoPlay.volumes.isEmpty()) {
            binding.volumes.gone()
        } else {
            binding.volumes.visible()
            showVolumes(VideoPlay.volumes)
        }
    }

    private fun setupQuickJumpButtons() {
        binding.quickJumpButtons.setContent {
            // 直接读取 VideoPlay 的属性值，每次 setContent 时会获取最新值
            LegadoTheme {
                QuickJumpButtons(
                    enabled = VideoPlay.quickJumpButtonsEnabled,
                    minutesA = VideoPlay.quickJumpMinutesA,
                    minutesB = VideoPlay.quickJumpMinutesB,
                    onBackA = { performQuickJump(-VideoPlay.quickJumpMinutesA) },
                    onBackB = { performQuickJump(-VideoPlay.quickJumpMinutesB) },
                    onForwardB = { performQuickJump(VideoPlay.quickJumpMinutesB) },
                    onForwardA = { performQuickJump(VideoPlay.quickJumpMinutesA) }
                )
            }
        }
    }
    
    private fun updateQuickJumpButtonsState() {
        // 非全屏模式：根据开关状态更新 ComposeView 可见性和内容
        if (VideoPlay.quickJumpButtonsEnabled && !isFullScreen && VideoPlay.book != null) {
            binding.quickJumpButtons.visible()
            binding.quickJumpButtons.setContent {
                LegadoTheme {
                    QuickJumpButtons(
                        enabled = VideoPlay.quickJumpButtonsEnabled,
                        minutesA = VideoPlay.quickJumpMinutesA,
                        minutesB = VideoPlay.quickJumpMinutesB,
                        onBackA = { performQuickJump(-VideoPlay.quickJumpMinutesA) },
                        onBackB = { performQuickJump(-VideoPlay.quickJumpMinutesB) },
                        onForwardB = { performQuickJump(VideoPlay.quickJumpMinutesB) },
                        onForwardA = { performQuickJump(VideoPlay.quickJumpMinutesA) }
                    )
                }
            }
        } else {
            binding.quickJumpButtons.gone()
        }

        // 全屏模式：更新全屏播放器内的快捷跳转按钮
        playerView.getFullWindowPlayer()?.updateQuickJumpButtons()
    }

    private fun performQuickJump(minutes: Int) {
        val currentPosition = playerView.getCurrentPositionWhenPlaying()
        val duration = playerView.getDuration()
        val jumpMs = minutes * 60 * 1000L
        val newPosition = (currentPosition + jumpMs).coerceIn(0, duration)
        playerView.seekTo(newPosition)
    }

    private fun showBook(book: Book) {
        binding.run {
            showCover(book)
            tvName.text = book.name
            book.getRealAuthor().takeIf { it.isNotEmpty() }?.let {
                tvAuthor.text = it
            } ?: tvAuthor.gone()
            showBookIntro(book)
        }
    }

    inner class CustomWebViewClient(
        private val source: io.legado.app.data.entities.BaseSource?
    ) : WebViewClient() {
        private val jsStr = buildUseWebInjection(source)
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                val uri = it.url
                return when (uri.scheme) {
                    "http", "https" -> false
                    "legado", "yuedu" -> {
                        startActivity<OnLineImportActivity> {
                            data = uri
                        }
                        true
                    }

                    else -> {
                        binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                            openUrl(uri)
                        }
                        true
                    }
                }
            }
            return true
        }
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            view?.evaluateJavascript(jsStr, null)
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.let {
                scheduleInlineContentFit(it, afterLayout = {
                    binding.tvIntroContainer.requestLayout()
                })
            }
        }
    }

    private fun showBookIntro(book: Book) {
        val intro = book.getDisplayIntro()
        if (intro?.startsWith("<useweb>") == true) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 8) {
                introTextView.text = intro
                return
            }
            val html = wrapUseWebHtml(intro.substring(8, lastIndex), VideoPlay.source)
            val pooledWebView = this.pooledWebView ?: let{
                val pooledWebView = WebViewPool.acquire(this)
                val webView = pooledWebView.realWebView
                webView.onResume()
                prepareForInlineContent(webView)
                installInlineContentRefitOnTouch(webView) {
                    binding.tvIntroContainer.requestLayout()
                }
                webView.webViewClient = CustomWebViewClient(VideoPlay.source)
                webView.addJavascriptInterface(WebCacheManager, nameCache)
                VideoPlay.source?.let {
                    webView.addJavascriptInterface(it, nameSource)
                    val webJsExtensions = WebJsExtensions(it, this, webView)
                    webView.addJavascriptInterface(webJsExtensions, nameJava)
                }
                pooledWebView
            }
            val webView = pooledWebView.realWebView
            prepareForInlineContent(webView)
            installInlineContentRefitOnTouch(webView) {
                binding.tvIntroContainer.requestLayout()
            }
            if (initIntroView || this.pooledWebView == null) {
                initIntroView = false
                this.pooledWebView = pooledWebView
                binding.tvIntroContainer.removeAllViews()
                binding.tvIntroContainer.addView(webView)
            }
            val bookUrl = VideoPlay.book?.bookUrl
                ?.takeIf { it.startsWith("http", true) }
                ?.substringBefore(",")
            webView.loadDataWithBaseURL(bookUrl, html, "text/html", "utf-8", bookUrl)
            return
        }
        if (!initIntroView || pooledWebView != null) {
            destroyWeb()
            binding.tvIntroContainer.removeAllViews()
            binding.tvIntroContainer.addView(introTextView)
        }
        if (intro.isNullOrBlank()) {
            return
        }
        val tvIntro = introTextView
        if (intro.startsWith("<usehtml>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 9) {
                tvIntro.text = intro
                return
            }
            val html = intro.substring(9, lastIndex)
            tvIntro.setHtml(
                html,
                glideImageGetter,
                textViewTagHandler,
                imgOnLongClickListener = {
                    showDialogFragment(PhotoDialog(it, VideoPlay.source?.getKey()))
                },
                imgOnClickListener = {
                    viewModel.onButtonClick(this@VideoPlayerActivity, "info image" , it)
                }
            )
        } else if (intro.startsWith("<md>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 4) {
                tvIntro.text = intro
                return
            }
            val mark = intro.substring(4, lastIndex)
            lifecycleScope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tvIntro.setTextClassifier(TextClassifier.NO_OP)
                }
                val context = this@VideoPlayerActivity
                val markwon: Markwon
                val markdown = withContext(IO) {
                    markwon = Markwon.builder(context)
                        .usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
                            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                                builder.linkResolver(InnerBrowserLinkResolver)
                            }
                        })
                        .usePlugin(
                            GlideImagesPlugin.create(
                                Glide.with(context)
                                    .applyDefaultRequestOptions(
                                        RequestOptions()
                                            .override(imgAvailableWidth)
                                            .encodeQuality(88)
                                    )
                            )
                        )
                        .usePlugin(HtmlPlugin.create())
                        .usePlugin(TablePlugin.create(context))
                        .build()
                    markwon.toMarkdown(mark)
                }
                tvIntro.setMarkdown(
                    markwon,
                    markdown,
                    imgOnLongClickListener = { source ->
                        showDialogFragment(PhotoDialog(source, VideoPlay.source?.getKey()))
                    }
                )
            }
        } else {
            tvIntro.text = intro
        }
    }

    private fun showCover(book: Book) {
        binding.ivCover.load(book, false)
    }

    private fun showToc(toc: List<BookChapter>) {
        binding.ivChapter.setOnClickListener {
            VideoPlay.book?.bookUrl?.let {
                tocActivityResult.launch(it)
            }
        }
        val recyclerView = binding.chapters
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.layoutManager = layoutManager
        val adapter = ChapterAdapter(toc,VideoPlay.chapterInVolumeIndex, false) { chapter, index ->
            if (index != VideoPlay.chapterInVolumeIndex) {
                VideoPlay.chapterInVolumeIndex = index
                VideoPlay.saveRead(0)
                upEpisodesView()
                VideoPlay.startPlay(playerView)
            }
        }
        recyclerView.adapter = adapter
        scrollToDurChapter(recyclerView, VideoPlay.chapterInVolumeIndex)
    }

    private fun showVolumes(volumes: List<BookChapter>) {
        val recyclerView = binding.volumes
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.layoutManager = layoutManager
        val adapter = ChapterAdapter(volumes,VideoPlay.durVolumeIndex, true) { chapter, index ->
            if (index != VideoPlay.durVolumeIndex) {
                VideoPlay.durVolumeIndex = index
                VideoPlay.chapterInVolumeIndex = 0
                VideoPlay.upEpisodes()
                if (VideoPlay.episodes.isNullOrEmpty()) {
                    binding.chapters.visibility = View.GONE
                } else {
                    binding.chapters.visibility = View.VISIBLE
                    val adapter = binding.chapters.adapter as? ChapterAdapter
                    adapter?.updateData(VideoPlay.episodes)
                }
                VideoPlay.saveRead(0)
                upVolumesView()
                VideoPlay.startPlay(playerView)
            }
        }
        recyclerView.adapter = adapter
        scrollToDurChapter(recyclerView, VideoPlay.durVolumeIndex)
    }

    private fun scrollToDurChapter(recyclerView: RecyclerView, index: Int) {
        recyclerView.postDelayed({
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.run {
                val smoothScroller = object : LinearSmoothScroller(this@VideoPlayerActivity) {
                    override fun getHorizontalSnapPreference(): Int {
                        return SNAP_TO_START // 滚动到最左边
                    }
                }
                smoothScroller.targetPosition = index
                this.startSmoothScroll(smoothScroller)
            }
            val adapter = recyclerView.adapter as? ChapterAdapter
            adapter?.updateSelectedPosition(index)
        }, 200)
    }

    private fun upView() {
        upEpisodesView()
        upVolumesView()
    }

    private fun upEpisodesView() {
        if (!VideoPlay.episodes.isNullOrEmpty()) {
            scrollToDurChapter(binding.chapters, VideoPlay.chapterInVolumeIndex)
        }
    }

    private fun upVolumesView() {
        if (!VideoPlay.volumes.isEmpty()) {
            scrollToDurChapter(binding.volumes, VideoPlay.durVolumeIndex)
        }
    }

    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        toggleSystemBar(!isFullScreen)
        if (isFullScreen) {
            orientation = requestedOrientation
            requestedOrientation = if (VideoPlay.isPortraitVideo) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT //竖屏
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE //横屏
            }
            supportActionBar?.hide()
            binding.chaptersContainer.gone()
            binding.data.gone()
            binding.quickJumpButtons.gone()
            playerView.startWindowFullscreen(this, false, false)
        } else {
            requestedOrientation = orientation
            supportActionBar?.show()
            if (VideoPlay.book != null) {
                binding.chaptersContainer.visible()
                binding.data.visible()
                if (VideoPlay.quickJumpButtonsEnabled) {
                    binding.quickJumpButtons.visible()
                }
            }
            playerView.postDelayed({
                playerView.backFromFull(this)
            }, if (VideoPlay.isPortraitVideo) 300 else 0)
            upView()
        }
    }


    @Suppress("DEPRECATION")
    @SuppressLint("SwitchIntDef")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFullScreen) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            when (newConfig.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> {
                    if (!VideoPlay.isPortraitVideo) {
                        toggleFullScreen()
                    }
                }
                Configuration.ORIENTATION_PORTRAIT -> {
                    if (VideoPlay.isPortraitVideo) {
                        toggleFullScreen()
                    }
                }
            }
        }
    }

    private fun setupPlayerView() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val layoutParams = playerView.layoutParams
        layoutParams.width = screenWidth
        val videoWidth = playerView.currentVideoWidth
        val videoHeight = playerView.currentVideoHeight
        val height = if (videoWidth > 0 && videoHeight > 0) (screenWidth * videoHeight / videoWidth) else (screenWidth * 9 / 16) //默认16:9
        //高度不超过一半屏幕
        layoutParams.height = if (height < screenHeight / 2) height else screenHeight / 2
        playerView.layoutParams = layoutParams
        playerView.isNeedOrientationUtils = false //关闭自带的屏幕方向控制
        playerView.fullscreenButton.setOnClickListener { toggleFullScreen() }
        playerView.setBackFromFullScreenListener { toggleFullScreen() }
        playerView.setVideoAllCallBack(object : GSYSampleCallBack() {
            @SuppressLint("SourceLockedOrientationActivity")
            override fun onPrepared(url: String?, vararg objects: Any?) {
                super.onPrepared(url, *objects)
                playerView.post {
                    val player = playerView.getCurrentPlayer()
                    if (VideoPlay.lockCurScreen &&  !player.getLockCurScreen()) {
                        player.lockTouchLogic()
                    }
                    //根据实际视频比例再次调整
                    val videoWidth = playerView.currentVideoWidth
                    val videoHeight = playerView.currentVideoHeight
                    if (videoWidth > 0 && videoHeight > 0) {
                        val layoutParams = playerView.layoutParams
                        val parentWidth = playerView.width
                        val aspectRatio = videoHeight.toFloat() / videoWidth.toFloat()
                        val isPortraitVideo = if (aspectRatio > 1.2) true else false
                        VideoPlay.isPortraitVideo = isPortraitVideo
                        if (isFullScreen && isPortraitVideo) {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT //提前进入了全屏，并且默认横屏了，纠正回来
                            return@post
                        }
                        if (VideoPlay.startFull && VideoPlay.autoPlay && !isFullScreen) {
                            toggleFullScreen()
                            return@post
                        }
                        val height = (parentWidth * aspectRatio).toInt()
                        val displayMetrics = resources.displayMetrics
                        val screenHeight = displayMetrics.heightPixels
                        //高度不超过一半屏幕
                        layoutParams.height = if (height < screenHeight / 2) height else screenHeight / 2
                        playerView.layoutParams = layoutParams
                    }
                }
            }
        })
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.video_play, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menuCustomBtn = menu.findItem(R.id.menu_custom_btn)?.also {
            it.isVisible = (VideoPlay.source as? BookSource)?.customButton == true
        }
        starMenuItem = menu.findItem(R.id.menu_rss_star)
        upStarMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    private fun upStarMenu() {
        if (VideoPlay.rssStar != null) {
            starMenuItem?.isVisible = true
            starMenuItem?.setIcon(R.drawable.ic_star)
            starMenuItem?.setTitle(R.string.in_favorites)
            starMenuItem?.icon?.setTintMutate(primaryTextColor)
        } else if(VideoPlay.rssRecord != null) {
            starMenuItem?.isVisible = true
            starMenuItem?.setIcon(R.drawable.ic_star_border)
            starMenuItem?.setTitle(R.string.out_favorites)
            starMenuItem?.icon?.setTintMutate(primaryTextColor)
        } else {
            starMenuItem?.isVisible = false
        }
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = !VideoPlay.source?.loginUrl.isNullOrBlank()
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_custom_btn -> {
                (VideoPlay.source as? BookSource)?.let {source ->
                    VideoPlay.book?.let { book ->
                        SourceCallBack.callBackBtn(
                            this,
                            SourceCallBack.CLICK_CUSTOM_BUTTON,
                            source,
                            book,
                            VideoPlay.chapter,
                            BookType.video
                        )
                    }
                }
            }
            R.id.menu_rss_star -> viewModel.addFavorite {
                VideoPlay.rssStar?.let { showDialogFragment(RssFavoritesDialog(it)) }
            }
            R.id.menu_float_window -> startFloatingWindow()
            R.id.menu_config_settings -> showDialogFragment(SettingsDialog(this))
            R.id.menu_login -> VideoPlay.source?.let {s ->
               when (s) {
                    is BookSource -> {
                        startActivity<SourceLoginActivity> {
                            putExtra("bookType", BookType.video)
                        }
                    }
                    is RssSource -> {
                        startActivity<SourceLoginActivity> {
                            putExtra("type", "rssSource")
                            putExtra("key", s.getKey())
                        }
                    }
                }
            }

            R.id.menu_copy_video_url -> {
                val url = VideoPlay.videoUrl
                if (url.isNullOrBlank()){
                    this.toastOnUi("暂无播放地址")
                    return true
                }
                VideoPlay.book?.let {
                    SourceCallBack.callBackBtn(
                        this,
                        SourceCallBack.CLICK_COPY_PLAY_URL,
                        VideoPlay.source as? BookSource,
                        it,
                        VideoPlay.chapter,
                        BookType.video,
                        url
                    ) {
                        sendToClip(url)
                    }
                }
            }
            R.id.menu_open_other_video_player -> {
                val url = VideoPlay.videoUrl
                if (url.isNullOrBlank()){
                    this.toastOnUi("暂无播放地址")
                    return true
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(url.toUri(), "video/*")
                }
                startActivity(intent)
            }
            R.id.menu_edit_source -> VideoPlay.source?.let {s  ->
                when (s) {
                    is BookSource -> bookSourceEditResult.launch {
                        putExtra("sourceUrl", s.getKey())
                    }
                    is RssSource -> rssSourceEditResult.launch {
                        putExtra("sourceUrl", s.getKey())
                    }
                }
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun startFloatingWindow() {
        VideoPlay.savePlayState(playerView)
        // 启动悬浮窗服务
        val intent = Intent(this, VideoPlayService::class.java).apply {
            putExtra("isNew", false)
        }
        ContextCompat.startForegroundService(this, intent)
        playerView.needDestroy = false
        finish() //如果在播放器复刻前活动被销毁，会导致状态继承异常（这里服务创建很快，没发现异常）
    }

    override fun observeLiveBus() {

        observeEventSticky<String>(EventBus.VIDEO_SUB_TITLE) {
            binding.titleBar.title = it
        }

        observeEvent<ArrayList<Int>>(EventBus.UP_VIDEO_INFO) {
            it.forEach { value ->
                when (value) {
                    1 -> upEpisodesView()
                }
            }
        }

        observeEvent<Boolean>(EventBus.VIDEO_CONFIG_CHANGED) {
            updateQuickJumpButtonsState()
            // 更新播放器静音状态
            playerView.setMute(VideoPlay.mutePlay)
        }

    }

    override fun finish() {
        val book = VideoPlay.book ?: return super.finish()
        if (VideoPlay.inBookshelf) {
            callBackBookEnd()
            return super.finish()
        }
        if (!AppConfig.showAddToShelfAlert) {
            callBackBookEnd()
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    VideoPlay.book?.removeType(BookType.notShelf)
                    VideoPlay.book?.save()
                    VideoPlay.inBookshelf = true
                    setResult(RESULT_OK)
                }
                noButton {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            }
        }
    }

    private fun callBackBookEnd() {
        SourceCallBack.callBackBook(SourceCallBack.END_READ, VideoPlay.source as BookSource?, VideoPlay.book, VideoPlay.chapter)
    }

    override fun updateFavorite(title: String?, group: String?) {
        viewModel.updateFavorite(title, group)
    }

    override fun deleteFavorite() {
        viewModel.delFavorite()
    }

    override fun onStart() {
        super.onStart()
        if (initGetter) {
            glideImageGetter.start()
        }
    }

    override fun onStop() {
        super.onStop()
        if (initGetter) {
            glideImageGetter.stop()
        }
    }

    /**
     * 暂停时记录视频阅读时长
     */
    override fun onPause() {
        super.onPause()
        VideoPlay.onPause()
    }

    /**
     * 恢复时标记视频阅读开始
     */
    override fun onResume() {
        super.onResume()
        if (isStartingNew) {
            // 新会话启动时跳过恢复旧视频，新内容加载完成后由 startPlayLogic 自动播放
            isStartingNew = false
            VideoPlay.markReadStart()
            return
        }
        VideoPlay.onResume()
    }

    override fun onDestroy() {
        destroyWeb()
        super.onDestroy()
        if (initGetter) {
            glideImageGetter.clear()
        }
        VideoPlay.saveRead()
        VideoPlay.stopLoading()
        playerView.getCurrentPlayer().release()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun destroyWeb() {
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
    }
}
