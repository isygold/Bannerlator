package com.winlator.star.communityconfigs

import android.util.Log
import com.winlator.star.container.Shortcut

/**
 * PHASE 2 apply engine — translate a community config → SURGICALLY merge it into a game's shortcut
 * {@code [Extra Data]} (written per-field via {@code Shortcut.putExtra(name, value)}), never a
 * wholesale overwrite.
 *
 * Key rules (encoded as constants so they can't drift):
 *  - Almost everything is per-game overridable via [OVERRIDABLE_KEYS].
 *  - EXCEPTION: {@code wineVersion}/Proton is CONTAINER-ONLY — advisory only, never written here.
 *  - {@code dxwrapperConfig} ("k=v," list) and {@code graphicsDriverConfig} ("k=v;" list) get a
 *    SUB-FIELD merge: replace ONLY the config-named versions, PRESERVE every other sub-key the user
 *    already has (BCn settings, vulkanVersion, gpuName, HUD, fps, renderer, …).
 *  - Component versions are resolved against what's installed under {@code files/contents/*},
 *    minor-version-aware; prefer an installed-compatible version, flag installs for material diffs.
 *
 * Reference translate+validate logic: tools/translate.py in The412Banner/bannerlator-game-configs.
 */
object CommunityConfigApply {

    private const val TAG = "CommunityConfigs"

    /** Shortcut [Extra Data] keys a community config may set directly via putExtra(). */
    val OVERRIDABLE_KEYS: List<String> = listOf(
        "dxwrapper", "dxwrapperConfig", "graphicsDriver", "graphicsDriverConfig",
        "emulator", "fexcoreVersion", "fexcorePreset", "box64Version", "box64Preset",
        "audioDriver", "cpuList", "envVars", "screenSize", "wincomponents", "renderer",
        "fpsLimiter", "frameGenEngine", "sfCompatMode", "inputType", "execArgs",
    )

    /** Advisory only — surfaced to the user but NEVER written to the shortcut (container-scoped). */
    val ADVISORY_ONLY_KEYS: List<String> = listOf("wineVersion")

    /** Composite-config sub-keys that a merge is allowed to replace; all others are preserved. */
    val MERGE_SUBKEYS: List<String> = listOf("version", "vkd3dVersion", "async", "graphics")

    /**
     * The reviewable, human-readable outcome of an apply — what actually changed, what needs an
     * install, and advisory-only notes (Proton). [ok] is false only on an outright fetch/translate
     * failure; an apply that changed nothing but produced advisories is still [ok].
     */
    data class ConfigApplyResult(
        val ok: Boolean,
        val message: String,
        val changed: List<String> = emptyList(),
        val advisories: List<String> = emptyList(),
    )

    /**
     * Surgically merge [config] into [shortcut] and persist. Resolves component versions against
     * [installed]. Returns a summary of exactly what changed plus install/Proton advisories. Safe to
     * call off the main thread (blocking file write via {@code saveData()}).
     *
     * @param containerWineVersion the container's Proton/Wine version, for the container-only advisory.
     */
    fun apply(
        shortcut: Shortcut,
        config: ShortcutConfig,
        installed: InstalledComponents,
        containerWineVersion: String?,
    ): ConfigApplyResult {
        val changed = ArrayList<String>()
        val advisories = ArrayList<String>()

        // FEXCore is coupled to the emulator scalar: only switch to fexcore if a compatible build is
        // installed, otherwise we'd point the shortcut at a translator that isn't there.
        val wantedEmulator = config.scalars["emulator"]
        val wantedFex = config.scalars["fexcoreVersion"]
        var writeEmulatorFex = true
        if (wantedEmulator == "fexcore" && !wantedFex.isNullOrBlank()) {
            when (val r = installed.resolve("FEXCore", wantedFex, shortcut.getExtra("fexcoreVersion"))) {
                is InstalledComponents.Resolution.Match -> {
                    setScalar(shortcut, "emulator", "fexcore", changed)
                    val token = r.token ?: wantedFex
                    setScalar(shortcut, "fexcoreVersion", token, changed)
                }
                InstalledComponents.Resolution.Missing -> {
                    writeEmulatorFex = false
                    advisories.add("config uses FEXCore $wantedFex — not installed; install it to switch x86 translator.")
                }
            }
        }

        // Plain scalars — direct putExtra, preserving everything else. (emulator/fexcoreVersion handled above.)
        for ((key, value) in config.scalars) {
            if (key == "emulator" || key == "fexcoreVersion") {
                if (!writeEmulatorFex) continue
                if (key == "emulator" && value != "fexcore") setScalar(shortcut, key, value, changed)
                continue
            }
            setScalar(shortcut, key, value, changed)
        }

        // dxwrapperConfig — comma k=v list; merge ONLY version / vkd3dVersion / async, preserve the rest.
        val dxwCurrent = currentOrDefault(shortcut, "dxwrapperConfig", shortcut.container?.getDXWrapperConfig())
        val dxwUpdates = LinkedHashMap<String, String>()
        config.dxwrapperConfig["version"]?.let { wanted ->
            resolveInto("DXVK", "DXVK", wanted, subValue(dxwCurrent, ",", "version"), installed, advisories)
                ?.let { dxwUpdates["version"] = it }
        }
        config.dxwrapperConfig["vkd3dVersion"]?.let { wanted ->
            resolveInto("VKD3D", "VKD3D", wanted, subValue(dxwCurrent, ",", "vkd3dVersion"), installed, advisories)
                ?.let { dxwUpdates["vkd3dVersion"] = it }
        }
        config.dxwrapperConfig["async"]?.let { dxwUpdates["async"] = it } // flag, always safe
        if (dxwUpdates.isNotEmpty()) {
            val merged = mergeList(dxwCurrent, dxwUpdates, ",")
            if (merged != dxwCurrent) {
                shortcut.putExtra("dxwrapperConfig", merged)
                dxwUpdates.forEach { (k, v) -> changed.add("dxwrapperConfig.$k → $v") }
            }
        }

        // graphicsDriverConfig — semicolon k=v list; merge ONLY the driver version, preserve the rest.
        val gdcCurrent = currentOrDefault(shortcut, "graphicsDriverConfig", shortcut.container?.getGraphicsDriverConfig())
        config.graphicsDriverConfig["version"]?.let { wanted ->
            val resolved = resolveInto("Turnip", "adrenotools", wanted, subValue(gdcCurrent, ";", "version"), installed, advisories)
            if (resolved != null) {
                val merged = mergeList(gdcCurrent, linkedMapOf("version" to resolved), ";")
                if (merged != gdcCurrent) {
                    shortcut.putExtra("graphicsDriverConfig", merged)
                    changed.add("graphicsDriverConfig.version → $resolved")
                }
            }
        }

        // Proton / wineVersion — container-only, advisory only.
        config.advisories["wineVersion"]?.let { proton ->
            val have = containerWineVersion?.takeIf { it.isNotBlank() } ?: "your container's"
            advisories.add("config used $proton (container-only) — your container is $have; change it in the container editor if needed.")
        }

        if (changed.isNotEmpty()) {
            try {
                shortcut.saveData()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist shortcut after apply", e)
                return ConfigApplyResult(false, "Failed to save shortcut: ${e.message ?: e.javaClass.simpleName}", changed, advisories)
            }
        }

        val message = when {
            changed.isEmpty() && advisories.isEmpty() ->
                "Already aligned — your shortcut matches this config; nothing to change."
            changed.isEmpty() ->
                "Nothing changed — this config needs components you don't have installed yet."
            else -> "Applied ${changed.size} change${if (changed.size == 1) "" else "s"} to \"${shortcut.name}\"."
        }
        return ConfigApplyResult(true, message, changed, advisories)
    }

    /** Set a scalar extra, recording the change only when it actually differs from the current value. */
    private fun setScalar(shortcut: Shortcut, key: String, value: String, changed: MutableList<String>) {
        if (shortcut.getExtra(key) == value) return
        shortcut.putExtra(key, value)
        changed.add("$key → $value")
    }

    /**
     * Resolve a requested component version; return the value to write into the sub-field, or null to
     * skip (either already aligned, or not installed → an advisory is added).
     */
    private fun resolveInto(
        label: String,
        installedType: String,
        wanted: String,
        current: String?,
        installed: InstalledComponents,
        advisories: MutableList<String>,
    ): String? = when (val r = installed.resolve(installedType, wanted, current)) {
        is InstalledComponents.Resolution.Match -> r.token // null = already aligned
        InstalledComponents.Resolution.Missing -> {
            val have = current?.takeIf { it.isNotBlank() }?.let { " you have $it —" } ?: ""
            advisories.add("config wants $label $wanted;$have install it to honor.")
            null
        }
    }

    /** Read a shortcut extra, falling back to the container default when the shortcut has no override. */
    private fun currentOrDefault(shortcut: Shortcut, key: String, default: String?): String {
        val v = shortcut.getExtra(key)
        return if (v.isNotBlank()) v else (default ?: "")
    }

    /** Value of one sub-key inside a delimited {@code k=v} list, or null when absent. */
    private fun subValue(list: String, delim: String, key: String): String? {
        if (list.isBlank()) return null
        for (part in list.split(delim)) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (part.substring(0, eq).trim() == key) return part.substring(eq + 1)
        }
        return null
    }

    /**
     * Merge [updates] into a delimited {@code k=v} list, REPLACING those keys in place and APPENDING
     * any new ones, while preserving order and every other sub-field the user already has.
     */
    private fun mergeList(list: String, updates: Map<String, String>, delim: String): String {
        val order = ArrayList<String>()
        val map = LinkedHashMap<String, String>()
        if (list.isNotBlank()) {
            for (part in list.split(delim)) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq < 0) { order.add(part); map[part] = " RAW"; continue }
                val k = part.substring(0, eq)
                order.add(k)
                map[k] = part.substring(eq + 1)
            }
        }
        for ((k, v) in updates) {
            if (!map.containsKey(k)) order.add(k)
            map[k] = v
        }
        return order.joinToString(delim) { k ->
            val v = map[k]
            if (v == " RAW") k else "$k=$v"
        }
    }
}
