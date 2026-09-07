package com.winlator.star.contentdialog;

import android.content.Context;
import android.util.Log;

import com.winlator.star.R;
import com.winlator.star.container.Container;
import com.winlator.star.core.DefaultVersion;
import com.winlator.star.contents.ContentProfile;
import com.winlator.star.contents.ContentsManager;
import com.winlator.star.core.EnvVars;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.KeyValueSet;
import com.winlator.star.core.StringUtils;
import com.winlator.star.core.VKD3DVersionItem;
import com.winlator.star.xenvironment.ImageFs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DXVKConfigDialog {
    private static final String TAG = "DXVKConfigDialog";
    public static final String DEFAULT_CONFIG = Container.DEFAULT_DXWRAPPERCONFIG;
    public static final int DXVK_TYPE_NONE = 0;
    public static final int DXVK_TYPE_ASYNC = 1;
    public static final int DXVK_TYPE_GPLASYNC = 2;
    public static final String[] VKD3D_FEATURE_LEVEL = {"12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1"};
    // VEGAS knowledge-layer assets bundled in assets/vegas_*.json (fork)
    public static final String VEGAS_KNOWLEDGE_ASSET = "vegas_knowledge.json";
    public static final String VEGAS_KEY_CATALOG_ASSET = "vegas_key_catalog.json";
    // Sentinel version for the DDraw-Wrapper=D7VK version dropdown. Means "use the bundled, offline
    // d7vk.tzst asset" (the default). Any other value is a downloaded CONTENT_TYPE_D7VK profile,
    // identified by "verName-verCode" (mirrors the VKD3D version identifier).
    public static final String D7VK_BUNDLED = "Bundled (default)";

    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    public static Integer tryGetMajor(String s) {
        if (s == null) return null;
        Matcher m = SEMVER.matcher(s);
        if (!m.find()) return null;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return null; }
    }

    public static int compareVersion(String varA, String varB) {
        final String[] levelsA = varA.split("\\.");
        final String[] levelsB = varB.split("\\.");
        int minLen = Math.min(levelsA.length, levelsB.length);
        for (int i = 0; i < minLen; i++) {
            int numA = Integer.parseInt(levelsA[i]);
            int numB = Integer.parseInt(levelsB[i]);
            if (numA != numB) return numA - numB;
        }
        return levelsA.length - levelsB.length;
    }

    public static int getDXVKType(String version) {
        if (version.contains("gplasync")) return DXVK_TYPE_GPLASYNC;
        if (version.contains("async")) return DXVK_TYPE_ASYNC;
        return DXVK_TYPE_NONE;
    }

    public static List<String> loadDxvkVersionList(Context context, ContentsManager contentsManager, boolean isArm64EC) {
        String[] original = context.getResources().getStringArray(R.array.dxvk_version_entries);
        List<String> list = new ArrayList<>(Arrays.asList(original));
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            String entry = ContentsManager.getEntryName(profile);
            int dash = entry.indexOf('-');
            list.add(entry.substring(dash + 1));
        }
        list.removeIf(v -> v.contains("arm64ec") && !isArm64EC);
        return list;
    }

    public static List<String> loadVkd3dVersionList(Context context, ContentsManager contentsManager) {
        String[] original = context.getResources().getStringArray(R.array.vkd3d_version_entries);
        List<String> list = new ArrayList<>(Arrays.asList(original));
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
            list.add(new VKD3DVersionItem(profile.verName, profile.verCode).getIdentifier());
        }
        return list;
    }

    public static List<String> loadD7vkVersionList(Context context, ContentsManager contentsManager) {
        // The bundled d7vk.tzst is always the first/default option (offline, zero-download).
        // Downloaded catalog profiles follow, identified as "verName-verCode" like VKD3D.
        List<String> list = new ArrayList<>();
        list.add(D7VK_BUNDLED);
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_D7VK)) {
            list.add(new VKD3DVersionItem(profile.verName, profile.verCode).getIdentifier());
        }
        return list;
    }

    public static List<String> loadVegasVersionList(Context context, ContentsManager contentsManager) {
        // Installed builds + the BUNDLED DEFAULT. vegas-2.7.3.tzst ships inside the
        // APK and the launcher extracts it as a fallback whenever no installed wcp
        // matches (see XServerDisplayActivity's vegas apply block), so it is always
        // launchable regardless of clear-data — list it explicitly. Other versions
        // appear only when actually installed (no ghost entries for builds whose
        // payloads we don't carry).
        List<String> list = new ArrayList<>();
        String bundled = DefaultVersion.getVegasDefault();
        if (bundled != null && !bundled.isEmpty()) list.add(bundled);

        // vegas WCP profiles have type CONTENT_TYPE_VEGAS, verName like "vegas-2.7.3"
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VEGAS)) {
            if (profile.verName != null && profile.verName.startsWith("vegas-")) {
                String ver = profile.verName.substring("vegas-".length());
                if (!list.contains(ver)) list.add(ver);
            }
        }

        return list;
    }

    public static List<String> loadVegasConfigSourceList(Context context) {
        String[] original = context.getResources().getStringArray(R.array.vegas_config_source_entries);
        return new ArrayList<>(Arrays.asList(original));
    }

    /** One installed VEGAS package that ships a stock config file, probed on-device. */
    public static final class StockSource {
        public final String verName;
        /** Release tag from the GitHub release (e.g. "v2.4.1-3137660"); null for pre-sidecar installs. */
        public final String tag;
        /** Real asset name from the release (e.g. "vegas-config-2.4.1-3137660.conf" or "dxvk.conf"); null pre-sidecar. */
        public final String assetName;
        public final java.io.File file;

        public StockSource(String verName, java.io.File file) {
            this(verName, null, null, file);
        }

        public StockSource(String verName, String tag, String assetName, java.io.File file) {
            this.verName = verName;
            this.tag = tag;
            this.assetName = assetName;
            this.file = file;
        }

        /** Dropdown label — disambiguates same-verName releases (v2.7.3-vegas vs v2.7.3-vegas-stable). */
        public String displayLabel() {
            return tag != null ? verName + " · " + tag : verName;
        }
    }

    /**
     * Stock config files shipped ALONGSIDE installed VEGAS WCP packages: the download
     * sheet fetches the release's .conf asset on the same tap as the .wcp and parks it
     * at <contentDir>/VEGAS/configs/<verName>.conf, recording the real asset name and
     * release tag in a .provenance.json sidecar (see VegasDownloadSheet). This probe
     * resolves those — it never looks inside a package, because the config is not in it.
     * Legacy parked files (pre-sidecar) still resolve via the file-name probe alone.
     */
    public static List<StockSource> loadVegasStockSources(Context context, ContentsManager contentsManager) {
        List<StockSource> out = new ArrayList<>();
        List<ContentProfile> profiles = contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VEGAS);
        if (profiles == null) return out;
        java.io.File confDir = new java.io.File(
                ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_VEGAS), "configs");
        org.json.JSONObject sidecar = loadStockProvenance(confDir);
        java.util.Set<String> seen = new java.util.HashSet<>();
        // The BUNDLED DEFAULT build has no content profile, yet its fallback payload
        // always launches — so a parked stock config for it is just as valid. Surface
        // it before the profile loop; `seen` keeps the profile loop from duplicating
        // it if a matching wcp is also installed.
        String bundledDefault = DefaultVersion.getVegasDefault();
        if (bundledDefault != null && !bundledDefault.isEmpty()) {
            java.io.File builtinConf = new java.io.File(confDir, bundledDefault + ".conf");
            if (builtinConf.isFile()) {
                org.json.JSONObject entry = sidecar != null ? sidecar.optJSONObject(bundledDefault) : null;
                out.add(new StockSource(bundledDefault,
                    entry != null ? entry.optString("tag", null) : null,
                    entry != null ? entry.optString("assetName", null) : null,
                    builtinConf));
                seen.add(bundledDefault);
            }
        }
        for (ContentProfile profile : profiles) {
            if (profile.verName == null) continue;
            // Fix #2: verName alone is not unique (v2.7.3-vegas vs v2.7.3-vegas-stable share it).
            // Dedupe by verName + verCode so both builds can surface as distinct StockSources.
            String dedupeKey = profile.verName + "#" + profile.verCode;
            if (!seen.add(dedupeKey)) continue;
            // Probe both naming variants: legacy parks used "vegas-2.7.3.conf" (with prefix) while
            // StockConfigFetcher parks as "2.7.3.conf" (stripped). Accept either so a download
            // always surfaces (fixes #2 "downloaded but not in dropdown").
            java.io.File conf1 = new java.io.File(confDir, profile.verName + ".conf");
            String stripped = profile.verName.startsWith("vegas-") ? profile.verName.substring("vegas-".length()) : profile.verName;
            java.io.File conf2 = new java.io.File(confDir, stripped + ".conf");
            java.io.File conf = conf1.isFile() ? conf1 : (conf2.isFile() ? conf2 : null);
            if (conf == null) continue;
            String tag = null, assetName = null;
            if (sidecar != null) {
                // provenance key may be with or without prefix depending on which sheet parked it
                String key = sidecar.has(profile.verName) ? profile.verName : (sidecar.has(stripped) ? stripped : null);
                if (key != null) {
                    org.json.JSONObject entry = sidecar.optJSONObject(key);
                    if (entry != null) {
                        tag = entry.optString("tag", null);
                        assetName = entry.optString("assetName", null);
                    }
                }
            }
            out.add(new StockSource(profile.verName, tag, assetName, conf));
        }
        return out;
    }

    /** configs/.provenance.json — verName -> {tag, assetName, url, parkedAt} (written by VegasDownloadSheet). */
    private static org.json.JSONObject loadStockProvenance(java.io.File confDir) {
        try {
            java.io.File f = new java.io.File(confDir, ".provenance.json");
            if (!f.isFile()) return null;
            return new org.json.JSONObject(FileUtils.readString(f));
        } catch (Exception e) {
            return null; // corrupt/unreadable sidecar -> legacy fallback, never crash the sheet
        }
    }

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    /**
     * Loads the bundled VEGAS knowledge asset (vegas_knowledge.json). Returns
     * null on any failure — missing asset or schema rejection — so callers can
     * fall back to last-known-good presentation instead of crashing the sheet.
     */
    public static VegasKeyKnowledge loadVegasKeyKnowledge(Context context) {
        try (java.io.InputStream in = context.getAssets().open(VEGAS_KNOWLEDGE_ASSET)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new VegasKeyKnowledge(out.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Loads the bundled VEGAS key catalog (vegas_key_catalog.json). Returns null on
     * any failure — missing asset or schema rejection — so callers can annotate rows
     * "unverified" instead of crashing the sheet. READ-ONLY by contract (§6b): this
     * class owns no adoption/migration path and never writes user config.
     */
    public static VegasKeyCatalog loadVegasKeyCatalog(Context context) {
        try (java.io.InputStream in = context.getAssets().open(VEGAS_KEY_CATALOG_ASSET)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new VegasKeyCatalog(out.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        setEnvVars(context, config, envVars, null);
    }

    /**
     * @param logDirOverride where DXVK/VKD3D should write their logs — the launching activity passes
     *                       this game's folder so its logs sit beside the Wine log instead of in one
     *                       shared pile. Null keeps the old flat behaviour (used by config previews,
     *                       which have no game context).
     */
    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars,
                                  java.io.File logDirOverride) {
        String configFile = config.get("dxvkConfigFile");
        boolean hasConfigFile = configFile != null && !configFile.isEmpty() && !configFile.equals("0") && !configFile.equals("None");
        if (com.winlator.star.BuildConfig.DEBUG) {
            Log.d(TAG, "setEnvVars: hasConfigFile=" + hasConfigFile + " configFile=" + configFile);
        }

        // DXVK_FRAME_RATE is a standalone env var, independent of DXVK_CONFIG.
        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            envVars.put("DXVK_FRAME_RATE", framerate);
        }

        if (!hasConfigFile) {
            // Stock: build inline defaults
            StringBuilder contentBuilder = new StringBuilder();
            if (!framerate.isEmpty() && !framerate.equals("0")) {
                contentBuilder.append("dxgi.maxFrameRate = ").append(framerate).append("; ");
                contentBuilder.append("d3d9.maxFrameRate = ").append(framerate);
            }

            // Append vegas-specific defaults — harmless for plain DXVK
            {
                if (contentBuilder.length() > 0) contentBuilder.append("; ");
                contentBuilder.append("dxvk.enableStarProfile = Auto; ");
                contentBuilder.append("vegas.enableUpscaler = Auto");
            }

            String content = contentBuilder.toString();
            if (!content.isEmpty()) {
                envVars.put("DXVK_CONFIG", content);
                if (com.winlator.star.BuildConfig.DEBUG) {
                    Log.d(TAG, "Stock DXVK_CONFIG=[" + content + "]");
                }
            }
        } else {
            // Custom config file: read content in Java and pass via DXVK_CONFIG (inline).
            // Wine's NT syscall layer mangles POSIX paths through its internal VFS when
            // opening files on /storage/emulated/0/ — the same UID can fopen("/sdcard/...")
            // successfully but ifstream("/storage/emulated/0/...") fails silently on some
            // devices. We bridge the gap by reading the file here (Java uses the native
            // Storage Access Framework) and injecting it as a semicolon-delimited inline
            // string. The DXVK/Vegas binary's config parser splits DXVK_CONFIG by ";"
            // and parses each segment as a line.
            try {
                java.io.File file = new java.io.File(configFile);
                if (file.isFile() && file.length() <= 8192) {
                    StringBuilder inlineBuilder = new StringBuilder();
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.FileReader(file))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String trimmed = line.trim();
                            // Skip blanks and # comments — the parser would ignore
                            // them anyway, but stripping keeps the env var compact.
                            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                                continue;
                            }
                            if (inlineBuilder.length() > 0) inlineBuilder.append("; ");
                            inlineBuilder.append(trimmed);
                        }
                    }
                    String content = inlineBuilder.toString();
                    if (!content.isEmpty()) {
                        envVars.put("DXVK_CONFIG", content);
                    }
                    // Also set DXVK_CONFIG_FILE as a fallback in case the binary
                    // CAN access the path (e.g. future Wine builds with FUSE mount).
                    envVars.put("DXVK_CONFIG_FILE", configFile);
                    if (com.winlator.star.BuildConfig.DEBUG) {
                        String preview = content.length() > 200
                                ? content.substring(0, 200) + "…" : content;
                        Log.d(TAG, "Custom DXVK_CONFIG (inline from " + configFile + ")=" + preview);
                    }
                } else if (file.isFile()) {
                    // File too large for env var — fall back to file path only
                    envVars.put("DXVK_CONFIG_FILE", configFile);
                    if (com.winlator.star.BuildConfig.DEBUG) {
                        Log.w(TAG, "Config file too large for inline (" + file.length()
                                + " bytes), using DXVK_CONFIG_FILE path only");
                    }
                } else {
                    // Config file not found at the chosen path — fall back to stock inline
                    // defaults so VEGAS/DXVK still gets its base config (enableStarProfile,
                    // enableUpscaler, framerate, etc.) instead of running with zero config.
                    Log.w(TAG, "Custom config file not found: " + configFile + " — falling back to stock inline");
                    StringBuilder fallbackBuilder = new StringBuilder();
                    if (!framerate.isEmpty() && !framerate.equals("0")) {
                        fallbackBuilder.append("dxgi.maxFrameRate = ").append(framerate).append("; ");
                        fallbackBuilder.append("d3d9.maxFrameRate = ").append(framerate);
                    }
                    {
                        if (fallbackBuilder.length() > 0) fallbackBuilder.append("; ");
                        fallbackBuilder.append("dxvk.enableStarProfile = Auto; ");
                        fallbackBuilder.append("vegas.enableUpscaler = Auto");
                    }
                    String fallbackContent = fallbackBuilder.toString();
                    if (!fallbackContent.isEmpty()) {
                        envVars.put("DXVK_CONFIG", fallbackContent);
                        if (com.winlator.star.BuildConfig.DEBUG) {
                            Log.d(TAG, "Fallback DXVK_CONFIG=[" + fallbackContent + "]");
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read custom config file: " + configFile, e);
                // Fallback: try the path in case the binary can read it, AND set inline
                // so the binary has at least its base config if the file read fails.
                envVars.put("DXVK_CONFIG_FILE", configFile);
                StringBuilder exBuilder = new StringBuilder();
                if (!framerate.isEmpty() && !framerate.equals("0")) {
                    exBuilder.append("dxgi.maxFrameRate = ").append(framerate).append("; ");
                    exBuilder.append("d3d9.maxFrameRate = ").append(framerate);
                }
                {
                    if (exBuilder.length() > 0) exBuilder.append("; ");
                    exBuilder.append("dxvk.enableStarProfile = Auto; ");
                    exBuilder.append("vegas.enableUpscaler = Auto");
                }
                String exContent = exBuilder.toString();
                if (!exContent.isEmpty()) {
                    envVars.put("DXVK_CONFIG", exContent);
                }
            }
        }

        if (!config.get("async").isEmpty() && !config.get("async").equals("0"))
            envVars.put("DXVK_ASYNC", "1");
        if (!config.get("asyncCache").isEmpty() && !config.get("asyncCache").equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");
        envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
        envVars.put("DXVK_STATE_CACHE_PATH", context.getFilesDir() + "/imagefs/" + ImageFs.CACHE_PATH);

        // Co-locate the DXVK/DXGI (and VKD3D-Proton) logs in the same user-chosen folder as
        // wine_debug.log (issue #70). These stay SEPARATE files (<app>_d3d11.log / <app>_dxgi.log /
        // vkd3d-proton.log), just written next to the wine log instead of the game working dir.
        // Honour the Log Manager's "DXVK & VKD3D" switch. DXVK logs unless told otherwise, so
        // turning it off means silencing it explicitly rather than just not choosing a path.
        //
        // Check the switch BEFORE resolving a directory: resolving one CREATES it, so asking first
        // is what keeps a switched-off Log Manager from still littering an empty folder per game on
        // every launch. Callers pass null for logDirOverride when logging is off, for the same
        // reason — see XServerDisplayActivity.dxvkLogDir().
        boolean dxvkLogs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context).getBoolean("enable_dxvk_logs", true);
        if (dxvkLogs) {
            // Logging ON: co-locate the user-visible DXVK/DXGI/VKD3D logs in the chosen folder (unchanged).
            java.io.File logDir = logDirOverride != null
                    ? logDirOverride
                    : com.winlator.star.core.LogLocation.resolveLogDir(context);
            if (logDir != null) {
                // The DXVK/VEGAS binary runs inside Wine — raw Android paths (/sdcard/...) are
                // readable via Z: but the FUSE VFS layer silently rejects writes. Translate to
                // Wine DOS paths so ofstream succeeds.  (ifstream for DXVK_CONFIG_FILE works
                // natively, but ofstream for log files does not — different VFS code path.)
                envVars.put("DXVK_LOG_PATH", toWinePath(logDir.getAbsolutePath()));
                envVars.put("VKD3D_LOG_FILE", toWinePath(
                        new java.io.File(logDir, "vkd3d-proton.log").getAbsolutePath()));
            }
        } else if (logDirOverride != null) {
            // Logging OFF but a private HUD dir was supplied: instead of silencing the wrappers, write a
            // MINIMAL startup-only signal there so the in-game HUD API resolver still gets ground truth
            // (arm64ec hides the DX DLLs from /proc/maps, so this is the only host-visible signal). Both
            // "info" is deliberate: VKD3D_DEBUG=info emits vkd3d-proton's "Program name" identity line and
            // device init; DXVK_LOG_LEVEL=info emits DXVK's per-API <app>_d3dNN.log files with their
            // header. These are startup-level logs (no per-frame spam) written to a per-launch private dir.
            // Respect a user-set DXVK_LOG_LEVEL (e.g. debug for vegas csv at root) — only set if not already present.
            envVars.put("DXVK_LOG_PATH", toWinePath(logDirOverride.getAbsolutePath()));
            if (!envVars.has("DXVK_LOG_LEVEL")) envVars.put("DXVK_LOG_LEVEL", "info");
            envVars.put("VKD3D_LOG_FILE", toWinePath(
                    new java.io.File(logDirOverride, "vkd3d-proton.log").getAbsolutePath()));
            if (!envVars.has("VKD3D_DEBUG")) envVars.put("VKD3D_DEBUG", "info");
        } else {
            // Logging OFF and no dir supplied (config previews) — keep the wrappers fully silent
            // — but respect an explicit user override (e.g. DXVK_LOG_LEVEL=debug to get vegas csv at root).
            if (!envVars.has("DXVK_LOG_LEVEL")) envVars.put("DXVK_LOG_LEVEL", "none");
            if (!envVars.has("VKD3D_DEBUG")) envVars.put("VKD3D_DEBUG", "none");
        }
    }

    /**
     * Translates an absolute Android path to a Wine DOS path using the standard drive mapping:
     *   F: → /storage/emulated/0  (external storage root)
     *   D: → /storage/emulated/0/Download
     * If the path doesn't match either prefix, it is returned unchanged (already a Wine path,
     * or an unexpected location). Forward slashes become backslashes per DOS convention.
     *
     * Needed because std::ofstream (write) fails silently on raw Android paths through Wine's
     * FUSE VFS layer, while std::ifstream (read) works natively. The DXVK/VEGAS binary uses
     * ofstream for DXVK_LOG_PATH / VKD3D_LOG_FILE.
     *
     * e.g. /sdcard/Documents/bannerlator/Container-1 → F:\Documents\bannerlator\Container-1
     */
    static String toWinePath(String androidPath) {
        if (androidPath == null) return null;
        String downloads = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        String extStorage = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();

        if (androidPath.startsWith(downloads + "/")) {
            return "D:\\" + androidPath.substring(downloads.length() + 1).replace("/", "\\");
        }
        if (androidPath.startsWith(extStorage + "/")) {
            return "F:\\" + androidPath.substring(extStorage.length() + 1).replace("/", "\\");
        }
        // Already a Wine path or unexpected — pass through
        return androidPath;
    }
}
