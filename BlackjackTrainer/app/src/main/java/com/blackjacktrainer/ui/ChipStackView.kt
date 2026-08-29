package com.blackjacktrainer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Zeichnet einen Betrag als Jetonstapel. Wie am echten Tisch sieht man vom
 * Stapel nur die Kante der unteren Jetons und die Fläche des obersten.
 */
class ChipStackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Denomination(val value: Int, val color: Int, val rim: Int)

    private val denominations = listOf(
        Denomination(500, Color.parseColor("#6A1B9A"), Color.parseColor("#4A1269")),
        Denomination(100, Color.parseColor("#1565C0"), Color.parseColor("#0E4585")),
        Denomination(25, Color.parseColor("#2E7D32"), Color.parseColor("#1F5623")),
        Denomination(5, Color.parseColor("#C62828"), Color.parseColor("#8C1C1C"))
    )

    /** Mehr Jetons zeigt der Stapel nicht - der genaue Betrag steht daneben. */
    private val maxChips = 7

    var amount: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private val chipWidth = 38f * density
    private val chipHeight = 24f * density
    private val step = 7f * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = Color.WHITE
    }
    private val oval = RectF()

    /** Zerlegt den Betrag gierig in Jetons, größter zuerst. */
    private fun chips(): List<Denomination> {
        var rest = amount
        val result = mutableListOf<Denomination>()
        for (denomination in denominations) {
            while (rest >= denomination.value && result.size < maxChips) {
                result.add(denomination)
                rest -= denomination.value
            }
        }
        return result
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = chips().size
        val height = if (count == 0) 0f else chipHeight + step * (count - 1)
        setMeasuredDimension(chipWidth.toInt(), height.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val stack = chips()
        if (stack.isEmpty()) return

        stroke.strokeWidth = 1.2f * density
        // Von unten nach oben: der größte Jeton liegt zuunterst.
        for ((index, chip) in stack.withIndex().reversed()) {
            val bottom = height - step * (stack.size - 1 - index)
            oval.set(0f, bottom - chipHeight, chipWidth, bottom)

            // Kante zuerst, dann die Fläche darüber - das gibt Tiefe.
            fill.color = chip.rim
            oval.offset(0f, 2f * density)
            canvas.drawOval(oval, fill)
            oval.offset(0f, -2f * density)

            fill.color = chip.color
            canvas.drawOval(oval, fill)
            stroke.color = Color.parseColor("#D9FFFFFF")
            canvas.drawOval(oval, stroke)
        }

        // Nur der oberste Jeton zeigt seinen Wert.
        val top = stack.first()
        val bottom = height - step * (stack.size - 1)
        oval.set(0f, bottom - chipHeight, chipWidth, bottom)
        stroke.color = Color.parseColor("#66FFFFFF")
        canvas.drawOval(
            oval.left + 5f * density,
            oval.top + 4f * density,
            oval.right - 5f * density,
            oval.bottom - 4f * density,
            stroke
        )
        label.textSize = 11f * density
        canvas.drawText(
            top.value.toString(),
            oval.centerX(),
            oval.centerY() + 4f * density,
            label
        )
    }
}
