package com.winlator.star.container

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.winlator.star.core.Callback
import com.winlator.star.core.HttpUtils
import org.json.JSONObject

/**
 * Background check for new VEGAS releases on app start.
 *
 * Polls the GitHub releases API for isygold/vegas-releases, compares the latest
 * non-prerelease tag against the locally installed version stored in SharedPreferences.
 *
 * Skip logic: each version tag gets a skip counter (max 2 chances). After 2 skips
 * the dialog never appears for that version again.
 */
object VegasUpdateChecker {

    private const val TAG = "VegasUpdateChecker"
    private const val RELEASES_URL = "https://api.github.com/repos/isygold/vegas-releases/releases/latest"
    private const val PREFS_NAME = "vegas_update_check"
    private const val KEY_SKIP_PREFIX = "skip_"
    private const val KEY_INSTALLED_VERSION = "installed_vegas_version"
    private const val MAX_SKIPS = 2

    data class ReleaseInfo(
        val tag: String,
        val body: String,
        val isPrerelease: Boolean
    )

    /**
     * Save the currently applied VEGAS version so the checker can compare.
     * Call this from XServerDisplayActivity after the VEGAS wrapper is resolved.
     */
    fun saveInstalledVersion(context: Context, version: String) {
        prefs(context).edit()
            .putString(KEY_INSTALLED_VERSION, version)
            .apply()
    }

    /**
     * Get the locally installed VEGAS version, or null if never set.
     */
    fun getInstalledVersion(context: Context): String? {
        return prefs(context).getString(KEY_INSTALLED_VERSION, null)
    }

    /**
     * Check for a VEGAS update in the background.
     * Calls [onResult] with a ReleaseInfo if an update is available and not skipped,
     * or null if up-to-date, skipped, or on error.
     */
    fun checkForUpdate(context: Context, onResult: Callback<ReleaseInfo?>) {
        HttpUtils.download(RELEASES_URL) { body ->
            if (body == null) {
                Log.d(TAG, "Failed to fetch releases")
                onResult.call(null)
                return@download
            }

            try {
                val json = JSONObject(body)
                val latestTag = json.optString("tag_name", "")
                val latestBody = json.optString("body", "")
                val isPrerelease = json.optBoolean("prerelease", false)

                if (latestTag.isEmpty()) {
                    onResult.call(null)
                    return@download
                }

                val installedVersion = getInstalledVersion(context)

                // Compare: strip leading 'v' and "vegas-" prefix for clean comparison
                val cleanLatest = normalizeTag(latestTag)
                val cleanInstalled = normalizeTag(installedVersion ?: "")

                if (cleanInstalled.isEmpty() || cleanLatest == cleanInstalled) {
                    // Same version or no installed version tracked — nothing to notify
                    onResult.call(null)
                    return@download
                }

                if (cleanLatest.compareTo(cleanInstalled) <= 0) {
                    // Installed is same or newer — nothing to notify
                    onResult.call(null)
                    return@download
                }

                // Check skip count
                val skipCount = getSkipCount(context, latestTag)
                if (skipCount >= MAX_SKIPS) {
                    Log.d(TAG, "Version $latestTag skipped $skipCount times — suppressing")
                    onResult.call(null)
                    return@download
                }

                // Update available and not fully skipped
                onResult.call(ReleaseInfo(latestTag, latestBody, isPrerelease))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse release", e)
                onResult.call(null)
            }
        }
    }

    /**
     * Record that the user skipped this version. Increments the skip counter.
     */
    fun recordSkip(context: Context, tag: String) {
        val current = getSkipCount(context, tag)
        prefs(context).edit()
            .putInt(KEY_SKIP_PREFIX + tag, current + 1)
            .apply()
        Log.d(TAG, "Skip recorded for $tag (count: ${current + 1})")
    }

    /**
     * Normalize a tag for comparison: strip leading 'v', strip "vegas-" prefix.
     * "v2.7.3-vegas" → "2.7.3"
     * "2.4.1-3137660" → "2.4.1-3137660"
     */
    private fun normalizeTag(tag: String): String {
        var t = tag.trim()
        if (t.startsWith("v")) t = t.substring(1)
        if (t.startsWith("vegas-")) t = t.substring("vegas-".length)
        return t
    }

    private fun getSkipCount(context: Context, tag: String): Int {
        return prefs(context).getInt(KEY_SKIP_PREFIX + tag, 0)
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
