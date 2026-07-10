package com.winlator.star.communityconfigs

/**
 * PHASE 2 DATA MODEL — apply a community config to a shortcut. NOT WIRED YET.
 *
 * This file only fixes the shapes so the write can be dropped in later; the Apply button in the UI
 * is disabled until then. Apply = translate a BannerHub config → SURGICALLY merge into the game's
 * shortcut {@code [Extra Data]} (written per-field via {@code Shortcut.putExtra(name, value)}), NOT
 * a wholesale overwrite.
 *
 * Key rules the eventual implementation must respect (encoded as constants below so they can't drift):
 *  - Almost everything is per-game overridable via [OVERRIDABLE_KEYS].
 *  - EXCEPTION: {@code wineVersion}/Proton is CONTAINER-ONLY — advisory only, never written here
 *    (see [ADVISORY_ONLY_KEYS]).
 *  - {@code dxwrapperConfig} ("k=v," list) and {@code graphicsDriverConfig} ("k=v;" list) get a
 *    SUB-FIELD merge: replace ONLY [MERGE_SUBKEYS] (versions), PRESERVE every other subkey the user
 *    already has (BCn settings, vulkanVersion, gpuName, …). See [SubfieldMerge].
 *  - Component versions named by the config are resolved against what's installed under
 *    {@code files/contents/{DXVK,VKD3D,Proton,FEXCore,adrenotools}}, minor-version-aware; prefer an
 *    installed-compatible version, flag installs for material diffs.
 *
 * Reference translate+validate logic: tools/translate.py in The412Banner/bannerlator-game-configs.
 */
object CommunityConfigApply {

    /** Shortcut [Extra Data] keys a community config may set directly via putExtra(). */
    val OVERRIDABLE_KEYS: List<String> = listOf(
        "dxwrapper", "dxwrapperConfig", "graphicsDriver", "graphicsDriverConfig",
        "emulator", "fexcoreVersion", "fexcorePreset", "box64Version", "box64Preset",
        "audioDriver", "cpuList", "envVars", "screenSize", "wincomponents", "renderer",
        "fpsLimiter", "frameGenEngine", "sfCompatMode", "inputType", "execArgs",
    )

    /** Advisory only — surfaced to the user but NEVER written to the shortcut (container-scoped). */
    val ADVISORY_ONLY_KEYS: List<String> = listOf("wineVersion")

    /** Composite-config subkeys that a merge is allowed to replace; all others are preserved. */
    val MERGE_SUBKEYS: List<String> = listOf("version", "vkd3dVersion", "async", "graphics")
}

/** How to touch one shortcut [Extra Data] key. */
enum class ApplyFieldKind {
    /** Replace the whole scalar value (e.g. {@code renderer}, {@code sfCompatMode}). */
    SCALAR,
    /** Merge only [CommunityConfigApply.MERGE_SUBKEYS] into a delimited k=v list, preserving the rest. */
    SUBFIELD_MERGE,
    /** Shown to the user, never written (container-only, e.g. {@code wineVersion}). */
    ADVISORY,
}

/**
 * A single sub-field replacement inside {@code dxwrapperConfig} / {@code graphicsDriverConfig}.
 * @param delimiter "," for dxwrapperConfig, ";" for graphicsDriverConfig.
 */
data class SubfieldMerge(
    val subkey: String,
    val newValue: String,
    val delimiter: String,
)

/** One planned change to a shortcut field. [merges] is populated only for [ApplyFieldKind.SUBFIELD_MERGE]. */
data class ApplyFieldChange(
    val key: String,
    val kind: ApplyFieldKind,
    val currentValue: String?,
    val proposedValue: String?,
    val merges: List<SubfieldMerge> = emptyList(),
    /** Set when the named component version differs materially from what is installed. */
    val installNote: String? = null,
)

/**
 * The full, reviewable set of changes a given community config would make to a shortcut. Phase 2
 * builds this from the fetched config JSON, shows it for confirmation, then applies each change.
 */
data class ConfigApplyPlan(
    val game: CanonicalGame,
    val device: CanonicalDevice,
    val changes: List<ApplyFieldChange>,
    val advisories: List<ApplyFieldChange>,
)
