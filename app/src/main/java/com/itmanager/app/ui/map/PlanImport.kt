package com.itmanager.app.ui.map

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Picture
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.caverock.androidsvg.SVG
import com.itmanager.app.core.network.UserFacingException
import com.itmanager.app.data.model.PlanFile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a building plan picked on the phone into something the server takes (PNG, JPEG or SVG),
 * and plan bytes back into a picture for the map. Blocking: call off the main thread.
 */
object PlanImport {
    /** The server's limit is 15 MB; leave room for the request around it. */
    private const val MAX_UPLOAD = 14 * 1024 * 1024
    /** Larger files are not read at all: a phone photo is a few MB, a scanned A0 sheet can be far more. */
    private const val MAX_INPUT = 60 * 1024 * 1024
    /** Longest side of an uploaded raster plan; walls stay readable when zoomed in. */
    private const val MAX_SIDE = 4096
    /** Longest side the map draws a raster plan at, to keep it to a few tens of MB in memory. */
    private const val DISPLAY_SIDE = 3072
    /** A PDF page is rendered at this size: about 260 dpi on A4, enough for room numbers. */
    private const val PDF_SIDE = 3000

    fun read(resolver: ContentResolver, uri: Uri): PlanFile {
        if (resolver.getType(uri) == "application/pdf") return fromPdf(resolver, uri)
        val bytes = resolver.openInputStream(uri)?.use { it.readLimited() } ?: throw unreadable()
        // A PDF that came without its type: the renderer needs a file descriptor, so it is opened again.
        if (bytes.startsWith("%PDF")) return fromPdf(resolver, uri)
        if (looksLikeSvg(bytes)) return fromSvg(bytes)
        return fromRaster(bytes)
    }

    /** A plan to draw under the devices; null when the bytes are neither an image nor an SVG. */
    fun picture(bytes: ByteArray): Picture? = runCatching {
        if (looksLikeSvg(bytes)) return@runCatching SVG.getFromInputStream(bytes.inputStream()).renderToPicture()
        val bitmap = decodeSampled(bytes, DISPLAY_SIDE) ?: return@runCatching null
        Picture().apply {
            beginRecording(bitmap.width, bitmap.height).drawBitmap(bitmap, 0f, 0f, null)
            endRecording()
        }
    }.getOrNull()

    private fun fromSvg(bytes: ByteArray): PlanFile {
        if (bytes.size > MAX_UPLOAD) throw tooBig()
        val svg = runCatching { SVG.getFromInputStream(bytes.inputStream()) }.getOrNull() ?: throw unreadable()
        val box = svg.documentViewBox
        val w = svg.documentWidth.takeIf { it > 0 } ?: box?.width() ?: 1600f
        val h = svg.documentHeight.takeIf { it > 0 } ?: box?.height() ?: (w * 0.625f)
        return PlanFile(bytes, "image/svg+xml", w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
    }

    private fun fromRaster(bytes: ByteArray): PlanFile {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw unreadable()
        val mime = bounds.outMimeType
        // PNG and JPEG of a sensible size go as they are; anything else (HEIC, WebP, huge photos) is re-encoded.
        if (mime in setOf("image/png", "image/jpeg") && max(bounds.outWidth, bounds.outHeight) <= MAX_SIDE &&
            bytes.size <= MAX_UPLOAD
        ) {
            return PlanFile(bytes, mime, bounds.outWidth, bounds.outHeight)
        }
        val bitmap = decodeSampled(bytes, MAX_SIDE) ?: throw unreadable()
        return encode(bitmap, preferPng = false)
    }

    private fun fromPdf(resolver: ContentResolver, uri: Uri): PlanFile {
        val fd = resolver.openFileDescriptor(uri, "r") ?: throw unreadable()
        val bitmap = runCatching {
            PdfRenderer(fd).use { pdf ->
                if (pdf.pageCount == 0) throw unreadable()
                pdf.openPage(0).use { page ->
                    val k = PDF_SIDE.toFloat() / max(page.width, page.height)
                    val bitmap = Bitmap.createBitmap(
                        (page.width * k).roundToInt().coerceAtLeast(1),
                        (page.height * k).roundToInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    // PDF pages are transparent where nothing is printed; plans are drawn on paper.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }.getOrElse { if (it is UserFacingException) throw it else throw UserFacingException("Не удалось открыть PDF: файл повреждён или защищён паролем.") }
        return encode(bitmap, preferPng = true)
    }

    /** PNG keeps thin lines of a drawing crisp; a photo or scan that comes out too large goes as JPEG. */
    private fun encode(bitmap: Bitmap, preferPng: Boolean): PlanFile {
        if (preferPng) {
            val png = compress(bitmap, Bitmap.CompressFormat.PNG, 100)
            if (png.size <= MAX_UPLOAD) return PlanFile(png, "image/png", bitmap.width, bitmap.height)
        }
        for (quality in intArrayOf(88, 75, 60)) {
            val jpeg = compress(bitmap, Bitmap.CompressFormat.JPEG, quality)
            if (jpeg.size <= MAX_UPLOAD) return PlanFile(jpeg, "image/jpeg", bitmap.width, bitmap.height)
        }
        throw tooBig()
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()

    /** Decodes with a power-of-two sample size, then scales so the longest side is at most [side]. */
    private fun decodeSampled(bytes: ByteArray, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longest = max(decoded.width, decoded.height)
        if (longest <= side) return decoded
        val k = side.toFloat() / longest
        return Bitmap.createScaledBitmap(decoded, (decoded.width * k).roundToInt(), (decoded.height * k).roundToInt(), true)
    }

    fun looksLikeSvg(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        return head.startsWith("<svg") || (head.startsWith("<") && head.contains("<svg"))
    }

    private fun ByteArray.startsWith(text: String) =
        size >= text.length && text.indices.all { this[it] == text[it].code.toByte() }

    private fun InputStream.readLimited(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_INPUT) throw tooBig()
        }
        return out.toByteArray()
    }

    private fun unreadable() = UserFacingException("Не удалось открыть файл. Подойдёт PDF, PNG, JPEG или SVG.")

    private fun tooBig() = UserFacingException("Файл плана слишком большой. Сохраните его меньшего размера или в PDF.")
}
