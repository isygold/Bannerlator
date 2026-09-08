package com.winlator.star.profiler

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.screens.OutlinedAlertDialog

/**
 * Results dialog shown after a profiling session completes.
 * Displays: FPS chart, summary table, recommendations, action buttons.
 */
@Composable
fun ProfilerResultsDialog(
    session: ProfilerSession,
    recommendations: List<ProfilerRecommendations.Recommendation>,
    onApplySettings: (List<ProfilerRecommendations.Recommendation>) -> Unit,
    onReProfile: () -> Unit,
    onDismiss: () -> Unit
) {
    val summary = session.summary ?: return
    val readings = session.readings

    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Game Profile Results", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 500.dp)
            ) {
                // ── FPS Chart ──
                Text("FPS OVER TIME", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                FpsChart(
                    readings = readings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ── Summary Table ──
                Text("SUMMARY", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                SummaryTable(summary)

                Spacer(modifier = Modifier.height(12.dp))

                // ── Recommendations ──
                if (recommendations.isNotEmpty()) {
                    Text("RECOMMENDATIONS", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    recommendations.forEach { rec ->
                        RecommendationRow(rec)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recommendations.any { it.key != "note" }) {
                    TextButton(onClick = { onApplySettings(recommendations) }) {
                        Text("Apply Settings")
                    }
                }
                TextButton(onClick = onReProfile) {
                    Text("Re-Profile")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}

@Composable
private fun FpsChart(
    readings: List<ProfilerSession.Reading>,
    modifier: Modifier = Modifier
) {
    val lineColor = Color(0xFF64C8FF)
    val gridColor = Color(0x40FFFFFF)
    val bgColor = Color(0xFF1A1A2E)

    Canvas(modifier = modifier.background(bgColor, RoundedCornerShape(8.dp))) {
        if (readings.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height
        val padLeft = 48f
        val padBottom = 24f
        val chartW = w - padLeft
        val chartH = h - padBottom

        // Find max FPS for scale
        val maxFps = readings.maxOfOrNull { it.fps }?.coerceAtLeast(60f) ?: 60f

        // Grid lines (at 0, 30, 60)
        val gridValues = listOf(0f, 30f, 60f, 90f).filter { it <= maxFps * 1.1f }
        for (gv in gridValues) {
            val y = chartH - (gv / (maxFps * 1.1f)) * chartH
            drawLine(gridColor, Offset(padLeft, y), Offset(w, y), strokeWidth = 1f)
            // Label
            drawContext.canvas.nativeCanvas.drawText(
                gv.toInt().toString(),
                4f, y + 8f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(150, 150, 170)
                    textSize = 20f
                }
            )
        }

        // FPS line
        val path = Path()
        val durationMs = ProfilerSession.DURATION_MS.toFloat()
        for ((i, r) in readings.withIndex()) {
            val x = padLeft + (r.timestampMs / durationMs) * chartW
            val y = chartH - (r.fps / (maxFps * 1.1f)) * chartH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3f))

        // Time axis labels
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val x = padLeft + t * chartW
            val secs = (t * ProfilerSession.DURATION_MS / 1000).toInt()
            drawContext.canvas.nativeCanvas.drawText(
                "${secs}s",
                x - 8f, h - 2f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(150, 150, 170)
                    textSize = 18f
                }
            )
        }
    }
}

@Composable
private fun SummaryTable(summary: ProfilerSession.Summary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        SummaryRow("Avg FPS", String.format("%.1f", summary.avgFps), ratingColor(summary.avgFps, 30f, 50f))
        SummaryRow("Min FPS", "${summary.minFps}", ratingColor(summary.minFps.toFloat(), 20f, 40f))
        SummaryRow("Max FPS", "${summary.maxFps}", Color(0xFF4CAF50))
        SummaryRow("Low 1%", String.format("%.1f", summary.low1Fps), ratingColor(summary.low1Fps, 20f, 40f))
        SummaryRow("Low 0.1%", String.format("%.1f", summary.low01Fps), ratingColor(summary.low01Fps, 15f, 30f))
        SummaryRow("Frame Time", String.format("%.1f ms", summary.avgFrameTimeMs), ratingColor(1000f / summary.avgFrameTimeMs.coerceAtLeast(1f), 30f, 50f))
        summary.avgCpuPercent?.let { SummaryRow("CPU Usage", "$it%", ratingInverse(it, 70, 90)) }
        summary.avgGpuLoadPercent?.let { SummaryRow("GPU Load", "$it%", ratingInverse(it, 80, 95)) }
        summary.avgVramUsedBytes?.let { SummaryRow("GPU Mem", ProfilerRecommendations.formatBytes(it), Color(0xFF90CAF9)) }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

@Composable
private fun RecommendationRow(rec: ProfilerRecommendations.Recommendation) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${rec.key} = ${rec.value}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = rec.rationale,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp
            )
        }
    }
}

private fun ratingColor(value: Float, poor: Float, good: Float): Color {
    return when {
        value >= good -> Color(0xFF4CAF50)   // green
        value >= poor -> Color(0xFFFFC107)   // amber
        else -> Color(0xFFF44336)            // red
    }
}

private fun ratingInverse(value: Int, poor: Int, danger: Int): Color {
    return when {
        value <= poor -> Color(0xFF4CAF50)
        value <= danger -> Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }
}
