package com.winlator.star.profiler

/**
 * Rule-based engine that takes profiling data and suggests optimal VEGAS config settings.
 *
 * All thresholds are based on practical Adreno TBDR experience — not invented numbers.
 * Each recommendation includes a rationale so the user knows WHY it's suggested.
 */
object ProfilerRecommendations {

    data class Recommendation(
        val key: String,         // VEGAS config key (e.g. "vegas.forceTier")
        val value: String,       // Suggested value (e.g. "1")
        val label: String,       // Human-readable label (e.g. "Force Tier 1 (Entry)")
        val rationale: String,   // Why this is suggested
        val confidence: Float    // 0..1, how confident we are (affects UI opacity)
    )

    fun analyze(summary: ProfilerSession.Summary): List<Recommendation> {
        val recs = mutableListOf<Recommendation>()

        // ── Tier recommendation ──────────────────────────────────────
        // Based on average FPS: <25 → tier 1, 25-45 → tier 2, >45 → tier 3
        val tierRec = when {
            summary.avgFps < 25f -> Recommendation(
                "vegas.forceTier", "1",
                "Force Tier 1 (Entry)",
                "Average FPS is ${summary.avgFps.toInt()} — well below 30. Entry tier reduces shader complexity.",
                0.9f
            )
            summary.avgFps < 45f -> Recommendation(
                "vegas.forceTier", "2",
                "Force Tier 2 (Mid)",
                "Average FPS is ${summary.avgFps.toInt()} — playable but not smooth. Mid tier balances quality and performance.",
                0.7f
            )
            else -> Recommendation(
                "vegas.forceTier", "0",
                "Keep Auto-Detection",
                "Average FPS is ${summary.avgFps.toInt()} — auto-detection is working well.",
                0.8f
            )
        }
        recs.add(tierRec)

        // ── Framerate cap ────────────────────────────────────────────
        // If FPS is unstable (high variance), cap it to smooth out frame pacing
        val fpsVariance = summary.maxFps - summary.minFps
        if (fpsVariance > 20 && summary.avgFps > 30) {
            val cap = when {
                summary.avgFps > 55 -> 60
                summary.avgFps > 40 -> 45
                else -> 30
            }
            recs.add(
                Recommendation(
                    "dxvk.maxFrameRate", cap.toString(),
                    "Cap FPS at $cap",
                    "FPS varies from ${summary.minFps} to ${summary.maxFps} (range: $fpsVariance). Capping reduces GPU spikes.",
                    0.8f
                )
            )
        }

        // ── Async shader compilation ─────────────────────────────────
        // If Low1% is significantly worse than avg, shader stutter is likely
        val stutterRatio = if (summary.avgFps > 0) summary.low1Fps / summary.avgFps else 1f
        if (stutterRatio < 0.6f && summary.avgFps > 15) {
            recs.add(
                Recommendation(
                    "dxvk.enableAsync", "true",
                    "Enable Async Shader Compilation",
                    "Low1% FPS (${summary.low1Fps.toInt()}) is ${(stutterRatio * 100).toInt()}% of average (${summary.avgFps.toInt()}) — shader stutter detected.",
                    0.85f
                )
            )
        }

        // ── Frame time analysis ──────────────────────────────────────
        // If average frame time > 33ms (below 30 FPS), heavy optimization needed
        if (summary.avgFrameTimeMs > 33f) {
            recs.add(
                Recommendation(
                    "vegas.enableUpscaler", "Auto",
                    "Enable Upscaler",
                    "Average frame time is ${summary.avgFrameTimeMs.toInt()}ms — upscaling can reduce rendering load.",
                    0.7f
                )
            )
        }

        // ── GPU memory pressure ──────────────────────────────────────
        // If VRAM > 80% of typical Adreno budget (~1GB for mid-range), suggest reducing
        summary.avgVramUsedBytes?.let { vram ->
            val vramMB = vram / (1024 * 1024)
            if (vramMB > 800) {
                recs.add(
                    Recommendation(
                        "d3d9.maxAvailableMemory", "512",
                        "Limit D3D9 Memory Pool",
                        "GPU memory usage is ${vramMB}MB — high for mobile. Limiting the pool prevents OOM crashes.",
                        0.6f
                    )
                )
            }
        }

        // ── CPU bottleneck detection ─────────────────────────────────
        // If CPU usage > 80% while GPU load is low, the bottleneck is CPU-side
        val cpu = summary.avgCpuPercent
        val gpu = summary.avgGpuLoadPercent
        if (cpu != null && gpu != null && cpu > 80 && gpu < 50) {
            recs.add(
                Recommendation(
                    "vegas.forceTier", "1",
                    "Force Tier 1 (Entry)",
                    "CPU at ${cpu}% while GPU at ${gpu}% — CPU bottleneck. Lower tier reduces CPU-side work.",
                    0.75f
                )
            )
        }

        // ── Performance is already good ──────────────────────────────
        if (summary.avgFps >= 55 && fpsVariance < 10 && stutterRatio > 0.7f) {
            recs.add(
                Recommendation(
                    "note", "none",
                    "Settings Look Good",
                    "Average ${summary.avgFps.toInt()} FPS with stable frame times. No changes recommended.",
                    0.9f
                )
            )
        }

        return recs
    }

    /**
     * Format a byte count as a human-readable string.
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> "%d MB".format(bytes / (1024 * 1024))
            bytes >= 1024 -> "%d KB".format(bytes / 1024)
            else -> "$bytes B"
        }
    }
}
