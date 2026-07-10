package com.winlator.star.communityconfigs

import android.content.Context
import java.io.File

/**
 * Snapshot of what compat components are installed on the device, read straight off the
 * {@code filesDir/contents/{DXVK,VKD3D,Proton,FEXCore,adrenotools}} directories (no manager needed —
 * each install is a sub-dir named {@code <verName>-<verCode>}, or a bare driver id for adrenotools).
 *
 * Used by the apply engine to resolve a config's requested component versions minor-aware: prefer a
 * compatible installed version (no download), otherwise flag an install rather than silently
 * substituting or pointing the shortcut at something that isn't there.
 */
class InstalledComponents private constructor(
    private val byType: Map<String, List<String>>,
) {

    /** Raw install-dir names for a component type (e.g. "dxvk-2.4.1-1624", "proton-arm64ec-3.0.1-0"). */
    fun raw(type: String): List<String> = byType[type].orEmpty()

    /**
     * The version token the shortcut sub-field expects, derived from an install-dir name by stripping
     * the type prefix the runtime re-adds ({@code "dxvk-" + version} / {@code "vkd3d-" + version}) and
     * the trailing numeric {@code -verCode}. e.g. "dxvk-2.4.1-1624" → "2.4.1"; adrenotools ids are
     * used as-is (the graphicsDriverConfig {@code version} IS the driver id).
     */
    fun versionTokens(type: String): List<String> = raw(type).map { deriveToken(type, it) }

    /**
     * Resolve one requested version against what's installed.
     *  - [Resolution.Match] — a minor-compatible version is installed; [Resolution.Match.token] is the
     *    value to write into the sub-field (or null when the current value is already aligned).
     *  - [Resolution.Missing] — nothing compatible installed; caller should advise an install and skip.
     */
    fun resolve(type: String, wanted: String, currentValue: String?): Resolution {
        val wantMm = majorMinor(wanted)
        val tokens = versionTokens(type)

        // Current value already compatible → nothing to change (the common "already aligned" case).
        if (!currentValue.isNullOrBlank()) {
            if (currentValue.equals(wanted, ignoreCase = true)) return Resolution.Match(null)
            if (wantMm != null && majorMinor(currentValue) == wantMm &&
                tokens.any { majorMinor(it) == wantMm }
            ) {
                return Resolution.Match(null)
            }
        }

        // Prefer an exact installed token, else a minor-compatible one.
        val exact = tokens.firstOrNull { it.equals(wanted, ignoreCase = true) }
        if (exact != null) return Resolution.Match(exact)
        if (wantMm != null) {
            val compatible = tokens.firstOrNull { majorMinor(it) == wantMm }
            if (compatible != null) return Resolution.Match(compatible)
        }
        // FEX / date-stamped versions have no x.y — fall back to substring containment.
        val contains = tokens.firstOrNull {
            it.contains(wanted, ignoreCase = true) || wanted.contains(it, ignoreCase = true)
        }
        if (contains != null) return Resolution.Match(if (contains.equals(currentValue, true)) null else contains)

        return Resolution.Missing
    }

    sealed class Resolution {
        /** Compatible version installed. [token] = new sub-field value, or null when already aligned. */
        data class Match(val token: String?) : Resolution()
        /** Nothing compatible installed — advise an install, don't write. */
        object Missing : Resolution()
    }

    companion object {
        // Dir names under filesDir/contents. adrenotools is lower-case (AdrenotoolsManager), the rest
        // match ContentProfile.ContentType.toString().
        val TYPES = listOf("DXVK", "VKD3D", "Proton", "FEXCore", "adrenotools")

        fun read(context: Context): InstalledComponents {
            val contents = File(context.filesDir, "contents")
            val map = HashMap<String, List<String>>()
            for (type in TYPES) {
                val dir = File(contents, type)
                val names = dir.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
                map[type] = names
            }
            return InstalledComponents(map)
        }

        private val PREFIX = mapOf("DXVK" to "dxvk-", "VKD3D" to "vkd3d-")

        /** Strip the runtime-re-added type prefix + trailing "-<verCode>" to recover the sub-field token. */
        private fun deriveToken(type: String, dirName: String): String {
            var t = dirName
            PREFIX[type]?.let { p -> if (t.startsWith(p, ignoreCase = true)) t = t.substring(p.length) }
            // Drop a trailing numeric verCode segment ("-1624", "-0").
            t = t.replace(Regex("-\\d+$"), "")
            return t
        }

        /** "2.7.1-1-async" → "2.7"; null when there's no leading numeric x.y (e.g. FEX date builds). */
        fun majorMinor(v: String?): String? {
            if (v.isNullOrBlank()) return null
            val m = Regex("(\\d+)\\.(\\d+)").find(v) ?: return null
            return "${m.groupValues[1]}.${m.groupValues[2]}"
        }
    }
}
