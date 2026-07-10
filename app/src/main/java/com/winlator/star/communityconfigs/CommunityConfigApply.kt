package com.winlator.star.communityconfigs

import android.util.Log
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.ContentProfile

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
 *  - Component versions are resolved against what's installed under the files/contents dirs,
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
     * A component the config wants but that isn't installed AND is installable through the app's
     * single-type download sheet (DXVK / VKD3D / FEXCore). Carries enough to (a) open the right sheet
     * and (b) SURGICALLY write the resolved version sub-field back after the user installs a build.
     * Proton / Turnip are NOT here — they stay plain advisory strings (not installable via the sheet).
     *
     * @param type    which single-type download sheet to open.
     * @param label   the same human-readable advisory line shown when there's no button.
     * @param wanted  the version the config requested (re-resolved against installs after download).
     * @param current the shortcut's current sub-field value, or null when it has none.
     */
    data class MissingComponent(
        val type: ContentProfile.ContentType,
        val label: String,
        val wanted: String,
        val current: String?,
    )

    /**
     * The reviewable, human-readable outcome of an apply — what actually changed, what needs an
     * install (structured [missingComponents], each installable via the download sheet), and
     * advisory-only notes ([advisories]: Proton / Turnip, plain text, no button). [ok] is false only
     * on an outright fetch/translate failure; an apply that changed nothing but produced advisories is
     * still [ok].
     */
    data class ConfigApplyResult(
        val ok: Boolean,
        val message: String,
        val changed: List<String> = emptyList(),
        val missingComponents: List<MissingComponent> = emptyList(),
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
        val missing = ArrayList<MissingComponent>()

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
                    missing.add(
                        MissingComponent(
                            ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                            "config uses FEXCore $wantedFex — not installed; install it to switch x86 translator.",
                            wantedFex,
                            shortcut.getExtra("fexcoreVersion").takeIf { it.isNotBlank() },
                        )
                    )
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
            resolveInto(
                "DXVK", "DXVK", wanted, subValue(dxwCurrent, ",", "version"), installed, advisories,
                missing, ContentProfile.ContentType.CONTENT_TYPE_DXVK,
            )?.let { dxwUpdates["version"] = it }
        }
        config.dxwrapperConfig["vkd3dVersion"]?.let { wanted ->
            resolveInto(
                "VKD3D", "VKD3D", wanted, subValue(dxwCurrent, ",", "vkd3dVersion"), installed, advisories,
                missing, ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
            )?.let { dxwUpdates["vkd3dVersion"] = it }
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
                return ConfigApplyResult(
                    false, "Failed to save shortcut: ${e.message ?: e.javaClass.simpleName}",
                    changed, missing, advisories,
                )
            }
        }

        val message = when {
            changed.isEmpty() && advisories.isEmpty() && missing.isEmpty() ->
                "Already aligned — your shortcut matches this config; nothing to change."
            changed.isEmpty() ->
                "Nothing changed — this config needs components you don't have installed yet."
            else -> "Applied ${changed.size} change${if (changed.size == 1) "" else "s"} to \"${shortcut.name}\"."
        }
        return ConfigApplyResult(true, message, changed, missing, advisories)
    }

    /**
     * Post-install fixup: after the user installs a build for a [mc], re-resolve it against [installed]
     * and, if it now resolves, SURGICALLY write the matching version sub-field into [shortcut] using the
     * same merge as [apply] (preserving every other sub-field). Returns true when the shortcut now
     * honors the requested component (whether that needed a write or it was already aligned), false when
     * it still isn't installed. Safe off the main thread (blocking saveData()).
     */
    fun applyResolvedComponent(
        shortcut: Shortcut,
        mc: MissingComponent,
        installed: InstalledComponents,
    ): Boolean {
        val type = installedType(mc.type) ?: return false
        val r = installed.resolve(type, mc.wanted, mc.current)
        if (r !is InstalledComponents.Resolution.Match) return false
        val token = r.token // null = already aligned; resolved, nothing to write.
        if (token != null) {
            when (mc.type) {
                ContentProfile.ContentType.CONTENT_TYPE_DXVK -> {
                    val cur = currentOrDefault(shortcut, "dxwrapperConfig", shortcut.container?.getDXWrapperConfig())
                    shortcut.putExtra("dxwrapperConfig", mergeList(cur, linkedMapOf("version" to token), ","))
                }
                ContentProfile.ContentType.CONTENT_TYPE_VKD3D -> {
                    val cur = currentOrDefault(shortcut, "dxwrapperConfig", shortcut.container?.getDXWrapperConfig())
                    shortcut.putExtra("dxwrapperConfig", mergeList(cur, linkedMapOf("vkd3dVersion" to token), ","))
                }
                ContentProfile.ContentType.CONTENT_TYPE_FEXCORE -> {
                    shortcut.putExtra("emulator", "fexcore")
                    shortcut.putExtra("fexcoreVersion", token)
                }
                else -> return false
            }
            try {
                shortcut.saveData()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist shortcut after component install", e)
                return false
            }
        }
        return true
    }

    /**
     * The smart shortlist for a [MissingComponent]: an [exact] downloadable profile (verName
     * normalizes-equal to the wanted version) when one exists, plus the [closest] few remaining
     * profiles ranked by version distance (same major preferred). Fed by the inline installer on the
     * "Config applied" screen so the user can install without opening the full version menu.
     */
    data class VersionShortlist(
        val exact: ContentProfile?,
        val closest: List<ContentProfile>,
    )

    /**
     * Rank downloadable [profiles] against the requested [wanted] version:
     *  - [VersionShortlist.exact] is the normalize-equal profile, if any.
     *  - [VersionShortlist.closest] is up to [limit] remaining profiles, nearest first by version
     *    distance, with the same major always ahead of a different major.
     *
     * Dotted versions (2.4.1) rank by major/minor/patch distance; date or build-style FEXCore
     * versions (Fex_20260428, FEX-2607) rank by the numeric build's absolute distance. When the
     * wanted version can't be parsed, [closest] is empty (caller falls back to "Browse all versions").
     */
    fun rankVersions(
        wanted: String,
        profiles: List<ContentProfile>,
        limit: Int = 3,
    ): VersionShortlist {
        if (profiles.isEmpty()) return VersionShortlist(null, emptyList())
        val wantNorm = normalizeVer(wanted)
        val exact = profiles.firstOrNull { normalizeVer(it.verName) == wantNorm }
        val wantKey = versionKey(wanted)
        val closest = profiles.asSequence()
            .filter { it !== exact }
            .mapNotNull { p -> versionKey(p.verName)?.let { p to versionDistance(wantKey, it) } }
            .filter { it.second != Long.MAX_VALUE }
            .sortedBy { it.second }
            .map { it.first }
            .take(limit)
            .toList()
        return VersionShortlist(exact, closest)
    }

    /** Loose equality for version strings: case-insensitive, whitespace-stripped, leading "v" dropped. */
    private fun normalizeVer(v: String): String =
        v.trim().lowercase().removePrefix("v").replace(" ", "")

    // Parsed comparison key. First element tags the format (0 = dotted x.y.z, 1 = single build/date
    // number); remaining elements are the numeric components. Null when nothing numeric parses.
    private fun versionKey(v: String): LongArray? {
        val dotted = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(v)
        if (dotted != null) {
            val maj = dotted.groupValues[1].toLongOrNull() ?: 0L
            val min = dotted.groupValues[2].toLongOrNull() ?: 0L
            val pat = dotted.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
            return longArrayOf(0L, maj, min, pat)
        }
        val big = Regex("\\d+").findAll(v).map { it.value }.maxByOrNull { it.length }?.toLongOrNull()
            ?: return null
        return longArrayOf(1L, big)
    }

    // Distance between two version keys; MAX_VALUE when formats differ or either is unparseable.
    private fun versionDistance(a: LongArray?, b: LongArray?): Long {
        if (a == null || b == null || a[0] != b[0]) return Long.MAX_VALUE
        return if (a[0] == 0L) {
            // Weight major far above minor above patch so same-major always sorts ahead.
            Math.abs(a[1] - b[1]) * 1_000_000L + Math.abs(a[2] - b[2]) * 1_000L + Math.abs(a[3] - b[3])
        } else {
            Math.abs(a[1] - b[1])
        }
    }

    /** Map a sheet-installable [ContentProfile.ContentType] to its [InstalledComponents] type key. */
    private fun installedType(type: ContentProfile.ContentType): String? = when (type) {
        ContentProfile.ContentType.CONTENT_TYPE_DXVK -> "DXVK"
        ContentProfile.ContentType.CONTENT_TYPE_VKD3D -> "VKD3D"
        ContentProfile.ContentType.CONTENT_TYPE_FEXCORE -> "FEXCore"
        else -> null
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
        missing: MutableList<MissingComponent>? = null,
        missingType: ContentProfile.ContentType? = null,
    ): String? = when (val r = installed.resolve(installedType, wanted, current)) {
        is InstalledComponents.Resolution.Match -> r.token // null = already aligned
        InstalledComponents.Resolution.Missing -> {
            val have = current?.takeIf { it.isNotBlank() }?.let { " you have $it —" } ?: ""
            val text = "config wants $label $wanted;$have install it to honor."
            // Sheet-installable types get a structured entry (→ Install button); everything else
            // (Turnip / adrenotools) stays a plain advisory line.
            if (missing != null && missingType != null) {
                missing.add(MissingComponent(missingType, text, wanted, current))
            } else {
                advisories.add(text)
            }
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
