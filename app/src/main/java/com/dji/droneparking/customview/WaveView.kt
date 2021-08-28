package com.dji.droneparking.customview

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.annotation.Nullable

class WaveView @JvmOverloads constructor(
        context: Context?,
        @Nullable attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        defStyleRes: Int = 0
) :
        View(context, attrs, defStyleAttr, defStyleRes) {

    private var wavePaint: Paint? = null
    private var rectPaint: Paint? = null
    private var textPaint: Paint? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private val amplitude = 5
    private var path: Path? = null
    private var progress = 0f
    private var textProgress = 0f
    private val startPoint = Point()

    fun setProgress(progress: Float) {
        textProgress = progress
        if (progress == 100f) {
            this.progress = progress + amplitude
        } else {
            this.progress = progress
        }
    }

    private fun init() {
        wavePaint = Paint()
        wavePaint!!.isAntiAlias = true
        wavePaint!!.strokeWidth = 3f
        textPaint = Paint()
        textPaint!!.style = Paint.Style.STROKE
        textPaint!!.isAntiAlias = true
        textPaint!!.color = Color.parseColor("#000000")
        textPaint!!.textSize = 30f
        rectPaint = Paint()
        rectPaint!!.isAntiAlias = true
        rectPaint!!.color = Color.parseColor("#ffffff")
        rectPaint!!.strokeWidth = 10f
        rectPaint!!.style = Paint.Style.FILL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size =
                measureSize(400, widthMeasureSpec).coerceAtMost(measureSize(400, heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        screenWidth = w
        screenHeight = h
        startPoint.x = -screenWidth
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        clipRect(canvas)
        drawRect(canvas)//
        drawWave(canvas)
        drawText(canvas)
        postInvalidateDelayed(10)
    }

    private fun drawText(canvas: Canvas) {
        val targetRect = Rect(0, -screenHeight, screenWidth, 0)
        val fontMetrics = textPaint!!.fontMetricsInt
        val baseline =
                (targetRect.bottom + targetRect.top - fontMetrics.bottom - fontMetrics.top) / 2
        textPaint!!.textAlign = Paint.Align.CENTER
        canvas.drawText(
                (textProgress).toString() + "%",
                targetRect.centerX().toFloat()-5,
                baseline.toFloat(),
                textPaint!!
        )
        textPaint!!.textSize = 20F
        canvas.drawText(
                "Drone",
                targetRect.centerX().toFloat()-5,
                baseline.toFloat()+30f,
                textPaint!!
        )
        canvas.drawText(
                "Battery",
                targetRect.centerX().toFloat()-5,
                baseline.toFloat()+50f,
                textPaint!!
        )
        textPaint!!.textSize = 30F
    }

    private fun drawWave(canvas: Canvas) {
        val height = (progress / 100 * screenHeight).toInt()
        startPoint.y = -height
        canvas.translate(10f, screenHeight.toFloat())
        path = Path()
        wavePaint!!.style = Paint.Style.FILL
        var statusColor = 0
        statusColor = if (textProgress > 50){
            Color.GREEN
        }else if(textProgress <= 50 && textProgress > 20){
            Color.rgb(255, 165, 0)//ORANGE
        }else{
            Color.RED
        }
        wavePaint!!.shader = LinearGradient(0f, 0f, 0f, screenHeight.toFloat(), statusColor, Color.WHITE, Shader.TileMode.MIRROR)
        val wave = screenWidth / 4
        path!!.moveTo(startPoint.x.toFloat(), startPoint.y.toFloat())
        for (i in 0..3) {
            val startX = startPoint.x + i * wave * 2
            val endX = startX + 2 * wave
            if (i % 2 == 0) {
                path!!.quadTo(
                        ((startX + endX) / 2).toFloat(),
                        (startPoint.y + amplitude).toFloat(),
                        endX.toFloat(),
                        startPoint.y.toFloat()
                )
            } else {
                path!!.quadTo(
                        ((startX + endX) / 2).toFloat(),
                        (startPoint.y - amplitude).toFloat(),
                        endX.toFloat(),
                        startPoint.y.toFloat()
                )
            }
        }
        path!!.lineTo(screenWidth.toFloat(), (screenHeight / 2).toFloat())
        path!!.lineTo(-screenWidth.toFloat(), (screenHeight / 2).toFloat())
        path!!.lineTo(-screenWidth.toFloat(), 0f)
        path!!.close()
        canvas.drawPath(path!!, wavePaint!!)
        startPoint.x += 10
        if (startPoint.x > 0) {
            startPoint.x = -screenWidth
        }
        path!!.reset()
    }


    private fun drawRect(canvas: Canvas) {
        canvas.drawRoundRect(
                0f,
                0f,
                screenWidth.toFloat(),
                screenHeight.toFloat(),
                30f,
                30f,
                rectPaint!!
        )
    }

    private fun clipRect(canvas: Canvas){
        val rectPath = Path()
        rectPath.addRoundRect(
                0f,
                0f,
                screenWidth.toFloat(),
                screenHeight.toFloat(),
                30f,
                30f,
                Path.Direction.CW)
        canvas.clipPath(rectPath)
    }

    private fun measureSize(defaultSize: Int, measureSpec: Int): Int {
        var result = defaultSize
        val mode = MeasureSpec.getMode(measureSpec)
        val size = MeasureSpec.getSize(measureSpec)
        when (mode) {
            MeasureSpec.UNSPECIFIED -> result = defaultSize
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY -> result = size
            else -> {
            }
        }
        return result
    }

    init {
        init()
    }
}