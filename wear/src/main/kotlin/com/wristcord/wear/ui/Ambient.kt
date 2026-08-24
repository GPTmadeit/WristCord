package com.wristcord.wear.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.ambient.AmbientLifecycleObserver

/**
 * Always-on support, the way YouTube Music does it: the screen never actually goes off while
 * the remote is open, it drops into a dim, mostly-unlit rendering and keeps showing you what
 * state the call is in.
 */
@Immutable
data class AmbientState(
    val isAmbient: Boolean = false,
    /** Device can only render a limited palette in ambient - draw outlines, not fills. */
    val lowBit: Boolean = false,
    /** Device wants static content nudged around so it does not etch into the panel. */
    val burnInProtection: Boolean = false,
    /** Bumped once per system ambient tick (about once a minute). */
    val tick: Long = 0L,
) {
    /**
     * A small per-tick shift applied to everything on screen in ambient. Two pixels either way
     * is enough to stop a static layout burning in over a long call.
     */
    val burnInOffset: Dp
        get() = if (isAmbient && burnInProtection) (((tick / 60_000L) % 5L) - 2L).toInt().dp else 0.dp
}

/**
 * Registers an [AmbientLifecycleObserver] for the hosting activity and surfaces its callbacks
 * as Compose state. Creating the observer is what turns always-on on for the activity.
 */
@Composable
fun rememberAmbientState(): AmbientState {
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(AmbientState()) }

    DisposableEffect(activity, lifecycleOwner) {
        if (activity == null) return@DisposableEffect onDispose { }

        val callback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                state = AmbientState(
                    isAmbient = true,
                    lowBit = ambientDetails.deviceHasLowBitAmbient,
                    burnInProtection = ambientDetails.burnInProtectionRequired,
                    tick = System.currentTimeMillis(),
                )
            }

            override fun onUpdateAmbient() {
                state = state.copy(tick = System.currentTimeMillis())
            }

            override fun onExitAmbient() {
                state = AmbientState()
            }
        }

        val observer = AmbientLifecycleObserver(activity, callback)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return state
}
