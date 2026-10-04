package de.energy6.caravanleveler

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withRotation
import com.google.android.material.color.MaterialColors
import kotlin.math.cos
import kotlin.math.sin

class CalibrationRotationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val strokeWidth = 2.5f * density
    private val primaryColor = MaterialColors.getColor(
        this,
        androidx.appcompat.R.attr.colorPrimary
    )
    private val surfaceColor = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorSurface
    )
    private val onSurfaceColor = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorOnSurface
    )
    private val phoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = surfaceColor
        style = Paint.Style.FILL
    }
    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryColor
        style = Paint.Style.STROKE
        strokeWidth = this@CalibrationRotationView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onSurfaceColor
        alpha = 72
        style = Paint.Style.STROKE
        strokeWidth = this@CalibrationRotationView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val targetPaint = Paint(guidePaint)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = surfaceColor
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private var measuredAngleDegrees = 0f
    private var demoAngleDegrees = 0f
    private var isLive = false
    private var targetReached = false
    private var closesLoop = false
    private var demoAnimator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun render(
        angleDegrees: Float,
        live: Boolean,
        reached: Boolean,
        closureTurn: Boolean
    ) {
        measuredAngleDegrees = angleDegrees.coerceIn(-120f, 120f)
        isLive = live
        targetReached = reached
        closesLoop = closureTurn
        if (isLive) stopDemo() else startDemoIfPossible()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startDemoIfPossible()
    }

    override fun onDetachedFromWindow() {
        stopDemo()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (220f * density).toInt() + paddingLeft + paddingRight
        val desiredHeight = (170f * density).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f + 4f * density
        val phoneWidth = 48f * density
        val phoneHeight = 88f * density
        val corner = 8f * density
        val displayedAngle = if (isLive) measuredAngleDegrees else demoAngleDegrees

        targetPaint.color = if (targetReached) primaryColor else onSurfaceColor
        targetPaint.alpha = if (targetReached) 220 else 58
        drawPhone(canvas, centerX, centerY, phoneWidth, phoneHeight, corner, 0f, guidePaint, false)
        drawPhone(canvas, centerX, centerY, phoneWidth, phoneHeight, corner, 90f, targetPaint, false)
        drawDirectionArrow(canvas, centerX, centerY, phoneHeight * 0.72f)
        drawPhone(
            canvas,
            centerX,
            centerY,
            phoneWidth,
            phoneHeight,
            corner,
            displayedAngle,
            phonePaint,
            true
        )
        if (closesLoop) drawClosureMarker(canvas, centerX, centerY, phoneHeight)
    }

    private fun drawPhone(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        phoneWidth: Float,
        phoneHeight: Float,
        corner: Float,
        angle: Float,
        outline: Paint,
        fill: Boolean
    ) {
        canvas.withRotation(angle, centerX, centerY) {
            val bounds = RectF(
                centerX - phoneWidth / 2f,
                centerY - phoneHeight / 2f,
                centerX + phoneWidth / 2f,
                centerY + phoneHeight / 2f
            )
            if (fill) drawRoundRect(bounds, corner, corner, phoneFillPaint)
            drawRoundRect(bounds, corner, corner, outline)
            drawLine(
                centerX - phoneWidth * 0.15f,
                centerY - phoneHeight * 0.37f,
                centerX + phoneWidth * 0.15f,
                centerY - phoneHeight * 0.37f,
                outline
            )
        }
    }

    private fun drawDirectionArrow(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val arc = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawArc(arc, -72f, 118f, false, phonePaint)
        val endDegrees = 46f
        val endRadians = Math.toRadians(endDegrees.toDouble())
        val x = centerX + cos(endRadians).toFloat() * radius
        val y = centerY + sin(endRadians).toFloat() * radius
        val arrow = Path().apply {
            moveTo(x, y)
            lineTo(x - 10f * density, y - 1f * density)
            moveTo(x, y)
            lineTo(x - 3f * density, y - 10f * density)
        }
        canvas.drawPath(arrow, phonePaint)
    }

    private fun drawClosureMarker(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        phoneHeight: Float
    ) {
        val markerX = centerX + phoneHeight * 0.48f
        val markerY = centerY - phoneHeight * 0.34f
        canvas.drawCircle(markerX, markerY, 11f * density, phonePaint.apply {
            style = Paint.Style.FILL
        })
        canvas.drawText("1", markerX, markerY + 4f * density, labelPaint)
        phonePaint.style = Paint.Style.STROKE
    }

    private fun startDemoIfPossible() {
        if (isLive || !isAttachedToWindow || demoAnimator != null ||
            !ValueAnimator.areAnimatorsEnabled()
        ) {
            return
        }
        demoAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2_400L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                demoAngleDegrees = when {
                    fraction < 0.15f -> 0f
                    fraction > 0.75f -> 90f
                    else -> {
                        val progress = ((fraction - 0.15f) / 0.6f).coerceIn(0f, 1f)
                        val eased = progress * progress * (3f - 2f * progress)
                        eased * 90f
                    }
                }
                invalidate()
            }
            start()
        }
    }

    private fun stopDemo() {
        demoAnimator?.cancel()
        demoAnimator = null
    }
}
