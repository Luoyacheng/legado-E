package io.legado.app.model.localBook

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.help.book.getExportFileName
import io.legado.app.utils.FileDoc
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.find
import io.legado.app.utils.openOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ag2s.epublib.domain.Author
import me.ag2s.epublib.domain.Date
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.Metadata
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubWriter
import me.ag2s.epublib.util.ResourceUtil
import splitties.init.appCtx
import java.io.File

/**
 * PDF 转 EPUB（文本版）
 *
 * 仅针对“文字版 PDF”（文本可被提取的 PDF）：用 PdfBox 提取文本，重组段落、切分章节后，
 * 复用 epublib 生成可重排的 EPUB。不改动 [PdfFile] 的图片渲染阅读逻辑。
 *
 * 扫描版 PDF（整页图片）提取不到文本，会抛出异常由调用方提示降级。
 */
object PdfToEpub {

    /** 段落结束标点：行尾是这些字符时，倾向认为本段结束 */
    private val paragraphEndChars =
        setOf('。', '！', '？', '”', '’', '』', '」', '）', '.', '!', '?', '…', ')')

    /** 兜底章节标题识别（无 PDF 书签时使用） */
    private val titleRegex = Regex(
        "^\\s*(" +
            "第[0-9零一二三四五六七八九十百千两]+[章节回卷部篇集]" +    // 第N章/节/回...
            "|(PART|Part|CHAPTER|Chapter)\\s*[0-9IVXLCDM]+" +          // PART1 / Chapter 1
            "|(楔子|引子|序章|序言|自序|代序|译序|推荐序|序|前言|后记|尾声|番外|附录|致谢|目录)" +
            ")(\\s|$|[:：、.．\\-—　])"
    )

    /**
     * @param book    待转换的 PDF 书籍
     * @param fileDoc 输出目录
     * @param onProgress (已完成章节数, 总章节数)
     * @return 生成的 epub 文件
     */
    suspend fun convert(
        book: Book,
        fileDoc: FileDoc,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): FileDoc {
        // 幂等、轻量，确保 PdfBox 资源已就绪（不依赖 App 启动时序）
        PDFBoxResourceLoader.init(appCtx)
        val inputStream = LocalBook.getBookInputStream(book)
        val chapters = PDDocument.load(inputStream).use { doc ->
            currentCoroutineContext().ensureActive()
            val list = extractChapters(doc)
            if (list.isEmpty() || list.sumOf { it.paragraphs.size } == 0) {
                throw NoTextException()
            }
            list
        }
        return buildEpub(book, chapters, fileDoc, onProgress)
    }

    private data class Chapter(
        val title: String,
        val paragraphs: List<String>,
        val isVolume: Boolean = false,
        val isSubChapter: Boolean = false
    )

    /** 抽取章节：优先使用 PDF 书签，否则用标题正则兜底，再否则整本单章 */
    private suspend fun extractChapters(doc: PDDocument): List<Chapter> {
        val stripper = PDFTextStripper().apply {
            lineSeparator = "\n"
            paragraphStart = ""
            sortByPosition = true
        }
        val outline = runCatching { doc.documentCatalog?.documentOutline }.getOrNull()
        val bookmarks = outline?.let { flattenOutline(it.firstChild, doc) } ?: emptyList()
        return if (bookmarks.isNotEmpty()) {
            chaptersFromBookmarks(doc, stripper, bookmarks)
        } else {
            chaptersFromFullText(doc, stripper)
        }
    }

    private data class Bookmark(
        val title: String,
        val pageIndex: Int,
        val depth: Int,
        val hasChildren: Boolean
    )

    /** 深度优先扁平化书签，记录页码（0 基）、层级与是否有子节点 */
    private fun flattenOutline(
        first: PDOutlineItem?,
        doc: PDDocument,
        depth: Int = 0
    ): List<Bookmark> {
        val result = ArrayList<Bookmark>()
        var item = first
        while (item != null) {
            val title = item.title?.trim().orEmpty()
            val pageIndex = runCatching {
                item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } ?: -1
            }.getOrDefault(-1)
            val firstChild = item.firstChild
            if (title.isNotEmpty() && pageIndex >= 0) {
                result.add(Bookmark(title, pageIndex, depth, firstChild != null))
            }
            firstChild?.let { result.addAll(flattenOutline(it, doc, depth + 1)) }
            item = item.nextSibling
        }
        return result
    }

    /** 按书签把页范围切成章节；顶层带子节点的书签（如 PART）作为“卷” */
    private suspend fun chaptersFromBookmarks(
        doc: PDDocument,
        stripper: PDFTextStripper,
        bookmarks: List<Bookmark>
    ): List<Chapter> {
        // 按页码排序，保证页范围切分稳定
        val sorted = bookmarks.sortedBy { it.pageIndex }
        val pageCount = doc.numberOfPages
        val chapters = ArrayList<Chapter>(sorted.size)
        for ((i, bm) in sorted.withIndex()) {
            currentCoroutineContext().ensureActive()
            val startPage = bm.pageIndex + 1 // PDFTextStripper 为 1 基
            val nextPage = sorted.getOrNull(i + 1)?.pageIndex ?: pageCount
            val endPage = nextPage.coerceAtLeast(startPage)
            // 跳过“封面”书签：其页范围多为封面/目录页，与导航重复且正文价值低
            if (bm.title == "封面") continue
            stripper.startPage = startPage
            stripper.endPage = endPage
            val raw = runCatching { stripper.getText(doc) }.getOrDefault("")
            val paragraphs = reflowToParagraphs(raw).dropTitleDuplicate(bm.title)
            chapters.add(
                Chapter(
                    bm.title,
                    paragraphs,
                    isVolume = bm.depth == 0 && bm.hasChildren,
                    isSubChapter = bm.depth > 0
                )
            )
        }
        return chapters
    }

    /** 无书签：整本提取后用标题正则切章 */
    private suspend fun chaptersFromFullText(
        doc: PDDocument,
        stripper: PDFTextStripper
    ): List<Chapter> {
        stripper.startPage = 1
        stripper.endPage = doc.numberOfPages
        val raw = stripper.getText(doc)
        currentCoroutineContext().ensureActive()
        val paragraphs = reflowToParagraphs(raw)
        val chapters = ArrayList<Chapter>()
        var curTitle = "正文"
        var curBody = ArrayList<String>()
        for (p in paragraphs) {
            if (isTitleParagraph(p)) {
                if (curBody.isNotEmpty()) {
                    chapters.add(Chapter(curTitle, curBody))
                }
                curTitle = p.trim()
                curBody = ArrayList()
            } else {
                curBody.add(p)
            }
        }
        if (curBody.isNotEmpty()) chapters.add(Chapter(curTitle, curBody))
        // 完全没识别到标题时，整本作为单章
        if (chapters.isEmpty() && paragraphs.isNotEmpty()) {
            chapters.add(Chapter("正文", paragraphs))
        }
        return chapters
    }

    private fun isTitleParagraph(p: String): Boolean {
        val t = p.trim()
        if (t.isEmpty() || t.length > 30) return false
        if (titleRegex.containsMatchIn(t)) return true
        // 短行、不以句末标点结尾、无逗号，疑似独立标题
        return displayWidth(t) <= 24 &&
            t.last() !in paragraphEndChars &&
            '，' !in t && ',' !in t && '。' !in t
    }

    /** 若首段与标题重复（书签标题往往也排印在正文首行），去掉以免重复显示 */
    private fun List<String>.dropTitleDuplicate(title: String): List<String> {
        if (isEmpty()) return this
        val first = first().trim()
        val t = title.trim()
        return if (first == t || (t.isNotEmpty() && first.replace(" ", "") == t.replace(" ", ""))) {
            drop(1)
        } else this
    }

    /**
     * 段落重组：PdfBox 按版面行输出（每行一个 \n），需把同段的多行合并成一段。
     * 启发式：以“满行宽度”为基准，明显短于满行的行视为段落结束。中文行内直接拼接，
     * 跨行的 ASCII 单词之间补空格避免粘连。
     */
    private fun reflowToParagraphs(raw: String): List<String> {
        val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val widths = lines.map { displayWidth(it) }.sorted()
        // 取 90 分位作为满行宽度，抗噪
        val fullWidth = widths[(widths.size * 0.9).toInt().coerceIn(0, widths.size - 1)]
        val threshold = (fullWidth * 0.85)
        val paragraphs = ArrayList<String>()
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.isNotEmpty()) {
                val prev = sb.last()
                val next = line.first()
                if (prev.isAsciiWord() && next.isAsciiWord()) sb.append(' ')
            }
            sb.append(line)
            val w = displayWidth(line)
            val endsWithPunc = line.last() in paragraphEndChars
            // 段落结束：本行明显短于满行；或以句末标点结尾且未占满整行
            if (w < threshold || (endsWithPunc && w < fullWidth)) {
                paragraphs.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) paragraphs.add(sb.toString())
        return paragraphs
    }

    private fun Char.isAsciiWord() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** 显示宽度：CJK/全角按 2，其余按 1 */
    private fun displayWidth(s: String): Int {
        var w = 0
        for (c in s) w += if (c.code >= 0x2E80) 2 else 1
        return w
    }

    private suspend fun buildEpub(
        book: Book,
        chapters: List<Chapter>,
        fileDoc: FileDoc,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): FileDoc {
        val epubBook = EpubBook()
        epubBook.version = "2.0"

        val metadata = Metadata()
        metadata.titles.add(book.name)
        metadata.authors.add(Author(book.getRealAuthor()))
        metadata.language = "zh"
        metadata.dates.add(Date())
        metadata.publishers.add("Legado")
        metadata.descriptions.add(book.getDisplayIntro())
        epubBook.metadata = metadata

        // 封面复用 PdfFile 渲染的首页图（book.coverUrl）
        runCatching {
            book.coverUrl?.let { File(it) }?.takeIf { it.exists() }?.let { cover ->
                epubBook.coverImage = Resource(cover.readBytes(), "Images/cover.jpg")
            }
        }.onFailure { AppLog.put("PDF转EPUB设置封面失败\n${it.localizedMessage}", it) }

        // 复用内置 epub 模板与样式
        epubBook.resources.add(
            Resource(appCtx.assets.open("epub/fonts.css").readBytes(), "Styles/fonts.css")
        )
        epubBook.resources.add(
            Resource(appCtx.assets.open("epub/main.css").readBytes(), "Styles/main.css")
        )
        val contentModel = String(appCtx.assets.open("epub/chapter.html").readBytes())

        val total = chapters.size
        var currentVolume: TOCReference? = null
        chapters.forEachIndexed { index, chapter ->
            currentCoroutineContext().ensureActive()
            // 每段一行，交由 ResourceUtil.formatHtml 包成 <p>
            val body = chapter.paragraphs.joinToString("\n")
            val resource = ResourceUtil.createChapterResource(
                chapter.title,
                body,
                contentModel,
                "Text/chapter_$index.html"
            )
            val volume = currentVolume
            currentVolume = when {
                // 卷（如 PART）作为父节点
                chapter.isVolume -> epubBook.addSection(chapter.title, resource)
                // 卷下的章
                chapter.isSubChapter && volume != null -> {
                    epubBook.addSection(volume, chapter.title, resource)
                    volume
                }
                // 顶层普通章
                else -> {
                    epubBook.addSection(chapter.title, resource)
                    null
                }
            }
            onProgress(index + 1, total)
        }

        val filename = book.getExportFileName("epub")
        fileDoc.find(filename)?.delete()
        val bookDoc = fileDoc.createFileIfNotExist(filename)
        bookDoc.openOutputStream().getOrThrow().buffered().use { os ->
            EpubWriter().write(epubBook, os)
        }
        return bookDoc
    }

    /** 提取不到文本（多为扫描版 PDF） */
    class NoTextException : Exception("PDF 中未提取到文本，可能是扫描版（图片型）PDF")
}
