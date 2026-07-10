package com.winlator.star.communityconfigs

import android.content.Context
import android.util.Log
import com.winlator.star.core.HttpUtils
import org.json.JSONObject
import java.io.File

/**
 * Offline-first source of the community-config index (`games_canonical.json`).
 *
 * Load order, never blocking the UI on the network:
 *  1. In-memory parsed cache (instant on repeat opens).
 *  2. On-disk cache at {@code filesDir/community_configs/games_canonical.json} (parsed once).
 *  3. Network fetch via [HttpUtils] — only when there is no cache at all. When a cache exists but
 *     is older than [MAX_AGE_MS] it is served immediately and a refresh is kicked off in the
 *     background (via HttpUtils' own executor), so the next open picks up the fresher copy.
 *
 * If the index is unavailable (first run + offline) callers get an empty list, which the UI turns
 * into a clean empty state — never a crash.
 */
class CommunityConfigRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, "community_configs")
    private val indexFile = File(cacheDir, INDEX_FILE_NAME)

    @Volatile private var cachedGames: List<CanonicalGame>? = null

    /**
     * Returns the parsed index, preferring cache. Safe to call from a background thread (it does
     * blocking disk / — on first run only — network IO). Never throws for the offline case.
     */
    fun getGames(): List<CanonicalGame> {
        cachedGames?.let { games ->
            if (isStale()) refreshInBackground()
            return games
        }

        val fromDisk = readIndexFromDisk()
        if (fromDisk != null) {
            cachedGames = fromDisk
            if (isStale()) refreshInBackground()
            return fromDisk
        }

        // No cache on disk — this is the only path that may block on the network.
        val fetched = fetchIndexBlocking()
        if (fetched != null) {
            val parsed = parseAndCache(fetched)
            if (parsed != null) return parsed
        }
        return emptyList()
    }

    private fun isStale(): Boolean {
        val lastModified = indexFile.lastModified()
        return lastModified <= 0L || (System.currentTimeMillis() - lastModified) > MAX_AGE_MS
    }

    private fun readIndexFromDisk(): List<CanonicalGame>? {
        if (!indexFile.isFile) return null
        return try {
            parseIndexJson(indexFile.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached index", e)
            null
        }
    }

    private fun parseAndCache(json: String): List<CanonicalGame>? {
        val parsed = try {
            parseIndexJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse fetched index", e)
            return null
        }
        writeIndexToDisk(json)
        cachedGames = parsed
        return parsed
    }

    private fun parseIndexJson(json: String): List<CanonicalGame> =
        CanonicalGame.parseIndex(JSONObject(json))

    private fun writeIndexToDisk(json: String) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            indexFile.writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist index cache", e)
        }
    }

    /** Blocking GET of the index via HttpUtils' async download bridged to a synchronous result. */
    private fun fetchIndexBlocking(): String? {
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        HttpUtils.download(INDEX_URL) { body ->
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

    /** Fire-and-forget refresh; updates the disk + in-memory cache when it lands. */
    private fun refreshInBackground() {
        HttpUtils.download(INDEX_URL) { body ->
            if (body != null) parseAndCache(body)
        }
    }

    companion object {
        private const val TAG = "CommunityConfigs"
        private const val INDEX_FILE_NAME = "games_canonical.json"
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000 // 24h
        private const val RAW_BASE =
            "https://raw.githubusercontent.com/The412Banner/bannerlator-game-configs/main/"
        const val INDEX_URL = RAW_BASE + INDEX_FILE_NAME
    }
}
