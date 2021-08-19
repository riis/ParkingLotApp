package com.dji.droneparking.tracker

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.*

class BorderedText private constructor(interiorColor: Int, exteriorColor: Int, textSize: Float) {
    private val interiorPaint: Paint = Paint()
    private val exteriorPaint: Paint
    private val textSize: Float

    /**
     * Creates a left-aligned bordered text object with a white interior, and a black exterior with
     * the specified text size.
     *
     * @param textSize text size in pixels
     */
    constructor(textSize: Float) : this(Color.WHITE, Color.BLACK, textSize) {}

    fun setTypeface(typeface: Typeface?) {
        interiorPaint.typeface = typeface
        exteriorPaint.typeface = typeface
    }

    fun drawText(canvas: Canvas, posX: Float, posY: Float, text: String?) {
        canvas.drawText(text!!, posX, posY, exteriorPaint)
        canvas.drawText(text, posX, posY, interiorPaint)
    }

    fun drawLines(canvas: Canvas, posX: Float, posY: Float, lines: Vector<String?>) {
        var lineNum = 0
        for (line in lines) {
            drawText(canvas, posX, posY - textSize * (lines.size - lineNum - 1), line)
            ++lineNum
        }
    }

    /**
     * Create a bordered text object with the specified interior and exterior colors, text size and
     * alignment.
     *
     * @param interiorColor the interior text color
     * @param exteriorColor the exterior text color
     * @param textSize      text size in pixels
     */
    init {
        interiorPaint.textSize = textSize
        interiorPaint.color = interiorColor
        interiorPaint.style = Paint.Style.FILL
        interiorPaint.isAntiAlias = false
        interiorPaint.alpha = 255
        exteriorPaint = Paint()
        exteriorPaint.textSize = textSize
        exteriorPaint.color = exteriorColor
        exteriorPaint.style = Paint.Style.FILL_AND_STROKE
        exteriorPaint.strokeWidth = textSize / 8
        exteriorPaint.isAntiAlias = false
        exteriorPaint.alpha = 255
        this.textSize = textSize
    }
}