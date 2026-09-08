package com.winlator.star.profiler

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale

/**
 * Lightweight in-game overlay shown during a profiling session.
 * Displays: progress bar, timer, live FPS, CPU usage, "Stop Early" button.
 * Pure Canvas drawing — no XML layout, no Compose dependency.
 */
class ProfilerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface onStopEarlyListener {
        fun onStopEarly()
    }

    private var session: ProfilerSession? = null
    private var onStopListener: onStopEarlyListener? = null
    private var lastFps: Float = 0f
    private var lastCpu: Int? = null

    // Paints
    private val bgPaint = Paint().apply {
        color = Color.argb(200, 20, 20, 30)
        style = Paint.Style.FILL
    }
    private val progressBgPaint = Paint().apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val progressFgPaint = Paint().apply {
        color = Color.rgb(100, 200, 255)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.MONOSPACE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 180, 180, 200)
        textSize = 26f
        typeface = Typeface.DEFAULT
    }
    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 255, 100)
        textSize = 38f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val stopBgPaint = Paint().apply {
        color = Color.argb(180, 60, 60, 80)
        style = Paint.Style.FILL
    }
    private val stopTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 120, 120)
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val stopRect = RectF()
    private val roundedRect = RectF()
    private val cornerRadius = 16f

    fun setSession(session: ProfilerSession) {
        this.session = session
    }

    fun setStopEarlyListener(listener: onStopEarlyListener) {
        this.onStopListener = listener
    }

    fun updateMetrics(fps: Float, cpuPercent: Int?) {
        lastFps = fps
        lastCpu = cpuPercent
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = session ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        val padding = 16f
        val lineHeight = 36f

        // Calculate total height needed
        val totalHeight = padding + lineHeight * 5 + padding + 12f // progress bar
        val topY = padding

        // Background rounded rect
        roundedRect.set(0f, 0f, w, totalHeight)
        canvas.drawRoundRect(roundedRect, cornerRadius, cornerRadius, bgPaint)

        // ── Title ──
        var y = topY + lineHeight
        canvas.drawText("📊 PROFILING", padding, y, textPaint.apply { textSize = 30f; color = Color.rgb(100, 200, 255) })

        // ── Timer ──
        y += lineHeight
        val elapsed = s.elapsedSeconds
        val remaining = (ProfilerSession.DURATION_MS / 1000f) - elapsed
        val timeStr = String.format(Locale.US, "%.0fs / %.0fs", elapsed, ProfilerSession.DURATION_MS / 1000f)
        canvas.drawText("⏱ $timeStr", padding, y, textPaint.apply { textSize = 28f; color = Color.WHITE })

        // ── FPS ──
        y += lineHeight + 4f
        canvas.drawText("FPS", padding, y, labelPaint)
        canvas.drawText(String.format(Locale.US, "%.0f", lastFps), padding + 80f, y, fpsPaint.apply {
            color = when {
                lastFps >= 50 -> Color.rgb(100, 255, 100)
                lastFps >= 30 -> Color.rgb(255, 255, 100)
                else -> Color.rgb(255, 100, 100)
            }
        })

        // ── CPU ──
        y += lineHeight
        val cpuStr = lastCpu?.let { "$it%" } ?: "N/A"
        canvas.drawText("CPU", padding, y, labelPaint)
        canvas.drawText(cpuStr, padding + 80f, y, textPaint.apply { textSize = 28f; color = Color.WHITE })

        // ── Readings count ──
        y += lineHeight
        canvas.drawText("Readings: ${s.readings.size}", padding, y, labelPaint)

        // ── Progress bar ──
        y += lineHeight + 8f
        val barLeft = padding
        val barRight = w - padding
        val barHeight = 12f
        roundedRect.set(barLeft, y, barRight, y + barHeight)
        canvas.drawRoundRect(roundedRect, 6f, 6f, progressBgPaint)
        val progress = s.progress
        if (progress > 0f) {
            roundedRect.set(barLeft, y, barLeft + (barRight - barLeft) * progress, y + barHeight)
            canvas.drawRoundRect(roundedRect, 6f, 6f, progressFgPaint)
        }

        // ── Stop Early button ──
        val btnW = 180f
        val btnH = 50f
        val btnX = w - padding - btnW
        val btnY = y + barHeight + 12f
        stopRect.set(btnX, btnY, btnX + btnW, btnY + btnH)
        canvas.drawRoundRect(stopRect, 12f, 12f, stopBgPaint)
        canvas.drawText("Stop Early", btnX + 24f, btnY + 34f, stopTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (stopRect.contains(event.x, event.y)) {
                onStopListener?.onStopEarly()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
