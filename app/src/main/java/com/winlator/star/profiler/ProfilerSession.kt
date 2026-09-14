package com.winlator.star.profiler

import android.content.Context
import android.content.SharedPreferences
import com.winlator.star.widget.FpsCounter
import com.winlator.star.widget.HudMetrics
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 30-second game profiling session.
 *
 * Polls FpsCounter + HudMetrics on a 500ms interval for [DURATION_MS] milliseconds.
 * Stores all readings in a thread-safe list for the results dialog to consume.
 *
 * Usage:
 * 1. Call [start] when the game window appears (after onMapWindow).
 * 2. The session auto-stops after 30s or when [stop] is called ("Stop Early").
 * 3. Read [readings] and [summary] after completion.
 */
class ProfilerSession(
    private val fpsCounter: FpsCounter,
    private val metrics: HudMetrics
) {

    companion object {
        const val DURATION_MS = 30_000L
        const val POLL_INTERVAL_MS = 500L
        const val PREFS_NAME = "profiler_results"
        private const val KEY_LAST_TIMESTAMP = "last_timestamp"
        private const val KEY_LAST_CONTAINER = "last_container_id"
        private const val KEY_LAST_SUMMARY = "last_summary"
    }

    data class Reading(
        val timestampMs: Long,       // elapsed since session start
        val fps: Float,
        val frameTimeMs: Float,      // 1000 / fps
        val cpuPercent: Int?,        // nullable if unavailable
        val gpuLoadPercent: Int?,    // nullable if unavailable
        val vramUsedBytes: Long?,    // nullable if unavailable
        val ramPercent: Int?
    )

    data class Summary(
        val avgFps: Float,
        val minFps: Int,
        val maxFps: Int,
        val low1Fps: Float,
        val low01Fps: Float,
        val avgFrameTimeMs: Float,
        val avgCpuPercent: Int?,
        val avgGpuLoadPercent: Int?,
        val avgVramUsedBytes: Long?,
        val durationSec: Float,
        val readingCount: Int
    ) {
        /** Serialize to JSON using [org.json.JSONObject] (available on Android). */
        fun toJson(): String {
            val obj = org.json.JSONObject()
            obj.put("avgFps", avgFps.toDouble())
            obj.put("minFps", minFps)
            obj.put("maxFps", maxFps)
            obj.put("low1Fps", low1Fps.toDouble())
            obj.put("low01Fps", low01Fps.toDouble())
            obj.put("avgFrameTimeMs", avgFrameTimeMs.toDouble())
            obj.put("avgCpuPercent", avgCpuPercent ?: org.json.JSONObject.NULL)
            obj.put("avgGpuLoadPercent", avgGpuLoadPercent ?: org.json.JSONObject.NULL)
            obj.put("avgVramUsedBytes", avgVramUsedBytes ?: org.json.JSONObject.NULL)
            obj.put("durationSec", durationSec.toDouble())
            obj.put("readingCount", readingCount)
            return obj.toString()
        }

        companion object {
            /** Deserialize from JSON, returning null on any parse failure. */
            fun fromJson(json: String): Summary? {
                return try {
                    val obj = org.json.JSONObject(json)
                    Summary(
                        avgFps = obj.getDouble("avgFps").toFloat(),
                        minFps = obj.getInt("minFps"),
                        maxFps = obj.getInt("maxFps"),
                        low1Fps = obj.getDouble("low1Fps").toFloat(),
                        low01Fps = obj.getDouble("low01Fps").toFloat(),
                        avgFrameTimeMs = obj.getDouble("avgFrameTimeMs").toFloat(),
                        avgCpuPercent = if (obj.isNull("avgCpuPercent")) null else obj.getInt("avgCpuPercent"),
                        avgGpuLoadPercent = if (obj.isNull("avgGpuLoadPercent")) null else obj.getInt("avgGpuLoadPercent"),
                        avgVramUsedBytes = if (obj.isNull("avgVramUsedBytes")) null else obj.getLong("avgVramUsedBytes"),
                        durationSec = obj.getDouble("durationSec").toFloat(),
                        readingCount = obj.getInt("readingCount")
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    val readings = CopyOnWriteArrayList<Reading>()
    var summary: Summary? = null
        internal set

    private var job: Job? = null
    private var startTimeMs: Long = 0L

    val isRunning: Boolean get() = job?.isActive == true

    val progress: Float
        get() {
            if (!isRunning) return 1f
            val elapsed = System.currentTimeMillis() - startTimeMs
            return (elapsed.toFloat() / DURATION_MS).coerceIn(0f, 1f)
        }

    val elapsedSeconds: Float
        get() {
            if (!isRunning) return 0f
            return ((System.currentTimeMillis() - startTimeMs) / 1000f).coerceAtMost(DURATION_MS / 1000f)
        }

    /**
     * Start the profiling session. Must be called from a CoroutineScope.
     */
    fun start(scope: CoroutineScope) {
        if (isRunning) return
        readings.clear()
        summary = null
        fpsCounter.reset()
        startTimeMs = System.currentTimeMillis()

        job = scope.launch(Dispatchers.IO) {
            while (isActive && (System.currentTimeMillis() - startTimeMs) < DURATION_MS) {
                val elapsed = System.currentTimeMillis() - startTimeMs
                val fps = fpsCounter.currentFPS
                val frametimeLows = fpsCounter.frametimeLows
                val cpu = metrics.cpuUsagePercent
                val gpuLoad = metrics.gpuUsagePercent
                val vram = metrics.vramUsedBytes
                val ram = metrics.ramPercent

                readings.add(
                    Reading(
                        timestampMs = elapsed,
                        fps = fps,
                        frameTimeMs = if (fps > 0) 1000f / fps else 0f,
                        cpuPercent = cpu,
                        gpuLoadPercent = gpuLoad,
                        vramUsedBytes = vram,
                        ramPercent = ram.toInt()
                    )
                )

                delay(POLL_INTERVAL_MS)
            }

            // Session complete — compute summary
            computeSummary()
        }
    }

    /**
     * Stop the session early (called from "Stop Early" button).
     */
    fun stop() {
        job?.cancel()
        job = null
        computeSummary()
    }

    private fun computeSummary() {
        if (readings.isEmpty()) return

        val fpsValues = readings.map { it.fps }.filter { it > 0 }
        val cpuValues = readings.mapNotNull { it.cpuPercent }
        val gpuValues = readings.mapNotNull { it.gpuLoadPercent }
        val vramValues = readings.mapNotNull { it.vramUsedBytes }

        val avgFps = if (fpsValues.isNotEmpty()) fpsValues.average().toFloat() else 0f
        val minFps = fpsValues.minOrNull()?.toInt() ?: 0
        val maxFps = fpsValues.maxOrNull()?.toInt() ?: 0
        val avgFrameTime = if (avgFps > 0) 1000f / avgFps else 0f

        val frametimeLows = fpsCounter.frametimeLows
        val durationMs = readings.lastOrNull()?.timestampMs ?: 0L

        summary = Summary(
            avgFps = avgFps,
            minFps = minFps,
            maxFps = maxFps,
            low1Fps = frametimeLows.low1Fps,
            low01Fps = frametimeLows.low01Fps,
            avgFrameTimeMs = avgFrameTime,
            avgCpuPercent = if (cpuValues.isNotEmpty()) cpuValues.average().toInt() else null,
            avgGpuLoadPercent = if (gpuValues.isNotEmpty()) gpuValues.average().toInt() else null,
            avgVramUsedBytes = if (vramValues.isNotEmpty()) vramValues.average().toLong() else null,
            durationSec = durationMs / 1000f,
            readingCount = readings.size
        )
    }

    // ── Persistent cache ──────────────────────────────────────────────

    fun saveToPrefs(context: Context, containerId: Int) {
        val s = summary ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_TIMESTAMP, System.currentTimeMillis())
            .putInt(KEY_LAST_CONTAINER, containerId)
            .putString(KEY_LAST_SUMMARY, s.toJson())
            .apply()
    }

    /**
     * Write a structured JSON log file for this profiling session.
     * Stored in <app-files>/benchmark_logs/<timestamp>.json
     * Returns the file path on success, null on failure.
     */
    fun saveSessionLog(context: Context, containerId: Int, exePath: String?): String? {
        val s = summary ?: return null
        try {
            val logDir = java.io.File(context.filesDir, "benchmark_logs")
            if (!logDir.exists()) logDir.mkdirs()

            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
                .format(System.currentTimeMillis())

            val readingsArray = org.json.JSONArray()
            for (r in readings) {
                readingsArray.put(org.json.JSONObject().apply {
                    put("timestampMs", r.timestampMs)
                    put("fps", r.fps)
                    put("cpuPercent", r.cpuPercent ?: org.json.JSONObject.NULL)
                    put("gpuLoadPercent", r.gpuLoadPercent ?: org.json.JSONObject.NULL)
                    put("vramUsedBytes", r.vramUsedBytes ?: org.json.JSONObject.NULL)
                })
            }

            val logObj = org.json.JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("containerId", containerId)
                put("exePath", exePath ?: org.json.JSONObject.NULL)
                put("durationSeconds", s.durationSec)
                put("totalReadings", s.readingCount)
                put("summary", org.json.JSONObject().apply {
                    put("avgFps", s.avgFps)
                    put("percentile1LowFps", s.low1Fps)
                    put("percentile01LowFps", s.low01Fps)
                    put("avgCpu", s.avgCpuPercent)
                    put("avgGpuLoad", s.avgGpuLoadPercent)
                })
                put("readings", readingsArray)
            }

            val file = java.io.File(logDir, "$timestamp.json")
            file.writeText(logObj.toString(2))
            return file.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("ProfilerSession", "Failed to save session log", e)
            return null
        }
    }

    fun getLastSummary(context: Context, containerId: Int): Pair<Long, Summary>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
        val storedContainer = prefs.getInt(KEY_LAST_CONTAINER, -1)
        if (storedContainer != containerId || ts == 0L) return null

        val raw = prefs.getString(KEY_LAST_SUMMARY, null) ?: return null
        val summary = Summary.fromJson(raw) ?: return null
        return ts to summary
    }
}
