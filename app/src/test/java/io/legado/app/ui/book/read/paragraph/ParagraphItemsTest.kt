package io.legado.app.ui.book.read.paragraph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphItemsTest {
    @Test
    fun paragraphSpanningPagesRemainsOneItemWithStableId() {
        val whole = "GPT是世界上最优秀的大模型。"
        val split = ParagraphItems().apply {
            beginBlock("body:0")
            appendText(whole.substring(0, 6), 20)
            appendText(whole.substring(6), 26)
        }.build(20 + whole.length)
        val unsplit = ParagraphItems().apply {
            beginBlock("body:0")
            appendText(whole, 20)
        }.build(20 + whole.length)

        assertEquals(1, split.size)
        assertEquals(whole, split.single().text)
        assertEquals(unsplit.single(), split.single())
        assertEquals(26, split.single().canonicalPositionAt(6))
    }

    @Test
    fun mixedAndConsecutiveImagesBecomeSeparateItems() {
        val items = ParagraphItems().apply {
            beginBlock("body:0")
            appendText("图前", 0)
            appendImage("first.png", "first-link", 2)
            appendImage("second.png", null, 3)
            appendText("图后", 4)
        }.build(6)

        assertEquals(listOf("图前", "", "", "图后"), items.map { it.text.toString() })
        assertEquals(listOf(false, true, true, false), items.map { it.isImage })
        assertEquals("first-link", items[1].imageClick)
        assertEquals(listOf(0, 2, 3, 4), items.map { it.chapterPosition })
        assertEquals(items.size, items.map { it.id }.distinct().size)
    }

    @Test
    fun htmlKeepsSourceOrderWhenOldLayoutEmitsBlockImageFirst() {
        val items = ParagraphItems().apply {
            beginBlock("body:1")
            addSourceText(0, 1, "A")
            addSourceImage(1, 2, "image.png")
            addSourceText(2, 3, "B")
            mapSourceImage(1, 0, "image-link")
            mapSourceLine(0, 3, 1)
        }.build(4)

        assertEquals(listOf("A", "", "B"), items.map { it.text.toString() })
        assertEquals(listOf(1, 0, 3), items.map { it.chapterPosition })
        assertEquals(listOf(1, 3, 3), items.map { it.progressPosition })
        assertEquals("image-link", items[1].imageClick)
        assertEquals(3, items[2].canonicalPositionAt(0))
    }

    @Test
    fun htmlTextMapsDiscontinuousPageCoordinatesWithoutSplittingItem() {
        val items = ParagraphItems().apply {
            beginBlock("body:0")
            addSourceText(0, 4, "ABCD")
            mapSourceLine(0, 2, 5)
            mapSourceLine(2, 4, 9)
        }.build(11)
        val item = items.single()

        assertEquals("ABCD", item.text)
        assertEquals(2, item.positionRanges.size)
        assertEquals(5, item.canonicalPositionAt(-1))
        assertEquals(6, item.canonicalPositionAt(1))
        assertEquals(9, item.canonicalPositionAt(2))
        assertEquals(11, item.canonicalPositionAt(100))
    }

    @Test
    fun trailingHtmlImagesUseChapterEndForProgress() {
        val items = ParagraphItems().apply {
            beginBlock("body:0")
            addSourceText(0, 1, "A")
            addSourceImage(1, 2, "first.png")
            addSourceImage(2, 3, "second.png")
            mapSourceImage(1, 0, null)
            mapSourceImage(2, 1, null)
            mapSourceLine(0, 3, 2)
        }.build(5)

        assertEquals(listOf(2, 5, 5), items.map { it.progressPosition })
    }

    @Test
    fun emptyParagraphsAreSkippedAndTitlesRemainSeparate() {
        val items = ParagraphItems().apply {
            beginBlock("title:0", title = true)
            appendText("章节标题", 0)
            beginBlock("body:0")
            appendText("　 \t", 5)
            beginBlock("body:1")
            appendText("正文", 9)
        }.build(11)

        assertEquals(2, items.size)
        assertTrue(items[0].isTitle)
        assertFalse(items[1].isTitle)
        assertEquals("body:1:text:0", items[1].id)
    }

    @Test
    fun emojiOffsetsUseUtf16Coordinates() {
        val item = ParagraphItems().apply {
            beginBlock("body:0")
            appendText("A", 0)
            appendText("😀", 1)
            appendText("B", 3)
        }.build(4).single()

        assertEquals(4, item.text.length)
        assertEquals(3, item.canonicalPositionAt(3))
    }
}
