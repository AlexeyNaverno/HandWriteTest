package com.handwrite.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.media.ExifInterface
import java.io.InputStream
import kotlin.math.max

/** Loads photos as normalised grayscale ink maps (1 = ink) for the CV pipeline. */
object PhotoLoader {

    /**
     * @param data grayscale luminance (kept for previews and for the fallback path)
     * @param ink colour-based ink map from [com.handwrite.core.handwriting.InkMask]; null when the
     *   photo could not be read in colour
     */
    class Gray(
        val data: FloatArray,
        val width: Int,
        val height: Int,
        val preview: Bitmap,
        val ink: FloatArray? = null
    )

    private const val MAX_SIDE = 1600
    private const val MIN_SIDE = 700

    fun load(context: Context, uri: Uri): Gray? {
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        var side = max(opts.outWidth, opts.outHeight)
        while (side / sample > MAX_SIDE) sample *= 2
        val decodeOpts = BitmapFactory.Options()
        decodeOpts.inSampleSize = sample
        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888
        var bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null
        // Cameras store the sensor image *plus* an EXIF orientation tag; BitmapFactory ignores the
        // tag, which is why photos used to come out rotated ("буквы боком", upside down).
        bmp = applyExifOrientation(context, uri, bmp)
        return fromBitmap(bmp)
    }

    /**
     * Rotates/mirrors a decoded photo according to its EXIF orientation.
     *
     * Uses the framework [android.media.ExifInterface] (available since API 5 for files, since API 24
     * for streams), so no AndroidX dependency is needed and the APK stays small. Any failure just
     * leaves the bitmap as decoded.
     */
    fun applyExifOrientation(context: Context, uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { readOrientation(it) }
        } catch (e: Exception) {
            null
        } ?: return bmp
        return applyOrientation(bmp, orientation)
    }

    /** reads the EXIF orientation tag (1..8) from a JPEG/PNG stream, or null when absent */
    private fun readOrientation(input: InputStream): Int? = try {
        val exif = ExifInterface(input)
        val o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        if (o in 1..8) o else null
    } catch (e: Throwable) {
        null
    }

    /** applies EXIF orientation 1..8 to a bitmap (see the TIFF/EXIF spec) */
    fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        if (orientation <= 1 || orientation > 8) return src
        val m = Matrix()
        when (orientation) {
            2 -> m.setScale(-1f, 1f)                    // mirrored horizontally
            3 -> m.setRotate(180f)
            4 -> m.setScale(1f, -1f)                    // mirrored vertically
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.setRotate(90f)                       // most phone photos in portrait
            7 -> { m.setRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.setRotate(270f)
        }
        return try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } catch (e: Exception) {
            src
        }
    }

    fun fromBitmap(bmp0: Bitmap): Gray {
        // scale into the working range
        var bmp = bmp0
        val side = max(bmp.width, bmp.height)
        if (side > MAX_SIDE) {
            val s = MAX_SIDE.toFloat() / side
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        } else if (side < MIN_SIDE) {
            val s = MIN_SIDE.toFloat() / side
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        }
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        val cr = FloatArray(w * h)
        val cg = FloatArray(w * h)
        val cb = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            cr[i] = r / 255f
            cg[i] = g / 255f
            cb[i] = b / 255f
            gray[i] = 0.299f * cr[i] + 0.587f * cg[i] + 0.114f * cb[i]
        }
        // The colour mask is what makes a squared notebook usable: the grid is neutral, the ink is
        // blue, so a page that used to segment into grid cells now yields real letters.
        val ink = try {
            com.handwrite.core.handwriting.InkMask.compute(cr, cg, cb, w, h)
        } catch (e: Throwable) {
            null
        }
        return Gray(gray, w, h, bmp, ink)
    }

    /** grayscale preview with the ink inverted to black-on-white (for the letter gallery) */
    fun grayscalePreview(bmp: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        c.drawBitmap(bmp, 0f, 0f, paint)
        return out
    }

    fun rotate(bmp: Bitmap, degrees: Float): Bitmap {
        val m = Matrix()
        m.postRotate(degrees)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
