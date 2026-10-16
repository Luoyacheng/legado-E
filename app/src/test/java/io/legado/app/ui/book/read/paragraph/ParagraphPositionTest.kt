package io.legado.app.ui.book.read.paragraph

import org.junit.Assert.assertEquals
import org.junit.Test

class ParagraphPositionTest {

    private fun text(id: String, position: Int, value: String = "abc") = ParagraphItem(
        id = id,
        text = value,
        chapterPosition = position,
        positionRanges = listOf(ParagraphPositionRange(0, value.length, position)),
    )

    @Test
    fun emptyChapterHasNoPosition() {
        assertEquals(-1, ParagraphPosition.findItemIndex(emptyList(), 0))
    }

    @Test
    fun restoresWholeParagraphContainingSavedPageStart() {
        val items = listOf(text("first", 0), text("second", 4))
        assertEquals(0, ParagraphPosition.findItemIndex(items, 2))
        assertEquals(1, ParagraphPosition.findItemIndex(items, 5))
    }

    @Test
    fun newlineBelongsToThePreviousParagraph() {
        val items = listOf(text("first", 0), text("second", 4))
        assertEquals(0, ParagraphPosition.findItemIndex(items, 3))
    }

    @Test
    fun restoresImageAndPrefersTextForSharedHtmlPlaceholder() {
        val image = ParagraphItem("image", "", imageSrc = "cover.jpg", chapterPosition = 4)
        assertEquals(1, ParagraphPosition.findItemIndex(listOf(text("before", 0), image), 4))
        assertEquals(2, ParagraphPosition.findItemIndex(listOf(text("before", 0), image, text("after", 4)), 4))
    }

    @Test
    fun handlesDiscontinuousTextRangesAndNonMonotonicImages() {
        val discontinuous = ParagraphItem(
            "html", "abcd", chapterPosition = 10,
            positionRanges = listOf(
                ParagraphPositionRange(0, 2, 10),
                ParagraphPositionRange(2, 4, 20),
            ),
        )
        val image = ParagraphItem("image", "", imageSrc = "inline.jpg", chapterPosition = 0)
        val items = listOf(text("before", 0), discontinuous, image, text("after", 30))
        assertEquals(1, ParagraphPosition.findItemIndex(items, 21))
        assertEquals(1, ParagraphPosition.findItemIndex(items, 25))
        assertEquals(3, ParagraphPosition.findItemIndex(items, 31))
    }

    @Test
    fun boundsStalePositionsWithoutOverflow() {
        val items = listOf(text("first", 10), text("last", 20))
        assertEquals(0, ParagraphPosition.findItemIndex(items, -10))
        assertEquals(1, ParagraphPosition.findItemIndex(items, Int.MAX_VALUE))
        assertEquals(0, ParagraphPosition.findItemIndex(listOf(text("end", Int.MAX_VALUE - 1)), Int.MAX_VALUE))
    }
}
