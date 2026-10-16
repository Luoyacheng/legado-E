package io.legado.app.ui.book.read.paragraph

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.dpToPx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A native list: accessibility services control focus and ordinary list scrolling. */
class ParagraphReadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    /** The same screen can be restored even after the user scrolls while speech is paused. */
    data class Viewport(
        val bookUrl: String,
        val chapterUrl: String,
        val topItemId: String,
        val offsetPx: Int
    )

    var onMenu: (() -> Unit)? = null
    var onImage: ((ParagraphItem) -> Unit)? = null
    var onPreviousChapter: (() -> Unit)? = null
    var onNextChapter: (() -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null
    var onTopItemChanged: ((ParagraphItem) -> Unit)? = null
    var onUserScroll: (() -> Unit)? = null

    private val layoutManager = LinearLayoutManager(context)
    private val list = RecyclerView(context)
    private val message = TextView(context)
    private val progress = TextView(context)
    private val controls = LinearLayout(context)
    private val previous = AppCompatButton(context)
    private val playPause = AppCompatButton(context)
    private val next = AppCompatButton(context)
    private val itemAdapter = ItemAdapter()
    private var items: List<ParagraphItem> = emptyList()
    private var chapter: TextChapter? = null
    private var programmaticScroll = false
    private var scrollGeneration = 0L
    private var speechFollowGeneration = 0L
    private var pendingViewport: Viewport? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusInset = 0
    private var navigationInset = 0
    private var lastItemHeight = 0

    init {
        orientation = VERTICAL
        list.layoutManager = layoutManager
        list.adapter = itemAdapter
        list.itemAnimator = null
        list.clipToPadding = false
        list.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        list.setAccessibilityDelegateCompat(object : RecyclerViewAccessibilityDelegate(list) {
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
                    || action == AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD
                    || action == AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_UP.id
                    || action == AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_DOWN.id
                ) {
                    speechFollowGeneration++
                    programmaticScroll = false
                    onUserScroll?.invoke()
                }
                return super.performAccessibilityAction(host, action, args)
            }
        })
        // A non-accessible trailing space lets even a short final paragraph reach the top.
        list.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                if (parent.getChildAdapterPosition(view) == items.lastIndex) {
                    outRect.bottom = (parent.height - parent.paddingTop - parent.paddingBottom - lastItemHeight)
                        .coerceAtLeast(0)
                }
            }
        })
        list.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) list.post { list.invalidateItemDecorations() }
        }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        message.gravity = Gravity.CENTER
        message.isVisible = false
        message.setOnClickListener { onMenu?.invoke() }
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        progress.gravity = Gravity.CENTER
        progress.setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
        // Progress remains discoverable without interrupting speech on every update.
        progress.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        controls.orientation = HORIZONTAL
        previous.setText(R.string.previous_chapter)
        next.setText(R.string.next_chapter)
        previous.setOnClickListener { onPreviousChapter?.invoke() }
        playPause.setOnClickListener { onPlayPause?.invoke() }
        next.setOnClickListener { onNextChapter?.invoke() }
        listOf(previous, playPause, next).forEach {
            it.minimumHeight = 48.dpToPx()
            it.isAllCaps = false
            controls.addView(it, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && !programmaticScroll) {
                    // Layout changes (for example an image finishing loading) also call
                    // onScrolled while idle; only an active gesture cancels speech follow.
                    if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                        speechFollowGeneration++
                    }
                    onUserScroll?.invoke()
                }
                updateProgress()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    speechFollowGeneration++
                    programmaticScroll = false
                    onUserScroll?.invoke()
                }
                if (newState == RecyclerView.SCROLL_STATE_IDLE) reportTopItem()
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            statusInset = if (ReadBookConfig.hideStatusBar) cutout.top else bars.top
            navigationInset = if (ReadBookConfig.hideNavigationBar) cutout.bottom else bars.bottom
            setPadding(cutout.left, statusInset, cutout.right, navigationInset)
            insets
        }
        upStyle()
    }

    val topItem: ParagraphItem?
        get() = items.getOrNull(layoutManager.findFirstVisibleItemPosition())

    fun isShowing(textChapter: TextChapter): Boolean = chapter === textChapter && list.isVisible

    fun showMessage(text: CharSequence) {
        invalidatePendingScroll()
        chapter = null
        items = emptyList()
        itemAdapter.notifyDataSetChanged()
        list.isVisible = false
        message.isVisible = true
        message.text = text
        progress.text = ""
        updateControls()
    }

    fun setChapter(textChapter: TextChapter, itemId: String?, position: Int) {
        invalidatePendingScroll()
        chapter = textChapter
        items = textChapter.paragraphItems
        lastItemHeight = 0
        list.isVisible = items.isNotEmpty()
        message.isVisible = items.isEmpty()
        message.text = textChapter.title
        itemAdapter.notifyDataSetChanged()
        list.post { list.invalidateItemDecorations() }
        val exact = items.indexOfFirst { it.id == itemId }
        val index = if (exact >= 0) exact else findPosition(position)
        scrollToItem(index)
        updateControls()
    }

    private fun findPosition(position: Int): Int {
        return ParagraphPosition.findItemIndex(items, position)
    }

    fun scrollToPosition(position: Int) = scrollToItem(findPosition(position))

    fun followSpeech(itemId: String) {
        val shownChapter = chapter ?: return
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0 || !isShown || !list.isVisible) return
        val request = ++speechFollowGeneration
        if (!programmaticScroll && !list.isLayoutRequested
            && !list.hasPendingAdapterUpdates() && list.childCount > 0
        ) {
            followSpeechInLaidOutList(index)
        } else {
            // Posting also handles the window after a layout but before its scroll-completion
            // callback; waiting only for another layout could lose the speech request forever.
            list.post { followSpeechAfterLayout(shownChapter, itemId, request) }
        }
    }

    private fun followSpeechAfterLayout(
        shownChapter: TextChapter,
        itemId: String,
        request: Long
    ) {
        if (chapter !== shownChapter || !isShown || !list.isVisible
            || speechFollowGeneration != request
        ) return
        if (list.isLayoutRequested || list.hasPendingAdapterUpdates() || list.childCount == 0) {
            list.doOnNextLayout {
                list.post { followSpeechAfterLayout(shownChapter, itemId, request) }
            }
            if (list.childCount == 0 && !list.isLayoutRequested) list.requestLayout()
            return
        }
        val index = items.indexOfFirst { it.id == itemId }
        if (index >= 0) followSpeechInLaidOutList(index)
    }

    private fun followSpeechInLaidOutList(index: Int) {
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        val target = layoutManager.findViewByPosition(index)
        val content = (target as? ViewGroup)?.let { holder ->
            (0 until holder.childCount).map(holder::getChildAt).firstOrNull { it.isVisible }
        }
        val contentTop = target?.let { it.top + (content?.top ?: 0) }
        val contentBottom = target?.let { it.top + (content?.bottom ?: it.height) }
        val visible = first >= 0 && last >= first && index in first..last
            && target != null
            && contentBottom != null && contentBottom > 0
            && contentTop != null && contentTop < list.height
        if (!visible) scrollToItem(index)
    }

    fun captureViewport(): Viewport? {
        val shownChapter = chapter ?: return null
        if (!list.isVisible || items.isEmpty()
            || ReadBook.book?.bookUrl != shownChapter.chapter.bookUrl
        ) return null
        if (programmaticScroll) return pendingViewport
        val first = layoutManager.findFirstVisibleItemPosition()
        val item = items.getOrNull(first) ?: return null
        val itemView = layoutManager.findViewByPosition(first) ?: return null
        return Viewport(shownChapter.chapter.bookUrl, shownChapter.chapter.url,
            item.id, itemView.top - list.paddingTop)
    }

    fun restoreViewport(viewport: Viewport): Boolean {
        val shownChapter = chapter ?: return false
        if (!list.isVisible || ReadBook.book?.bookUrl != viewport.bookUrl
            || shownChapter.chapter.bookUrl != viewport.bookUrl
            || shownChapter.chapter.url != viewport.chapterUrl
        ) return false
        val index = items.indexOfFirst { it.id == viewport.topItemId }
        if (index < 0) return false
        scrollToItem(index, viewport.offsetPx)
        return true
    }

    private fun invalidatePendingScroll() {
        scrollGeneration++
        speechFollowGeneration++
        programmaticScroll = false
        pendingViewport = null
    }

    private fun scrollToItem(index: Int, offsetPx: Int = 0) {
        if (items.isEmpty()) return
        speechFollowGeneration++
        val shownChapter = chapter ?: return
        val boundedIndex = index.coerceIn(items.indices)
        val generation = ++scrollGeneration
        programmaticScroll = true
        pendingViewport = Viewport(shownChapter.chapter.bookUrl, shownChapter.chapter.url,
            items[boundedIndex].id, offsetPx)
        list.doOnNextLayout {
            list.post {
                if (scrollGeneration != generation || chapter !== shownChapter) return@post
                programmaticScroll = false
                pendingViewport = null
                reportTopItem()
            }
        }
        list.stopScroll()
        layoutManager.scrollToPositionWithOffset(boundedIndex, offsetPx)
    }

    fun scrollScreen(forward: Boolean) {
        speechFollowGeneration++
        programmaticScroll = false
        onUserScroll?.invoke()
        list.smoothScrollBy(0, (list.height - list.paddingTop).coerceAtLeast(1) * if (forward) 1 else -1)
    }

    fun reportTopItem() {
        // Pending positioning still exposes the previous layout's visible indices.
        if (programmaticScroll) return
        topItem?.let { onTopItemChanged?.invoke(it) }
        updateProgress()
    }

    fun updateControls() {
        controls.isVisible = AppConfig.paragraphReadControls
        progress.isVisible = AppConfig.paragraphReadProgress
        previous.isEnabled = ReadBook.durChapterIndex > 0
        next.isEnabled = ReadBook.durChapterIndex < ReadBook.simulatedChapterSize - 1
        playPause.setText(if (BaseReadAloudService.isPlay()) R.string.pause else R.string.paragraph_play)
        playPause.contentDescription = playPause.text
        updateProgress()
    }

    private fun updateProgress() {
        val item = topItem ?: return
        progress.text = chapter?.let { ParagraphPosition.readProgress(it, item) }.orEmpty()
    }

    fun upStyle() {
        background = ReadBookConfig.bg?.constantState?.newDrawable()?.mutate()
        message.setTextColor(ReadBookConfig.textColor)
        progress.setTextColor(ReadBookConfig.textColor)
        list.setPadding(
            ReadBookConfig.paddingLeft.dpToPx(),
            ReadBookConfig.paddingTop.dpToPx(),
            ReadBookConfig.paddingRight.dpToPx(),
            ReadBookConfig.paddingBottom.dpToPx()
        )
        itemAdapter.notifyDataSetChanged()
        updateControls()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
    }

    private inner class ItemHolder(val container: LinearLayout) : RecyclerView.ViewHolder(container) {
        val text = TextView(context)
        val image = ImageView(context)
        var imageJob: Job? = null
        var bitmap: Bitmap? = null
        var boundId: String? = null

        init {
            container.orientation = VERTICAL
            container.addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            image.adjustViewBounds = true
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            container.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            // Each item exposes exactly one accessible text or image node.
            container.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            text.setOnClickListener { onMenu?.invoke() }
            ViewCompat.replaceAccessibilityAction(text,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                context.getString(R.string.paragraph_read_menu)) { _, _ ->
                onMenu?.invoke()
                true
            }
            ViewCompat.addAccessibilityAction(image, context.getString(R.string.paragraph_read_menu)) { _, _ ->
                onMenu?.invoke()
                true
            }
            container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (bindingAdapterPosition == items.lastIndex && container.height != lastItemHeight) {
                    lastItemHeight = container.height
                    list.post { list.invalidateItemDecorations() }
                }
            }
        }

        fun releaseImage() {
            imageJob?.cancel()
            imageJob = null
            image.setImageDrawable(null)
            bitmap?.recycle()
            bitmap = null
        }
    }

    private inner class ItemAdapter : RecyclerView.Adapter<ItemHolder>() {
        init { setHasStableIds(true) }

        override fun getItemId(position: Int): Long {
            var hash = -3750763034362895579L
            for (char in "${chapter?.chapter?.url}:${items[position].id}") {
                hash = (hash xor char.code.toLong()) * 1099511628211L
            }
            return hash
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemHolder {
            val container = LinearLayout(context)
            container.layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            return ItemHolder(container)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ItemHolder, position: Int) {
            val item = items[position]
            holder.boundId = item.id
            holder.releaseImage()
            holder.text.isVisible = !item.isImage
            holder.image.isVisible = item.isImage
            holder.container.setPadding(0, 0, 0,
                (ChapterProvider.contentPaintTextHeight * ReadBookConfig.paragraphSpacing / 10f).toInt())
            if (!item.isImage) {
                holder.text.text = item.text
                holder.text.setTextColor(ReadBookConfig.textColor)
                holder.text.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    if (item.isTitle) ChapterProvider.titlePaint.textSize else ChapterProvider.contentPaint.textSize)
                holder.text.typeface = if (item.isTitle) ChapterProvider.titlePaint.typeface else ChapterProvider.contentPaint.typeface
                holder.text.setLineSpacing(0f, ReadBookConfig.lineSpacingExtra / 10f)
                holder.text.letterSpacing = ReadBookConfig.letterSpacing
                holder.text.gravity = if (item.isTitle && ReadBookConfig.isMiddleTitle) Gravity.CENTER else Gravity.START
                ViewCompat.setAccessibilityHeading(holder.text, item.isTitle)
            } else {
                val imageNumber = items.take(position + 1).count { it.isImage }
                holder.image.contentDescription = context.getString(R.string.paragraph_image, imageNumber)
                holder.image.minimumHeight = 64.dpToPx()
                holder.image.setOnClickListener { onImage?.invoke(item) }
                val book = ReadBook.book ?: return
                val src = item.imageSrc ?: return
                holder.imageJob = scope.launch {
                    var decoded: Bitmap? = null
                    try {
                        withContext(Dispatchers.IO) {
                            decoded = runCatching {
                                ImageProvider.cacheImage(book, src, ReadBook.bookSource)
                                ImageProvider.getImage(book, src, list.width.coerceAtLeast(1))
                                    .copy(Bitmap.Config.ARGB_8888, false)
                            }.getOrNull()
                        }
                        if (holder.boundId == item.id) {
                            holder.bitmap = decoded
                            holder.image.setImageBitmap(decoded)
                            decoded = null
                        }
                    } finally {
                        decoded?.recycle()
                    }
                }
            }
        }

        override fun onViewRecycled(holder: ItemHolder) {
            holder.boundId = null
            holder.releaseImage()
        }
    }
}
