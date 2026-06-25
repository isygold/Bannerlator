package com.winlator.star.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.winlator.star.BuildConfig
import org.json.JSONObject
import java.io.File

/**
 * In-app updater for Bannerlator.
 *
 * Source of truth = the integer [BuildConfig.VERSION_CODE] (gradle `versionCode`).
 * Each STABLE GitHub release attaches an `update.json` asset; because the
 * `releases/latest` redirect only ever resolves to the newest NON-prerelease,
 * nightly/test (prerelease) builds never trip the updater.
 *
 * update.json shape:
 * {
 *   "versionCode": 26,
 *   "versionName": "1.8",
 *   "notes": "What changed…",
 *   "minSupported": 1,
 *   "apk": {
 *     "com.winlator.banner":    "Bannerlator-1.8-standard.apk",
 *     "com.ludashi.benchmark":  "Bannerlator-1.8-ludashi.apk",
 *     "com.tencent.ig":         "Bannerlator-1.8-pubg.apk"
 *   }
 * }
 */
object UpdateManager {
    private const val REPO = "The412Banner/Bannerlator"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val UPDATE_JSON_URL =
        "https://github.com/$REPO/releases/latest/download/update.json"
    private fun assetUrl(name: String) =
        "https://github.com/$REPO/releases/latest/download/$name"

    // Reuse the FileProvider already declared in the manifest.
    private const val FILE_PROVIDER_AUTHORITY = "com.winlator.star.tileprovider"

    private const val PREF_NOTIFY = "update_notify_enabled"
    private const val PREF_SKIP = "update_skip_version"
    private const val PREF_LAST_CHECK = "update_last_check"
    private const val CACHE_NAME = "update_latest.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val apkName: String?,
    ) {
        val apkUrl: String? get() = apkName?.let { assetUrl(it) }
        /** True when the released build is newer than the installed one. */
        val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
    }

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    // ── Preferences ──────────────────────────────────────────────────────
    fun isNotifyEnabled(ctx: Context) = prefs(ctx).getBoolean(PREF_NOTIFY, true)
    fun setNotifyEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(PREF_NOTIFY, enabled).apply()

    fun skippedVersionCode(ctx: Context) = prefs(ctx).getInt(PREF_SKIP, 0)
    fun skipVersion(ctx: Context, versionCode: Int) =
        prefs(ctx).edit().putInt(PREF_SKIP, versionCode).apply()

    fun lastCheck(ctx: Context) = prefs(ctx).getLong(PREF_LAST_CHECK, 0L)

    /** Installed version string for display, e.g. "1.7". */
    fun installedVersionName() = BuildConfig.VERSION_NAME

    // ── Network check ────────────────────────────────────────────────────
    /**
     * Fetch the latest stable metadata off the main thread. [onResult] is
     * invoked on a background thread (callers must marshal to the UI thread).
     * On network failure, falls back to the last cached result so the banner
     * still works offline.
     */
    fun check(ctx: Context, onResult: (UpdateInfo?) -> Unit) {
        HttpUtils.download(UPDATE_JSON_URL) { body ->
            val info = body?.let { parse(it) }
            if (info != null) {
                cache(ctx, body)
                prefs(ctx).edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
                onResult(info)
            } else {
                onResult(loadCached(ctx))
            }
        }
    }

    private fun parse(body: String): UpdateInfo? = try {
        val o = JSONObject(body)
        val apkName = o.optJSONObject("apk")
            ?.optString(BuildConfig.APPLICATION_ID, null)
            ?.takeIf { it.isNotBlank() }
        UpdateInfo(
            versionCode = o.getInt("versionCode"),
            versionName = o.optString("versionName", ""),
            notes = o.optString("notes", ""),
            apkName = apkName,
        )
    } catch (_: Exception) {
        null
    }

    private fun cache(ctx: Context, body: String) = try {
        File(ctx.cacheDir, CACHE_NAME).writeText(body)
    } catch (_: Exception) { }

    private fun loadCached(ctx: Context): UpdateInfo? = try {
        val f = File(ctx.cacheDir, CACHE_NAME)
        if (f.isFile) parse(f.readText()) else null
    } catch (_: Exception) {
        null
    }

    // ── Install-permission (Android 8+) ──────────────────────────────────
    fun canInstallPackages(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ctx.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName),
                )
            )
        }
    }

    // ── Download + install ───────────────────────────────────────────────
    /**
     * Downloads the flavor-matched APK (shows the app's standard
     * [DownloadProgressDialog]) and launches the package installer.
     * If the "install unknown apps" grant is missing, routes the user to that
     * settings screen first and returns without downloading.
     */
    fun downloadAndInstall(activity: Activity, info: UpdateInfo, onDone: (Boolean) -> Unit) {
        val url = info.apkUrl
        if (url == null) {
            AppUtils.showToast(activity, "No download available for this build")
            // Fall back to the release page so the user can grab it manually.
            openReleasesPage(activity)
            onDone(false)
            return
        }
        if (!canInstallPackages(activity)) {
            AppUtils.showToast(activity, "Allow installing apps from Bannerlator, then tap Update again")
            requestInstallPermission(activity)
            onDone(false)
            return
        }
        val dir = File(activity.externalCacheDir, "update").apply { mkdirs() }
        val apk = File(dir, info.apkName ?: "Bannerlator-update.apk")
        HttpUtils.download(activity, url, apk) { ok ->
            if (ok) install(activity, apk) else AppUtils.showToast(activity, "Update download failed")
            onDone(ok)
        }
    }

    private fun install(activity: Activity, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, FILE_PROVIDER_AUTHORITY, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            AppUtils.showToast(activity, "Could not open installer")
            openReleasesPage(activity)
        }
    }

    fun openReleasesPage(activity: Activity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)))
        } catch (_: Exception) { }
    }
}
