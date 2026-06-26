package com.winlator.star.renderer;

import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.winlator.star.widget.XServerView;
import com.winlator.star.xserver.XServer;

/**
 * SurfaceFlinger host renderer (ASurfaceRenderer / "ASR").
 *
 * <p>Phase-0 spike port of GameNative PR #1582 (author André Vito), which is built on
 * StevenMX's scanout work (already present in this tree as VulkanRendererScanout.cpp). ASR
 * composites the X11 desktop + game frames directly through Android SurfaceFlinger via
 * SurfaceControl / ASurfaceTransaction instead of a GL/Vulkan compositor pass.</p>
 *
 * <p>This skeleton intentionally implements only the renderer lifecycle and the
 * {@link HostRenderer} contract: it loads the native library, declares the full native
 * surface-control method set, and creates/tears down the native SurfaceFlinger context on
 * the surface lifecycle. The actual per-window compositing (WindowManager listener, scanout
 * buffer wiring, cursor/desktop handling) is deferred to Phase&nbsp;1.</p>
 */
public class ASurfaceRenderer implements HostRenderer {
    private static final String TAG = "ASurfaceRenderer";

    static {
        System.loadLibrary("asurface_renderer");
    }

    /** ASurfaceControl / ASurfaceTransaction require API 29+. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    private final XServerView xServerView;
    private final XServer xServer;

    private int surfaceWidth;
    private int surfaceHeight;
    private boolean surfaceInitialized = false;

    // HostRenderer-backed state
    private boolean renderingEnabled = true;
    private boolean cursorVisible = true;
    private boolean fullscreen = false;
    private boolean screenOffsetYRelativeToCursor = false;
    private float magnifierZoom = 1.0f;
    private int fpsLimit = 0;
    private int fpsWindowId = 0;
    private String[] unviewableWMClasses = null;

    public ASurfaceRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
    }

    // -------------------------------------------------------------------------
    // Surface lifecycle (driven by XServerView's SurfaceHolder.Callback)
    // -------------------------------------------------------------------------

    public void onSurfaceCreated(Surface surface) {
        // Defer native init until we have a real size from onSurfaceChanged.
        if (surface != null && surfaceWidth > 0 && surfaceHeight > 0) {
            initNative(surface);
        }
    }

    public void onSurfaceChanged(Surface surface, int width, int height) {
        this.surfaceWidth = width;
        this.surfaceHeight = height;
        if (!surfaceInitialized) {
            initNative(surface);
        } else {
            nativeReattachSurface(surface);
        }
    }

    public void onSurfaceDestroyed() {
        if (surfaceInitialized) {
            nativeDestroyScanout();
            nativeDestroy();
            surfaceInitialized = false;
        }
    }

    private void initNative(Surface surface) {
        if (surfaceInitialized || surface == null) return;
        boolean ok = nativeInit(surface, surfaceWidth, surfaceHeight);
        if (ok) {
            surfaceInitialized = true;
            Log.i(TAG, "SurfaceFlinger renderer context created (" + surfaceWidth + "x" + surfaceHeight + ")");
        } else {
            Log.e(TAG, "nativeInit failed — SurfaceFlinger context not created");
        }
    }

    // -------------------------------------------------------------------------
    // HostRenderer
    // -------------------------------------------------------------------------

    @Override
    public XServerView getXServerView() {
        return xServerView;
    }

    @Override
    public void setRenderingEnabled(boolean enabled) {
        this.renderingEnabled = enabled;
    }

    @Override
    public void requestRender() {
        // ASR presents via SurfaceFlinger transactions; nothing to pump here in Phase 0.
    }

    @Override
    public void forceCleanup() {
        onSurfaceDestroyed();
    }

    @Override
    public void setCursorVisible(boolean visible) {
        this.cursorVisible = visible;
        if (surfaceInitialized) nativeScanoutSetCursorVisibility(visible);
    }

    @Override
    public boolean isCursorVisible() {
        return cursorVisible;
    }

    @Override
    public void setUnviewableWMClasses(String wmClasses) {
        this.unviewableWMClasses = wmClasses != null ? wmClasses.split(",") : null;
    }

    @Override
    public void setFilterMode(int mode) {
        // ASR bypasses the GL/Vulkan compositor: no shader/filter pass.
    }

    @Override
    public void setMagnifierZoom(float zoom) {
        this.magnifierZoom = zoom;
    }

    @Override
    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    @Override
    public void toggleFullscreen() {
        this.fullscreen = !this.fullscreen;
    }

    @Override
    public boolean isFullscreen() {
        return fullscreen;
    }

    @Override
    public void setScreenOffsetYRelativeToCursor(boolean b) {
        this.screenOffsetYRelativeToCursor = b;
    }

    @Override
    public boolean isScreenOffsetYRelativeToCursor() {
        return screenOffsetYRelativeToCursor;
    }

    @Override
    public void setFpsWindowId(int id) {
        this.fpsWindowId = id;
    }

    @Override
    public void setFrameRating(Object fr) {
        // Phase 1: hook the perf HUD into the scanout present callback.
    }

    @Override
    public int getFpsLimit() {
        return fpsLimit;
    }

    @Override
    public void setFpsLimit(int limit) {
        this.fpsLimit = limit;
    }

    @Override
    public int getSurfaceWidth() {
        return surfaceWidth;
    }

    @Override
    public int getSurfaceHeight() {
        return surfaceHeight;
    }

    // -------------------------------------------------------------------------
    // Native contract (libasurface_renderer.so) — full set wired for Phase 1.
    // -------------------------------------------------------------------------

    private native boolean nativeInit(Surface surface, int screenWidth, int screenHeight);
    private native void nativeDestroy();
    private native void nativeInitScanout();
    private native boolean nativeReattachSurface(Surface surface);
    private native void nativeDestroyScanout();
    private native void nativeSetWindowBuffer(long contentId, long ahbPtr, int fenceFd, long windowId, long serial);
    private native void nativeScanoutSetCursorVisibility(boolean visible);
    private native void nativeRegisterWindowSC(long contentId, String debugName);
    private native void nativeUnregisterWindowSC(long contentId);
    private native void nativeScanoutSetCursorImage(java.nio.ByteBuffer pixels, short w, short h, short stride);
    private native void nativeScanoutSetCursorPos(short x, short y, short hotX, short hotY, boolean cursorVisible);
    private native void nativeScanoutSetDst(int x, int y, int w, int h);
    private native void nativeSetSfCallbackTarget(Object rendererRef);
    private native void nativeBeginTransaction();
    private native void nativeApplyTransaction();
    private native void nativeUpdateWindow(long contentId, boolean visible, int zOrder,
            int srcL, int srcT, int srcR, int srcB,
            int dstL, int dstT, int dstR, int dstB);
}
