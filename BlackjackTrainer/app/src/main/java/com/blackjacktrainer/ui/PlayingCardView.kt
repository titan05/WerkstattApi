package com.blackjacktrainer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.blackjacktrainer.game.Card

/** Zeichnet eine Spielkarte - offen oder verdeckt. */
class PlayingCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var card: Card? = null
        set(value) {
            field = value
            invalidate()
        }

    var faceDown: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Kartenbreite in dp - wird kleiner, wenn mehrere Hände auf dem Tisch sind. */
    var cardWidthDp: Float = 58f
        set(value) {
            field = value
            requestLayout()
        }

    private val density = context.resources.displayMetrics.density

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1B3A6B") }
    private val backAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2E5FA8") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#22000000")
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33000000") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (cardWidthDp * density).toInt()
        val h = (w * 1.45f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = w * 0.10f
        val inset = w * 0.03f

        rect.set(inset, inset + w * 0.02f, w - inset, h - inset)
        canvas.drawRoundRect(rect, radius, radius, shadowPaint)

        rect.set(inset, inset, w - inset, h - inset - w * 0.02f)
        borderPaint.strokeWidth = density

        if (faceDown) {
            canvas.drawRoundRect(rect, radius, radius, backPaint)
            val pad = w * 0.14f
            val inner = RectF(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
            canvas.drawRoundRect(inner, radius * 0.6f, radius * 0.6f, backAccent)
            centerPaint.color = Color.parseColor("#8FB4E8")
            centerPaint.textSize = w * 0.34f
            centerPaint.isFakeBoldText = true
            canvas.drawText("21", w / 2f, h / 2f + w * 0.12f, centerPaint)
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
            return
        }

        val c = card ?: return
        canvas.drawRoundRect(rect, radius, radius, facePaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val color = if (c.suit.isRed) Color.parseColor("#C62828") else Color.parseColor("#1A1A1A")
        textPaint.color = color
        centerPaint.color = color

        // Rang oben links
        textPaint.textSize = w * 0.30f
        canvas.drawText(c.rank.label, w * 0.13f, h * 0.24f, textPaint)

        // kleines Symbol darunter
        textPaint.textSize = w * 0.22f
        canvas.drawText(c.suit.symbol, w * 0.13f, h * 0.38f, textPaint)

        // großes Symbol in der Mitte
        centerPaint.textSize = w * 0.52f
        centerPaint.isFakeBoldText = false
        canvas.drawText(c.suit.symbol, w * 0.58f, h * 0.78f, centerPaint)
    }
}
