package io.legado.app.ui.book.read.paragraph

/**
 * Side-channel collector for the existing chapter layout. It never changes page text or positions.
 * Normal blocks arrive in reading order. HTML blocks declare their source order before their
 * existing layout records positions, because block images can be emitted before preceding text.
 */
class ParagraphItems {
    private data class Draft(
        val id: String,
        val text: CharSequence,
        val imageSrc: String?,
        var imageClick: String?,
        var chapterPosition: Int?,
        val isTitle: Boolean,
        val sourceStart: Int? = null,
        val sourceEnd: Int? = null,
        val correctImageProgress: Boolean = false,
        val ranges: MutableList<ParagraphPositionRange> = arrayListOf(),
    )

    private val drafts = arrayListOf<Draft>()
    private val blockDrafts = arrayListOf<Draft>()
    private var blockId = ""
    private var isTitle = false
    private var sequence = 0
    private var sourceDraftCursor = 0
    private val text = StringBuilder()
    private val ranges = arrayListOf<ParagraphPositionRange>()

    fun beginBlock(id: String, title: Boolean = false) {
        flushText()
        blockDrafts.clear()
        blockId = id
        isTitle = title
        sequence = 0
        sourceDraftCursor = 0
    }

    fun appendText(value: String, chapterStart: Int) {
        if (value.isEmpty()) return
        val textStart = text.length
        text.append(value)
        addRange(ranges, ParagraphPositionRange(textStart, text.length, chapterStart))
    }

    fun appendImage(src: String, click: String?, chapterPosition: Int) {
        flushText()
        drafts.add(Draft(nextId("image"), "", src, click, chapterPosition, isTitle))
    }

    fun addSourceText(sourceStart: Int, sourceEnd: Int, value: CharSequence) {
        if (value.isBlank()) return
        val draft = Draft(
            nextId("text"), value, null, null, null, isTitle,
            sourceStart = sourceStart, sourceEnd = sourceEnd,
        )
        drafts.add(draft)
        blockDrafts.add(draft)
    }

    fun addSourceImage(sourceStart: Int, sourceEnd: Int, src: String) {
        val draft = Draft(
            nextId("image"), "", src, null, null, isTitle,
            sourceStart = sourceStart, sourceEnd = sourceEnd, correctImageProgress = true,
        )
        drafts.add(draft)
        blockDrafts.add(draft)
    }

    fun mapSourceLine(sourceStart: Int, sourceEnd: Int, chapterStart: Int) {
        while (sourceDraftCursor < blockDrafts.size
            && blockDrafts[sourceDraftCursor].sourceEnd!! <= sourceStart
        ) sourceDraftCursor++
        for (index in sourceDraftCursor until blockDrafts.size) {
            val draft = blockDrafts[index]
            if (draft.sourceStart!! >= sourceEnd) break
            val start = maxOf(sourceStart, draft.sourceStart)
            val end = minOf(sourceEnd, draft.sourceEnd!!)
            if (start >= end) continue
            val canonicalStart = chapterStart + start - sourceStart
            if (draft.imageSrc != null) {
                // Used for inline images, and as a fallback if a block image could not be laid out.
                if (draft.chapterPosition == null) draft.chapterPosition = canonicalStart
            } else {
                if (draft.chapterPosition == null) draft.chapterPosition = canonicalStart
                addRange(
                    draft.ranges,
                    ParagraphPositionRange(start - draft.sourceStart, end - draft.sourceStart, canonicalStart),
                )
            }
        }
    }

    fun mapSourceImage(sourcePosition: Int, chapterPosition: Int, click: String?) {
        blockDrafts.firstOrNull {
            it.imageSrc != null && sourcePosition >= it.sourceStart!! && sourcePosition < it.sourceEnd!!
        }?.let {
            it.chapterPosition = chapterPosition
            it.imageClick = click
        }
    }

    fun build(chapterEnd: Int): List<ParagraphItem> {
        flushText()
        val items = drafts.map { draft ->
            ParagraphItem(
                id = draft.id,
                text = draft.text,
                imageSrc = draft.imageSrc,
                imageClick = draft.imageClick,
                chapterPosition = draft.chapterPosition ?: chapterEnd,
                isTitle = draft.isTitle,
                positionRanges = draft.ranges.toList(),
            )
        }
        val result = items.toMutableList()
        var nextTextPosition = chapterEnd
        for (index in items.indices.reversed()) {
            val item = items[index]
            if (!item.isImage) {
                nextTextPosition = item.chapterPosition
            } else if (drafts[index].correctImageProgress) {
                result[index] = item.copy(progressPosition = nextTextPosition)
            }
        }
        return result
    }

    private fun flushText() {
        if (text.isNotBlank()) {
            drafts.add(
                Draft(
                    nextId("text"), text.toString(), null, null,
                    ranges.firstOrNull()?.chapterStart, isTitle, ranges = ArrayList(ranges),
                ),
            )
        }
        text.setLength(0)
        ranges.clear()
    }

    private fun nextId(type: String): String = "$blockId:$type:${sequence++}"

    private fun addRange(target: MutableList<ParagraphPositionRange>, value: ParagraphPositionRange) {
        val last = target.lastOrNull()
        if (last != null && last.textEnd == value.textStart
            && last.chapterStart + last.textEnd - last.textStart == value.chapterStart
        ) {
            target[target.lastIndex] = last.copy(textEnd = value.textEnd)
        } else {
            target.add(value)
        }
    }
}
