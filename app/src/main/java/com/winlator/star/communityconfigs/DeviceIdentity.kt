package com.winlator.star.communityconfigs

import android.content.Context
import android.os.Build
import com.winlator.star.core.GPUInformation

/**
 * Best-effort description of the running device, used to rank community-config device rows.
 * Nothing here is required — when a value is unavailable we simply fall back to showing all rows.
 */
object DeviceIdentity {

    /** SoC model (e.g. "SM8650"). {@code Build.SOC_MODEL} is API 31+; null on older devices. */
    fun soc(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val model = Build.SOC_MODEL
            if (!model.isNullOrBlank() && model != Build.UNKNOWN) return model
        }
        return null
    }

    /** GPU/driver name from the existing native probe (e.g. "Adreno 750"); null on failure. */
    fun gpu(context: Context): String? =
        try {
            GPUInformation.getRenderer(null, context)?.takeIf {
                it.isNotBlank() && !it.contains("unknown", ignoreCase = true)
            }
        } catch (e: Throwable) {
            null
        }
}
