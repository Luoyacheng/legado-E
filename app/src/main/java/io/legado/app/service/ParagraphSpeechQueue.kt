package io.legado.app.service

import io.legado.app.constant.AppPattern
import io.legado.app.ui.book.read.paragraph.ParagraphItem

internal data class ParagraphSpeechItem(
    val item: ParagraphItem,
    val textStart: Int,
    val text: String
)

/** Keeps display-item identity and canonical offsets when an engine requires chunks. */
internal object ParagraphSpeechQueue {
    private val silentChunk = Regex("^(\\s|\\p{C}|\\p{P}|\\p{Z})+$")

    /** Limit complete readable display items, rather than an engine's speech chunks. */
    fun buildPrefetch(
        items: List<ParagraphItem>,
        maxInputLength: Int,
        itemLimit: Int = 10
    ): List<ParagraphSpeechItem> {
        require(maxInputLength >= 2)
        require(itemLimit >= 0)
        return items.asSequence()
            .map { build(listOf(it), maxInputLength) }
            .filter { it.isNotEmpty() }
            .take(itemLimit)
            .flatten()
            .toList()
    }

    fun build(items: List<ParagraphItem>, maxInputLength: Int): List<ParagraphSpeechItem> {
        require(maxInputLength >= 2)
        return items.flatMap { item ->
            val text = item.text.toString()
            if (item.isImage || text.isBlank() || text.matches(AppPattern.notReadAloudRegex)) {
                emptyList()
            } else {
                val chunks = arrayListOf<ParagraphSpeechItem>()
                var start = 0
                while (start < text.length) {
                    var end = (start.toLong() + maxInputLength)
                        .coerceAtMost(text.length.toLong()).toInt()
                    if (end < text.length) {
                        if (Character.isHighSurrogate(text[end - 1])) end--
                        val sentenceEnd = (end - 1 downTo start).firstOrNull {
                            text[it] in "。！？.!?；;\n"
                        }
                        if (sentenceEnd != null) end = sentenceEnd + 1
                    }
                    val chunk = text.substring(start, end)
                    if (!chunk.matches(silentChunk)) {
                        chunks.add(ParagraphSpeechItem(item, start, chunk))
                    }
                    start = end
                }
                chunks
            }
        }
    }
}
