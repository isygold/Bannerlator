package com.winlator.star.communityconfigs

import android.content.Context
import android.os.Build
import com.winlator.star.BuildConfig
import com.winlator.star.container.Shortcut
import java.util.Random

/**
 * PHASE 3 step 1 — the thin Android adapter around the pure [ConfigExporter] core. Resolves a
 * shortcut's EFFECTIVE settings (per-game override, else container default — mirroring how
 * {@link CommunityConfigApply}'s {@code currentOrDefault} reads them), fills the device/version
 * provenance, generates a fresh {@code upload_token}, and hands everything to [ConfigExporter.export].
 *
 * Produces the artifact ONLY — no UI, no file I/O, no network. Later Phase-3 steps (share sheet,
 * {@code POST /upload}) consume [ExportResult].
 */
object ShortcutExporter {

    /**
     * The export artifact: the config [json], the BannerHub-shaped [fileName] to store/upload it under,
     * and the sanitized [game] slug the worker keys uploads by.
     */
    data class ExportResult(
        val json: String,
        val fileName: String,
        val game: String,
    )

    /**
     * Build a shareable config from [shortcut]. [context] is used only for the GPU-renderer soc probe.
     * Safe off the main thread (pure reads + string work). Never touches the filesystem or network.
     */
    fun fromShortcut(shortcut: Shortcut, context: Context): ExportResult {
        val container = shortcut.container

        // Effective settings = shortcut override, else the container default (the value the game runs
        // with). Composite k=v lists (dxwrapperConfig / graphicsDriverConfig) and the scalars with a
        // clean container getter fall back; execArgs / inputType are read as-is (shortcut-scoped).
        val effective = LinkedHashMap<String, String>()
        put(effective, "dxwrapperConfig", orDefault(shortcut, "dxwrapperConfig", container?.getDXWrapperConfig()))
        put(effective, "graphicsDriverConfig", orDefault(shortcut, "graphicsDriverConfig", container?.getGraphicsDriverConfig()))
        put(effective, "graphicsDriver", orDefault(shortcut, "graphicsDriver", container?.getGraphicsDriver()))
        put(effective, "emulator", orDefault(shortcut, "emulator", container?.getEmulator()))
        put(effective, "fexcoreVersion", orDefault(shortcut, "fexcoreVersion", container?.getFEXCoreVersion()))
        put(effective, "audioDriver", orDefault(shortcut, "audioDriver", container?.getAudioDriver()))
        put(effective, "wineVersion", orDefault(shortcut, "wineVersion", container?.getWineVersion()))
        put(effective, "envVars", orDefault(shortcut, "envVars", container?.getEnvVars()))
        put(effective, "inputType", shortcut.getExtra("inputType"))
        put(effective, "execArgs", shortcut.getExtra("execArgs"))

        val device = Build.MANUFACTURER + " " + Build.MODEL
        val soc = DeviceIdentity.gpu(context) ?: DeviceIdentity.soc()
        val meta = ConfigExporter.ExportMeta(
            appSource = "bannerlator",
            device = device,
            soc = soc,
            version = BuildConfig.VERSION_NAME,
            uploadToken = newUploadToken(),
        )

        val json = ConfigExporter.export(effective, meta)
        val fileName = ConfigExporter.fileName(
            game = shortcut.name,
            mfr = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = soc ?: "",
            epochSeconds = System.currentTimeMillis() / 1000L,
        )
        // The worker keys uploads by the sanitized game slug (same transform the file name uses).
        val game = shortcut.name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return ExportResult(json, fileName, game)
    }

    /** Variable-length lowercase hex, non-crypto — the exact format BannerHub's exporter emits. */
    private fun newUploadToken(): String =
        java.lang.Long.toHexString(Random().nextLong() and Long.MAX_VALUE)

    /** Shortcut override, or the container default when the shortcut has none. */
    private fun orDefault(shortcut: Shortcut, key: String, default: String?): String {
        val v = shortcut.getExtra(key)
        return if (v.isNotBlank()) v else (default ?: "")
    }

    private fun put(map: MutableMap<String, String>, key: String, value: String) {
        if (value.isNotBlank()) map[key] = value
    }
}
