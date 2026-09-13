package com.therealsoftware.duo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/** A picture ready to be shown across the two screens, and how many pages it has. */
class Picture(val canvas: Bitmap, val pages: Int)

/**
 * Turn a picked file into one bitmap the size of the whole canvas, with the
 * picture centred inside it and black around it.
 *
 * The whole picture is fitted, not cropped. A photograph keeps all four edges,
 * and a page of a document keeps its footer. That is the point of showing it on
 * two screens rather than one.
 *
 * Both a still image and a page of a PDF arrive here. Android renders PDF pages
 * itself, so no library is needed for either.
 */
fun renderPicture(
    context: Context,
    uri: Uri,
    page: Int,
    canvasW: Int,
    canvasH: Int,
): Picture? {
    if (canvasW <= 0 || canvasH <= 0) return null
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: ""
    return if (mime.startsWith("image/")) imageToCanvas(context, uri, canvasW, canvasH)
    else pdfToCanvas(context, uri, page, canvasW, canvasH)
}

/** How many pages the picked file has. One for an image. */
fun pageCount(context: Context, uri: Uri): Int {
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: ""
    if (mime.startsWith("image/")) return 1
    return runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    }.getOrNull() ?: 1
}

private fun imageToCanvas(context: Context, uri: Uri, w: Int, h: Int): Picture? {
    // Ask how big it is before decoding it. A 40 megapixel photograph decoded at
    // full size would take 160 MB, which is more than the phone will give us.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, w, h)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull() ?: return null

    val fitted = fitInside(decoded, w, h)
    if (fitted !== decoded) decoded.recycle()
    val canvas = centred(fitted, w, h)
    if (canvas !== fitted) fitted.recycle()
    return Picture(canvas, 1)
}

private fun pdfToCanvas(context: Context, uri: Uri, page: Int, w: Int, h: Int): Picture? {
    val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        ?: return null
    return pfd.use { descriptor ->
        runCatching {
            PdfRenderer(descriptor).use { renderer ->
                val index = page.coerceIn(0, renderer.pageCount - 1)
                renderer.openPage(index).use { p ->
                    // Render at the size we will draw, so the text stays sharp.
                    val scale = min(w.toFloat() / p.width, h.toFloat() / p.height)
                    val rw = max(1, (p.width * scale).toInt())
                    val rh = max(1, (p.height * scale).toInt())
                    val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                    // PDF pages are transparent by default, which would show the
                    // screen behind them. Paint the paper white first.
                    Canvas(bmp).drawColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val canvas = centred(bmp, w, h)
                    if (canvas !== bmp) bmp.recycle()
                    Picture(canvas, renderer.pageCount)
                }
            }
        }.getOrNull()
    }
}

/**
 * The largest power of two that still leaves the decoded image at least as big
 * as the canvas. Decoding smaller than the canvas would show a soft picture.
 */
private fun sampleSizeFor(srcW: Int, srcH: Int, w: Int, h: Int): Int {
    var sample = 1
    while (srcW / (sample * 2) >= w && srcH / (sample * 2) >= h) sample *= 2
    return sample
}

private fun fitInside(src: Bitmap, w: Int, h: Int): Bitmap {
    val scale = min(w.toFloat() / src.width, h.toFloat() / src.height)
    if (scale >= 1f) return src          // already smaller than the canvas
    val tw = max(1, (src.width * scale).toInt())
    val th = max(1, (src.height * scale).toInt())
    return Bitmap.createScaledBitmap(src, tw, th, true)
}

private fun centred(src: Bitmap, w: Int, h: Int): Bitmap {
    if (src.width == w && src.height == h) return src
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(out).apply {
        drawColor(Color.BLACK)
        drawBitmap(src, (w - src.width) / 2f, (h - src.height) / 2f, null)
    }
    return out
}
