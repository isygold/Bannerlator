package com.winlator.star.reshade;

import android.content.Context;
import android.util.Log;

import com.winlator.star.core.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// STEP 3 — ReShade drop-in support (vkBasalt-powered). App-side discovery + param reflection.
//
// Users drop ReShade effects into one folder, ONE self-contained subfolder per effect (the .fx
// plus any .fxh includes and textures). scanEffects() lists every subfolder that contains a .fx;
// reflectParams() scrapes each .fx's `uniform ... < ui_* > = default;` annotations so the editor /
// in-game overlay can build a slider per tunable uniform (route B from RESHADE_STEP3_PLAN.md — a
// lightweight regex scraper, no native call). Robust to missing annotations: anything unparseable
// falls back to a sane default rather than dropping the param.
//
// vkBasalt compiles the .fx -> SPIR-V on-device (zlib-licensed, embedded reshadefx compiler) and
// applies it to DXVK/VKD3D (Vulkan) games. This class never touches the layer binary — it only
// discovers effects and describes their parameters; conf generation + env wiring live in
// XServerDisplayActivity (the same place the CAS/DLS sharpness path lives).
public class ReshadeManager {
    private static final String TAG = "ReshadeManager";

    // User-visible drop-in folder: /sdcard/Android/data/<pkg>/files/ReShade/. Reachable from any
    // file manager without extra storage permissions, and readable by the guest wine processes
    // (they run as the app UID — this fork does NOT proot). Mirrors the wine_debug.log location.
    public static final String FOLDER_NAME = "ReShade";

    public enum ParamType { FLOAT, INT, BOOL }

    // One tunable uniform reflected from a .fx. Float/int carry min/max/step; bool ignores them.
    public static class ReshadeParam {
        public final String name;          // the uniform identifier (what we write into vkBasalt.conf)
        public final ParamType type;
        public final float min, max, step;
        public final float defaultValue;   // bool default carried as 0.0 / 1.0
        public final String label;         // ui_label, else the uniform name
        public final String uiType;        // ui_type ("slider"/"drag"/"combo"/"color"/...), may be ""

        public ReshadeParam(String name, ParamType type, float min, float max, float step,
                            float defaultValue, String label, String uiType) {
            this.name = name;
            this.type = type;
            this.min = min;
            this.max = max;
            this.step = step;
            this.defaultValue = defaultValue;
            this.label = label;
            this.uiType = uiType;
        }
    }

    // A discovered effect: its subfolder name (the selectable identifier), its folder and .fx, and
    // the reflected param list.
    public static class ReshadeEffect {
        public final String name;
        public final File dir;
        public final File fxFile;
        public final List<ReshadeParam> params;

        public ReshadeEffect(String name, File dir, File fxFile, List<ReshadeParam> params) {
            this.name = name;
            this.dir = dir;
            this.fxFile = fxFile;
            this.params = params;
        }
    }

    public static File getReshadeDir(Context context) {
        File dir = new File(context.getExternalFilesDir(null), FOLDER_NAME);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    // List every subfolder that contains at least one .fx, sorted by name. Each becomes one
    // selectable effect. A .fx matching the folder name wins as the technique source; otherwise the
    // first .fx found.
    public static List<ReshadeEffect> scanEffects(Context context) {
        ArrayList<ReshadeEffect> out = new ArrayList<>();
        File root = getReshadeDir(context);
        File[] subdirs = root.listFiles(File::isDirectory);
        if (subdirs == null) return out;
        for (File dir : subdirs) {
            File fx = findFxFile(dir);
            if (fx == null) continue;
            out.add(new ReshadeEffect(dir.getName(), dir, fx, reflectParams(fx)));
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    // Names only (for dropdowns) — "None" is prepended by callers, not here.
    public static List<String> scanEffectNames(Context context) {
        ArrayList<String> names = new ArrayList<>();
        for (ReshadeEffect e : scanEffects(context)) names.add(e.name);
        return names;
    }

    public static ReshadeEffect findEffect(Context context, String name) {
        if (name == null || name.isEmpty()) return null;
        for (ReshadeEffect e : scanEffects(context)) {
            if (e.name.equalsIgnoreCase(name)) return e;
        }
        return null;
    }

    private static File findFxFile(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File first = null;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".fx")) {
                if (first == null) first = f;
                // Prefer a .fx whose basename matches the folder (e.g. Sepia/Sepia.fx).
                String base = f.getName().substring(0, f.getName().length() - 3);
                if (base.equalsIgnoreCase(dir.getName())) return f;
            }
        }
        return first;
    }

    // ── Param reflection (route B) ──────────────────────────────────────────────────────────────
    // Matches: uniform <type> <name> < ...annotations... > = <default> ;   (default optional)
    // ReShade annotations don't nest <>, so a non-greedy [^>]* block is sufficient.
    private static final Pattern UNIFORM = Pattern.compile(
            "uniform\\s+(\\w+)\\s+(\\w+)\\s*<([^>]*)>\\s*(?:=\\s*([^;]+?))?\\s*;",
            Pattern.DOTALL);

    public static List<ReshadeParam> reflectParams(File fxFile) {
        ArrayList<ReshadeParam> params = new ArrayList<>();
        String src;
        try {
            src = FileUtils.readString(fxFile);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read .fx for reflection: " + fxFile, e);
            return params;
        }
        if (src == null) return params;

        Matcher m = UNIFORM.matcher(src);
        while (m.find()) {
            String typeStr = m.group(1).toLowerCase(Locale.US);
            String name = m.group(2);
            String ann = m.group(3) != null ? m.group(3) : "";
            String defExpr = m.group(4);

            // Only scalar float/int/bool drive a single slider/switch. Skip vectors (floatN/intN),
            // colors, textures, samplers — the overlay scaffold handles scalars; richer controls
            // (color pickers, combos) are a later refinement.
            ParamType type;
            if (typeStr.equals("bool")) type = ParamType.BOOL;
            else if (typeStr.equals("int") || typeStr.equals("uint")) type = ParamType.INT;
            else if (typeStr.equals("float")) type = ParamType.FLOAT;
            else continue;

            // ui_type — skip explicit non-scalar UI hints that we can't represent as one slider.
            String uiType = unquote(annValue(ann, "ui_type"));
            if (uiType != null && (uiType.equals("color") || uiType.equals("radio")
                    || uiType.equals("list") || uiType.equals("combo"))) {
                // combo/list/radio map poorly to a slider; keep INT ones as a clamped slider but
                // drop color (a 3/4-component value can't ride one scalar slider).
                if (uiType.equals("color")) continue;
            }
            if (uiType == null) uiType = "";

            String label = unquote(annValue(ann, "ui_label"));
            if (label == null || label.isEmpty()) label = name;

            float min = parseFloat(annValue(ann, "ui_min"), type == ParamType.BOOL ? 0f
                    : (type == ParamType.INT ? 0f : 0f));
            float max = parseFloat(annValue(ann, "ui_max"), type == ParamType.INT ? 100f : 1f);
            float step = parseFloat(annValue(ann, "ui_step"),
                    type == ParamType.INT ? 1f : (Math.max(0.0001f, (max - min) / 100f)));
            if (max <= min) max = min + (type == ParamType.INT ? 1f : 1f);

            float def;
            if (type == ParamType.BOOL) {
                def = (defExpr != null && defExpr.trim().equalsIgnoreCase("true")) ? 1f : 0f;
            } else {
                def = parseFloat(defExpr, min);
                if (def < min) def = min;
                if (def > max) def = max;
            }

            params.add(new ReshadeParam(name, type, min, max, step, def, label, uiType));
        }
        return params;
    }

    // Pull `key = value` from an annotation block; value runs to the next `;` or end. Returns the
    // raw (possibly quoted) value, or null when absent.
    private static String annValue(String ann, String key) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(key) + "\\s*=\\s*([^;]+)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(ann);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }

    // Parse the leading float out of an expression (handles "0.5", "float(0.5)", "1.0f", "{0.5, ...}"
    // by taking the first number). Falls back to [fallback] on anything unparseable.
    private static float parseFloat(String s, float fallback) {
        if (s == null) return fallback;
        Matcher m = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+").matcher(s);
        if (m.find()) {
            try {
                return Float.parseFloat(m.group());
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
