package com.winlator.star.communityconfigs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip proof for the reverse config translator (Phase 3 step 1):
 * {@code ConfigTranslator.translate(ConfigExporter.export(effective)) == effective} for the supported
 * fields. This is the whole point of the step — the exported {@code pc_*} JSON must read back through
 * the forward path to the same shortcut settings. Pure JVM (no Android types).
 */
class ConfigExporterTest {

    private val meta = ConfigExporter.ExportMeta(
        appSource = "bannerlator",
        device = "Pixel 8",
        soc = "Adreno 750",
        version = "2.6-pre1",
        uploadToken = "deadbeef",
    )

    @Test
    fun roundTrip_supportedFields_reproduceInput() {
        val effective = linkedMapOf(
            // Composite k=v lists carry extra sub-fields the export must ignore (only version tokens matter).
            "dxwrapperConfig" to "version=2.4.1,vkd3dVersion=2.14,async=0,vulkanVersion=1.3",
            "graphicsDriverConfig" to "version=Mesa Turnip v25.0.0;gpuName=Adreno 750",
            "graphicsDriver" to "wrapper-bcn_layer",
            "emulator" to "fexcore",
            "fexcoreVersion" to "Fex_20260428",
            "audioDriver" to "pulseaudio",
            "inputType" to "1",
            "envVars" to "DXVK_HUD=fps",
            "execArgs" to "-dx11",
        )

        val json = ConfigExporter.export(effective, meta)
        val config = ConfigTranslator.translate(JSONObject(json))

        assertEquals("2.4.1", config.dxwrapperConfig["version"])
        assertEquals("2.14", config.dxwrapperConfig["vkd3dVersion"])
        assertEquals("Mesa Turnip v25.0.0", config.graphicsDriverConfig["version"])
        assertEquals("wrapper-bcn_layer", config.scalars["graphicsDriver"])
        assertEquals("fexcore", config.scalars["emulator"])
        assertEquals("Fex_20260428", config.scalars["fexcoreVersion"])
        assertEquals("pulseaudio", config.scalars["audioDriver"])
        assertEquals("1", config.scalars["inputType"])
        assertEquals("DXVK_HUD=fps", config.scalars["envVars"])
        assertEquals("-dx11", config.scalars["execArgs"])
    }

    @Test
    fun export_meta_isBannerlatorNamespaced() {
        val root = JSONObject(ConfigExporter.export(emptyMap(), meta))
        val m = root.getJSONObject("meta")
        assertEquals("bannerlator", m.getString("app_source"))
        assertEquals("deadbeef", m.getString("upload_token"))
        assertEquals("Adreno 750", m.getString("soc"))
        assertEquals("2.6-pre1", m.getString("bh_version"))
        // Nothing supplied → no settings, no components.
        assertEquals(0, m.getInt("settings_count"))
        assertEquals(0, m.getInt("components_count"))
    }

    @Test
    fun export_partialInput_emitsOnlyPresentKeys() {
        val effective = mapOf("dxwrapperConfig" to "version=2.3.1")
        val root = JSONObject(ConfigExporter.export(effective, meta))
        val settings = root.getJSONObject("settings")

        assertTrue(settings.has("pc_ls_DXVK"))
        assertFalse(settings.has("pc_ls_VK3k"))
        assertFalse(settings.has("pc_set_constant_95"))
        assertFalse(settings.has("pc_ls_GPU_DRIVER_"))
        assertFalse(settings.has("pc_ls_AUDIO_DRIVER"))
        assertFalse(settings.has("pc_ls_boot_option"))
        assertEquals(1, root.getJSONObject("meta").getInt("settings_count"))
        assertEquals(1, root.getJSONObject("meta").getInt("components_count"))

        // The emitted value is a JSON STRING with a name (BannerHub shape), read back by translate.
        val dxvk = JSONObject(settings.getString("pc_ls_DXVK")).getString("name")
        assertEquals("2.3.1", dxvk)
    }

    @Test
    fun export_graphicsWrapper_isPlainStringNotComponent() {
        val effective = mapOf("graphicsDriver" to "wrapper-bcn_layer")
        val root = JSONObject(ConfigExporter.export(effective, meta))
        val settings = root.getJSONObject("settings")

        // Emitted as a PLAIN scalar string — NOT a {"name":…} component wrapper.
        assertEquals("wrapper-bcn_layer", settings.getString("pc_ls_GRAPHICS_WRAPPER"))
        assertNull(settings.opt("pc_ls_GRAPHICS_WRAPPER") as? JSONObject)
        // It counts toward settings_count but adds NO components[] entry.
        assertEquals(1, root.getJSONObject("meta").getInt("settings_count"))
        assertEquals(0, root.getJSONObject("meta").getInt("components_count"))
    }

    @Test
    fun export_emulatorNotFex_omitsFexComponent() {
        val effective = mapOf("emulator" to "box64", "fexcoreVersion" to "Fex_20260428")
        val root = JSONObject(ConfigExporter.export(effective, meta))
        assertFalse(root.getJSONObject("settings").has("pc_set_constant_95"))
        assertEquals(0, root.getJSONObject("meta").getInt("components_count"))
    }

    @Test
    fun fileName_matchesBannerHubShape() {
        val name = ConfigExporter.fileName(
            game = "Crysis 3: Remaster!",
            mfr = "Google",
            model = "Pixel 8",
            soc = "Adreno 750",
            epochSeconds = 1778449587L,
        )
        // Five fields, each sanitized [^a-zA-Z0-9_\-] -> _, unix seconds, .json.
        assertEquals("Crysis_3__Remaster_-Google-Pixel_8-Adreno_750-1778449587.json", name)
    }

    @Test
    fun subValue_absentSubKey_notEmitted() {
        // dxwrapperConfig present but with NO version sub-key → no DXVK key emitted.
        val effective = mapOf("dxwrapperConfig" to "async=1,vulkanVersion=1.3")
        val settings = JSONObject(ConfigExporter.export(effective, meta)).getJSONObject("settings")
        assertFalse(settings.has("pc_ls_DXVK"))
        assertNull(settings.opt("pc_ls_DXVK"))
    }
}
