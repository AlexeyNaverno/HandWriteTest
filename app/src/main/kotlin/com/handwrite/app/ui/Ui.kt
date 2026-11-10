package com.handwrite.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny UI toolkit: the app builds its screens in code (no XML, no AndroidX). */
object Ui {

    val ACCENT = Color.parseColor("#FF2E6BE6")
    val ACCENT_DARK = Color.parseColor("#FF1B4CB0")
    val INK = Color.parseColor("#FF1B2A4A")
    val TEXT = Color.parseColor("#FF1A1D24")
    val TEXT2 = Color.parseColor("#FF6B7380")
    val SURFACE = Color.parseColor("#FFF4F1E9")
    val CARD = Color.WHITE
    val DIVIDER = Color.parseColor("#FFE2DED2")
    val PAPER = Color.parseColor("#FFFFFDF7")

    fun dp(context: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = radiusPx
        d.setColor(color)
        if (strokeColor != null && strokeWidth > 0) d.setStroke(strokeWidth, strokeColor)
        return d
    }

    fun card(context: Context, padding: Int = 16): LinearLayout {
        val l = LinearLayout(context)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(context, padding.toFloat()), dp(context, padding.toFloat()),
            dp(context, padding.toFloat()), dp(context, padding.toFloat()))
        l.background = rounded(CARD, dp(context, 18f).toFloat(), DIVIDER, dp(context, 1f))
        return l
    }

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float = 15f,
        color: Int = TEXT,
        bold: Boolean = false
    ): TextView {
        val t = TextView(context)
        t.text = value
        t.setTextSize(sizeSp)
        t.setTextColor(color)
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
        t.setLineSpacing(0f, 1.12f)
        return t
    }

    fun sectionTitle(context: Context, value: String): TextView {
        val t = text(context, value.uppercase(), 11.5f, TEXT2, true)
        t.letterSpacing = 0.08f
        t.setPadding(0, dp(context, 18f), 0, dp(context, 8f))
        return t
    }

    fun button(
        context: Context,
        label: String,
        primary: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val b = TextView(context)
        b.text = label
        b.setTextSize(15f)
        b.gravity = Gravity.CENTER
        b.setTextColor(if (primary) Color.WHITE else TEXT)
        b.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
        b.setPadding(dp(context, 18f), dp(context, 12f), dp(context, 18f), dp(context, 12f))
        b.background = rounded(
            if (primary) ACCENT else Color.parseColor("#FFEDEAE0"),
            dp(context, 14f).toFloat()
        )
        b.isClickable = true
        b.isFocusable = true
        b.setOnClickListener { onClick() }
        return b
    }

    fun chip(context: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val c = text(context, label, 14f, if (selected) Color.WHITE else TEXT, selected)
        c.gravity = Gravity.CENTER
        c.setPadding(dp(context, 14f), dp(context, 9f), dp(context, 14f), dp(context, 9f))
        c.background = rounded(
            if (selected) INK else Color.parseColor("#FFEDEAE0"),
            dp(context, 20f).toFloat(),
            if (selected) null else DIVIDER, dp(context, 1f)
        )
        c.isClickable = true
        c.setOnClickListener { onClick() }
        return c
    }

    fun divider(context: Context, vertical: Boolean = false): View {
        val v = View(context)
        v.setBackgroundColor(DIVIDER)
        val lp = if (vertical) LinearLayout.LayoutParams(dp(context, 1f), ViewGroup.LayoutParams.MATCH_PARENT)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1f))
        v.layoutParams = lp
        return v
    }

    fun space(context: Context, heightDp: Int): View {
        val v = View(context)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp.toFloat()))
        return v
    }

    fun row(context: Context): LinearLayout {
        val l = LinearLayout(context)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun frame(context: Context): FrameLayout = FrameLayout(context)

    /** Slightly rounded soft shadow effect using an inner card on a tinted frame. */
    fun outlined(context: Context, view: View, radiusDp: Float = 18f): FrameLayout {
        val f = FrameLayout(context)
        f.background = rounded(Color.WHITE, dp(context, radiusDp).toFloat(), DIVIDER, dp(context, 1f))
        f.addView(
            view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return f
    }

    fun rectF(left: Float, top: Float, right: Float, bottom: Float) = RectF(left, top, right, bottom)

    fun roundPx(paint: Paint, value: Float) {
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = value
    }
}

/** A view that draws its content with a callback (used for chips previews etc.). */
class DrawView(
    context: Context,
    private val onDrawBlock: (Canvas, Int, Int) -> Unit,
    private val onMeasureBlock: ((Int, Int) -> Pair<Int, Int>)? = null
) : View(context) {

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (onMeasureBlock != null) {
            val (_, desiredH) = onMeasureBlock(w, MeasureSpec.getSize(heightMeasureSpec))
            desiredH
        } else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        onDrawBlock(canvas, width, height)
    }
}
