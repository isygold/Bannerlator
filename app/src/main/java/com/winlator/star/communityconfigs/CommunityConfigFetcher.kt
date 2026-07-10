package com.winlator.star.communityconfigs

import android.util.Log
import com.winlator.star.core.HttpUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

/**
 * Phase 2 — fetch the actual config JSON for a chosen game+device off the BannerHub config repo.
 *
 * The canonical index knows a game's {@code folders} and {@code devices} but NOT the per-config file
 * names, so we list the folder via the GitHub contents API, pick the {@code .json} whose filename
 * tokens best match the chosen device (model + GPU), then fetch its raw {@code download_url}.
 *
 * All calls are blocking — invoke from a background thread. Every failure returns null (offline / not
 * found), which the UI turns into a clear message rather than a crash.
 */
object CommunityConfigFetcher {

    private const val TAG = "CommunityConfigs"
    private const val CONTENTS_API =
        "https://api.github.com/repos/The412Banner/bannerhub-game-configs/contents/configs/"

    /** Result of resolving + fetching a config: the parsed JSON plus the file it came from. */
    data class Fetched(val json: JSONObject, val fileName: String)

    /**
     * Resolve and download the best-matching config for [device] across the game's [folders].
     * Returns null when no folder lists / no file could be fetched.
     */
    fun fetchForDevice(game: CanonicalGame, device: CanonicalDevice): Fetched? {
        val deviceTokens = tokenizeDevice(device)
        for (folder in game.folders) {
            val listing = listFolder(folder) ?: continue
            val best = pickBestFile(listing, deviceTokens) ?: continue
            val body = downloadRaw(best.downloadUrl) ?: continue
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                Log.w(TAG, "Config JSON parse failed for ${best.name}", e)
                continue
            }
            return Fetched(json, best.name)
        }
        return null
    }

    /**
     * Download ONE exact config file by name (the per-uploaded-config path). Goes through the worker's
     * `GET /download?game=&file=` — which returns the raw config JSON (and bumps the sampled download
     * count) — so it lands on the same file the `/list` entry named, with no GitHub-contents walking.
     * Returns null when the fetch fails or the body isn't valid JSON (offline / removed).
     */
    fun fetchForFile(workerGame: String, filename: String): Fetched? {
        val body = CommunityConfigWorker.download(workerGame, filename) ?: return null
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "Config JSON parse failed for $filename", e)
            return null
        }
        return Fetched(json, filename)
    }

    private data class RepoFile(val name: String, val downloadUrl: String)

    /** GET the GitHub contents listing for one config folder → its {@code .json} files. */
    private fun listFolder(folder: String): List<RepoFile>? {
        val body = downloadRaw(CONTENTS_API + encodePath(folder)) ?: return null
        return try {
            val arr = JSONArray(body)
            val out = ArrayList<RepoFile>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                val url = o.optString("download_url", "")
                if (name.endsWith(".json", ignoreCase = true) && url.isNotBlank()) {
                    out.add(RepoFile(name, url))
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Folder listing parse failed for $folder", e)
            null
        }
    }

    /** Pick the file whose name shares the most tokens with the device (model + GPU). */
    private fun pickBestFile(files: List<RepoFile>, deviceTokens: Set<String>): RepoFile? {
        if (files.isEmpty()) return null
        if (deviceTokens.isEmpty()) return files.first()
        return files.maxByOrNull { f ->
            val fileTokens = tokenize(f.name)
            deviceTokens.count { it in fileTokens }
        }
    }

    private fun tokenizeDevice(device: CanonicalDevice): Set<String> =
        tokenize("${device.model} ${device.gpu}")

    /** Lower-case alphanumeric tokens (length ≥ 2) — filenames encode "-Xiaomi-2312DRA50G-Adreno__TM__710-". */
    private fun tokenize(raw: String): Set<String> =
        raw.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()

    private fun encodePath(folder: String): String =
        folder.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** Blocking GET via HttpUtils' async executor bridged to a synchronous result. */
    private fun downloadRaw(url: String): String? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        HttpUtils.download(url) { body ->
            holder[0] = body
            latch.countDown()
        }
        return try {
            latch.await()
            holder[0]
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}
