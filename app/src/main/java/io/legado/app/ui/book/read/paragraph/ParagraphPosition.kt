package io.legado.app.ui.book.read.paragraph

import io.legado.app.ui.book.read.page.entities.TextChapter

/** Maps the existing chapter/TTS coordinate to complete accessibility items. */
object ParagraphPosition {

    fun findItemIndex(items: List<ParagraphItem>, legacyPosition: Int): Int {
        if (items.isEmpty()) return -1
        val position = legacyPosition.coerceAtLeast(0)

        // HTML images may share a legacy coordinate with text. Keep text restoration
        // compatible with the page renderer and resolve an exact image only afterwards.
        items.forEachIndexed { index, item ->
            if (item.isImage) return@forEachIndexed
            if (item.positionRanges.any { range ->
                    position >= range.chapterStart &&
                        position.toLong() < range.chapterStart.toLong() + range.textEnd - range.textStart
                } || item.positionRanges.isEmpty() &&
                position >= item.chapterPosition &&
                position.toLong() < item.chapterPosition.toLong() + item.text.length
            ) {
                return index
            }
        }
        items.indexOfFirst { it.isImage && it.chapterPosition == position }
            .takeIf { it >= 0 }?.let { return it }

        // A saved page position can be on a paragraph's newline, beyond a changed
        // chapter's end, or between discontinuous HTML text ranges. Chapter positions
        // are not necessarily ordered in display order, so inspect every range.
        var precedingIndex = 0
        var precedingPosition = Long.MIN_VALUE
        items.forEachIndexed { index, item ->
            val starts = if (item.positionRanges.isEmpty()) {
                listOf(item.chapterPosition)
            } else {
                item.positionRanges.map { it.chapterStart }
            }
            starts.forEach { start ->
                if (start <= position && start.toLong() > precedingPosition) {
                    precedingPosition = start.toLong()
                    precedingIndex = index
                }
            }
        }
        return precedingIndex
    }

    /** Keeps the original page-based percentage, including its rounding behavior. */
    fun readProgress(textChapter: TextChapter, item: ParagraphItem): String {
        return textChapter.getPageByReadPos(item.progressPosition)?.readProgress ?: "0.0%"
    }
}
