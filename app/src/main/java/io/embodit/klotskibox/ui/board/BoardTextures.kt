package io.embodit.klotskibox.ui.board

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import io.embodit.klotskibox.domain.Layout
import io.embodit.klotskibox.platform.TAG

/**
 * Canvas textures for the abstract marks (the wooden models are GLBs from tools/build_models.py): the slide arrow, the
 * glow under a lit tile, the counter plate. Image up = the far edge (-Z) for marks lying on the box floor (D10: the
 * plane is laid flat with pitch -90°, which maps bitmap-up to -Z).
 */
object BoardTextures {
    /** An arrow pointing image-up (= far, -Z); rotated per direction on the plane. Soft cream with a dark rim. */
    fun arrow(size: Int = 256, fill: Int = Color.rgb(0xFF, 0xF1, 0xC8), rim: Int = Color.rgb(0x3A, 0x26, 0x14)): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val p = Path().apply {
            moveTo(s * 0.5f, s * 0.10f)
            lineTo(s * 0.86f, s * 0.50f)
            lineTo(s * 0.64f, s * 0.50f)
            lineTo(s * 0.64f, s * 0.88f)
            lineTo(s * 0.36f, s * 0.88f)
            lineTo(s * 0.36f, s * 0.50f)
            lineTo(s * 0.14f, s * 0.50f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = rim; paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.05f; paint.strokeJoin = Paint.Join.ROUND
        c.drawPath(p, paint)
        paint.style = Paint.Style.FILL; paint.color = fill
        c.drawPath(p, paint)
        return bmp
    }

    /** A soft rounded-rectangle glow ring for a tile of [wM] x [dM] metres drawn on a plane [marginM] larger on every side. */
    fun halo(wM: Float, dM: Float, marginM: Float, color: Int = Color.rgb(0xFF, 0xD8, 0x6A)): Bitmap {
        val pxPerM = 2400f
        val w = ((wM + 2 * marginM) * pxPerM).toInt()
        val h = ((dM + 2 * marginM) * pxPerM).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val m = marginM * pxPerM
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = m * 0.9f
            maskFilter = BlurMaskFilter(m * 0.45f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRoundRect(RectF(m * 0.75f, m * 0.75f, w - m * 0.75f, h - m * 0.75f), m, m, paint)
        return bmp
    }

    /** The light strip over the copper sill (gap glow). */
    fun strip(color: Int = Color.rgb(0xFF, 0xC8, 0x6A)): Bitmap {
        val bmp = Bitmap.createBitmap(512, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL) }
        c.drawRoundRect(RectF(16f, 16f, 496f, 48f), 16f, 16f, paint)
        return bmp
    }

    /** "F↑" orientation test (profile D10): the F must read upright from the user with its arrow pointing away. */
    fun orientationTest(size: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size * 0.6f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText("F↑", size / 2f, size * 0.7f, p)
        return bmp
    }

    val PLATE_FACE = Color.rgb(0xF6, 0xEC, 0xD8)
    val PLATE_INK = Color.rgb(0x2A, 0x1E, 0x14)
    val PLATE_EDGE = Color.rgb(0x6B, 0x4A, 0x2E)

    /** Text width (px) of a counter plate line at the plate's 80 px size. */
    fun plateTextWidth(text: String): Float = platePaint().measureText(text)

    private fun platePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PLATE_INK; textSize = 80f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    /**
     * The counter plate (design spatialLayout.counterPlate): 1024 x 180 px for 0.26 x 0.045 m, light wood face, ink text
     * 80 px (em 2.03 cm). A line wider than the 944 px text width logs OVERFLOW and is drawn smaller (never cut).
     */
    fun plate(text: String): Bitmap {
        val bmp = Bitmap.createBitmap(Layout.PLATE_PX_W, Layout.PLATE_PX_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = RectF(0f, 0f, Layout.PLATE_PX_W.toFloat(), Layout.PLATE_PX_H.toFloat())
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE_EDGE }
        c.drawRect(r, bg)
        bg.color = PLATE_FACE
        c.drawRoundRect(RectF(10f, 10f, r.right - 10f, r.bottom - 10f), 18f, 18f, bg)
        val p = platePaint()
        val w = p.measureText(text)
        if (w > Layout.PLATE_TEXT_PX) {
            Log.w(TAG, "OVERFLOW plate/text \"$text\" ${w.toInt()} px > ${Layout.PLATE_TEXT_PX}")
            p.textSize = 80f * Layout.PLATE_TEXT_PX / w
        }
        val fm = p.fontMetrics
        c.drawText(text, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, p)
        return bmp
    }
}
