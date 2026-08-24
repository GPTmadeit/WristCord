package com.wristcord.shared

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Makes sure this device is actually advertising its Data Layer capability.
 *
 * ## Why this exists
 *
 * A Wear capability is normally declared once, in `res/values/wear.xml`, as a string-array named
 * `android_wear_capabilities`. Nothing in the app's own code or manifest ever references it -
 * Google Play services reads it out of the APK, by name, from another process.
 *
 * Which means R8's *resource* shrinker sees an unreferenced resource and deletes it. That is
 * exactly what happened between v1.0.0 and v1.1.0 of this app: the array vanished from the
 * release APKs, neither device advertised anything, `getCapability` returned no nodes, and the
 * two halves silently stopped finding each other. Nothing crashed; nothing logged; it just did
 * not work.
 *
 * So there are now three independent guards, and this file is two of them:
 *
 *  1. `isShrinkResources` is off (see the build files) - the shrinker cannot see through a
 *     cross-process by-name lookup, so it is not allowed to make that call here.
 *  2. [ensure] reads the array through `R.array`, which makes it a referenced resource. Even if
 *     shrinking is switched back on, it now survives.
 *  3. [ensure] then registers the same capability at runtime via `addLocalCapability`, so the
 *     link comes up even if the resource were missing entirely.
 */
object Capabilities {

    private const val TAG = "WristCord/Caps"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * @param declared the contents of `R.array.android_wear_capabilities`, passed in by each
     *   module so this shared file does not need to know either module's R class. Reading it is
     *   the point: it is what keeps the resource alive.
     */
    fun ensure(ctx: Context, declared: Array<String>, expected: String) {
        val app = ctx.applicationContext
        if (!declared.contains(expected)) {
            Log.w(TAG, "wear.xml does not declare $expected (declared: ${declared.toList()})")
        }
        scope.launch {
            runCatching {
                withTimeoutOrNull(TIMEOUT_MS) {
                    Wearable.getCapabilityClient(app).addLocalCapability(expected).await()
                }
            }.onSuccess {
                Log.i(TAG, "advertising $expected")
            }.onFailure {
                Log.w(TAG, "could not advertise $expected", it)
            }
        }
    }

    /** What the local device is advertising right now. Used by the phone's diagnostics. */
    suspend fun localAdvertises(ctx: Context, capability: String): Boolean = runCatching {
        withTimeoutOrNull(TIMEOUT_MS) {
            Wearable.getCapabilityClient(ctx.applicationContext)
                .getAllCapabilities(CapabilityClient.FILTER_ALL)
                .await()
                .containsKey(capability)
        } ?: false
    }.getOrDefault(false)

    /** Whether the other device is currently reachable. */
    suspend fun peerReachable(ctx: Context, capability: String): Boolean = runCatching {
        withTimeoutOrNull(TIMEOUT_MS) {
            Wearable.getCapabilityClient(ctx.applicationContext)
                .getCapability(capability, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
                .isNotEmpty()
        } ?: false
    }.getOrDefault(false)

    private const val TIMEOUT_MS = 8_000L
}
