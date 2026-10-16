package io.legado.app.service

import io.legado.app.ui.book.read.paragraph.ParagraphItem
import io.legado.app.ui.book.read.paragraph.ParagraphPositionRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphSpeechQueueTest {
    private fun text(id: String, text: String, position: Int) = ParagraphItem(
        id = id, text = text, chapterPosition = position,
        positionRanges = listOf(ParagraphPositionRange(0, text.length, position))
    )

    @Test
    fun mixedContentRetainsItemOrderAndCanonicalOffsets() {
        val before = text("before", "图前文字", 100)
        val image = ParagraphItem("image", "", "image.png", chapterPosition = 104)
        val after = text("after", "图后文字", 117)
        val queue = ParagraphSpeechQueue.build(listOf(before, image, after), Int.MAX_VALUE)
        assertEquals(listOf("before", "after"), queue.map { it.item.id })
        assertEquals(listOf(100, 117), queue.map { it.item.canonicalPositionAt(it.textStart) })
    }

    @Test
    fun chunksPreferSentenceEndAndRetainOneDisplayItem() {
        val item = text("long", "第一句。第二句很长很长很长。", 50)
        val queue = ParagraphSpeechQueue.build(listOf(item), 7)
        assertEquals("第一句。", queue.first().text)
        assertEquals(item.text.toString(), queue.joinToString("") { it.text })
        assertTrue(queue.all { it.text.length <= 7 && it.item.id == "long" })
        assertEquals(listOf(50, 54, 61), queue.map { it.item.canonicalPositionAt(it.textStart) })
    }

    @Test
    fun chunksNeverSplitSurrogatePairs() {
        val item = text("emoji", "甲😀乙😀丙", 0)
        val queue = ParagraphSpeechQueue.build(listOf(item), 2)
        assertEquals(item.text.toString(), queue.joinToString("") { it.text })
        assertTrue(queue.all { it.text.length <= 2 })
        assertFalse(queue.any { Character.isHighSurrogate(it.text.last()) })
        assertFalse(queue.any { Character.isLowSurrogate(it.text.first()) })
    }

    @Test
    fun imagesAndPunctuationOnlyChunksDoNotLeaveAnUnspokenTail() {
        val item = text("tail", "正文。。。。。。。。", 0)
        val queue = ParagraphSpeechQueue.build(listOf(
            text("blank", "  ", 0), text("symbols", "！！", 2), item
        ), 4)
        assertEquals(listOf("tail"), queue.map { it.item.id })
        assertEquals("正文。。", queue.single().text)
    }

    @Test
    fun prefetchCountsReadableItemsAndKeepsTitleAndMixedContentInPlaybackOrder() {
        val title = text("title", "下一章标题", 0).copy(isTitle = true)
        val before = text("before", "图前文字", 7)
        val after = text("after", "图后文字", 29)
        val items = listOf(
            title,
            text("blank", "  ", 5),
            text("punctuation", "……！！！", 5),
            before,
            ParagraphItem("image", "图片说明", "image.png", chapterPosition = 11),
            after,
            text("excluded", "不在预下载范围内", 33)
        )

        val queue = ParagraphSpeechQueue.buildPrefetch(items, 4, itemLimit = 3)

        assertEquals(listOf("title", "before", "after"), queue.map { it.item.id }.distinct())
        assertEquals(
            ParagraphSpeechQueue.build(listOf(title, before, after), 4),
            queue
        )
        assertEquals("图后文字", queue.last().text)
        assertEquals(29, queue.last().item.canonicalPositionAt(queue.last().textStart))
    }

    @Test
    fun prefetchRetainsAllChunksOfTenthItemWithoutDownloadingEleventhItem() {
        val firstNine = (1..9).map { text("item$it", "第${it}段正文", it * 10) }
        val tenth = text("long", "长段正文".repeat(15), 100)
        val eleventh = text("eleventh", "下一段正文", 200)

        val queue = ParagraphSpeechQueue.buildPrefetch(firstNine + tenth + eleventh, 4)

        assertEquals(10, queue.map { it.item.id }.distinct().size)
        assertFalse(queue.any { it.item.id == "eleventh" })
        val longChunks = queue.filter { it.item.id == "long" }
        assertTrue(longChunks.size > 10)
        assertEquals(tenth.text.toString(), longChunks.joinToString("") { it.text })
        assertEquals(
            ParagraphSpeechQueue.build(firstNine + tenth, 4),
            queue
        )
    }

    @Test
    fun prefetchShortChapterMatchesPlaybackIncludingSentenceAndSurrogateBoundaries() {
        val items = listOf(
            text("title", "短章标题", 0).copy(isTitle = true),
            text("body", "第一句。甲😀乙😀丙。第二句正文。", 10),
            text("silent", "。。。", 30),
            ParagraphItem("image", "", "image.png", chapterPosition = 33)
        )

        assertEquals(
            ParagraphSpeechQueue.build(items, 7),
            ParagraphSpeechQueue.buildPrefetch(items, 7)
        )
    }
}
