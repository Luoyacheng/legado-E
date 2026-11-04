package io.legado.app.utils

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.PictureDrawable
import android.util.Size
import java.io.FileInputStream
import java.io.InputStream
import com.caverock.androidsvg.SVG
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MAX_SVG_BITMAP_DIMENSION = 4096
private const val MAX_SVG_BITMAP_PIXELS = 4_194_304L

@Suppress("WeakerAccess", "MemberVisibilityCanBePrivate")
object SvgUtils {

    /**
     * 从Svg中解码bitmap
     */
    
    fun createBitmap(filePath: String, width: Int, height: Int? = null): Bitmap? {
        return kotlin.runCatching {
            val inputStream = FileInputStream(filePath)
            createBitmap(inputStream, width, height)
        }.getOrNull()
    }

    fun createBitmap(inputStream: InputStream, width: Int, height: Int? = null): Bitmap? {
        return kotlin.runCatching {
            val svg = SVG.getFromInputStream(inputStream)
            createBitmap(svg, width, height)
        }.getOrNull()
    }

    fun createDrawable(inputStream: InputStream): Pair<PictureDrawable, Size>? {
        return kotlin.runCatching {
            val svg = SVG.getFromInputStream(inputStream)
            val size = getSize(svg)
            val picture = svg.renderToPicture()
            Pair(PictureDrawable(picture), size)
        }.getOrNull()
    }

    //获取svg图片大小
    fun getSize(filePath: String): Size? {
        return kotlin.runCatching {
            val inputStream = FileInputStream(filePath)
            getSize(inputStream)
        }.getOrNull()
    }

    fun getSize(inputStream: InputStream): Size? {
        return kotlin.runCatching {
            val svg = SVG.getFromInputStream(inputStream)
            getSize(svg)
        }.getOrNull()
    }

    /////// private method
    private fun createBitmap(svg: SVG, width: Int? = null, height: Int? = null): Bitmap {
        val size = getSize(svg)

        val viewBox: RectF? = svg.documentViewBox
        if (viewBox == null && size.width > 0 && size.height > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }

        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")

        val (bitmapWidth, bitmapHeight) = calculateSvgBitmapSize(
            size.width,
            size.height,
            width,
            height,
        )
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)

        svg.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    private fun calculateSvgBitmapSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
    ): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(targetWidth == null || targetWidth > 0)
        require(targetHeight == null || targetHeight > 0)
        val widthScale = targetWidth?.let { it.toFloat() / sourceWidth }
        val heightScale = targetHeight?.let { it.toFloat() / sourceHeight }
        val requestedScale = when {
            widthScale != null && heightScale != null -> min(widthScale, heightScale)
            widthScale != null -> widthScale
            heightScale != null -> heightScale
            else -> 1f
        }
        val dimensionScale = min(
            MAX_SVG_BITMAP_DIMENSION.toFloat() / sourceWidth,
            MAX_SVG_BITMAP_DIMENSION.toFloat() / sourceHeight,
        )
        val pixelScale = sqrt(
            MAX_SVG_BITMAP_PIXELS.toDouble() / (sourceWidth.toLong() * sourceHeight).toDouble()
        ).toFloat()
        val scale = min(requestedScale, min(dimensionScale, pixelScale))
        return Pair(
            (sourceWidth * scale).roundToInt().coerceAtLeast(1),
            (sourceHeight * scale).roundToInt().coerceAtLeast(1),
        )
    }

    private fun getSize(svg: SVG): Size {
        val width = svg.documentWidth.toInt().takeIf { it > 0 }
            ?: (svg.documentViewBox.right - svg.documentViewBox.left).toInt()
        val height = svg.documentHeight.toInt().takeIf { it > 0 }
            ?: (svg.documentViewBox.bottom - svg.documentViewBox.top).toInt()
        return Size(width, height)      
    }

}
