package com.winlator.star.profiler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/**
 * Java-friendly helpers for creating and cancelling coroutine scopes.
 * Java cannot directly instantiate Kotlin coroutine interfaces;
 * these thin wrappers bridge the gap.
 */
object ProfilerScopeHelper {
    @JvmStatic
    fun create(): CoroutineScope = CoroutineScope(Dispatchers.IO)

    @JvmStatic
    fun cancel(scope: CoroutineScope?) {
        scope?.cancel()
    }
}
