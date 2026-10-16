package io.legado.app.ui.book.read.paragraph

/** A complete accessibility item, independent of the screen's page boundaries. */
data class ParagraphItem(
    val id: String,
    val text: CharSequence,
    val imageSrc: String? = null,
    val imageClick: String? = null,
    val chapterPosition: Int,
    val progressPosition: Int = chapterPosition,
    val isTitle: Boolean = false,
    val positionRanges: List<ParagraphPositionRange> = emptyList(),
) {
    val isImage: Boolean get() = imageSrc != null

    /** Maps a UTF-16 text offset back to the existing page/TTS chapter coordinate. */
    fun canonicalPositionAt(textOffset: Int): Int {
        if (positionRanges.isEmpty()) return chapterPosition
        val offset = textOffset.coerceIn(0, text.length)
        val range = positionRanges.firstOrNull { offset >= it.textStart && offset < it.textEnd }
            ?: if (offset >= positionRanges.last().textEnd) positionRanges.last()
            else positionRanges.firstOrNull { it.textStart >= offset } ?: positionRanges.first()
        return range.chapterStart + (offset - range.textStart).coerceIn(0, range.textEnd - range.textStart)
    }
}

/** Half-open UTF-16 text range; HTML images can make chapter ranges discontinuous. */
data class ParagraphPositionRange(
    val textStart: Int,
    val textEnd: Int,
    val chapterStart: Int,
)
