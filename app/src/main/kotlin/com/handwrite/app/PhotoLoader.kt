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
import java.io.InputStream
import kotlin.math.max

/** Loads photos as normalised grayscale ink maps (1 = ink) for the CV pipeline. */
object PhotoLoader {

    class Gray(val data: FloatArray, val width: Int, val height: Int, val preview: Bitmap)

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
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null
        return fromBitmap(bmp)
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
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return Gray(gray, w, h, bmp)
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
