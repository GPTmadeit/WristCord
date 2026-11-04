package com.wristcord.wear.voice

import android.content.Context
import com.wristcord.shared.RemoteAction
import com.wristcord.shared.Slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which action sits in which position on the remote - the watch-face-complication idea, applied
 * to the four buttons. Persisted, and read by the Tile too so both surfaces agree.
 */
object SlotConfig {

    private const val PREFS = "wristcord_slots"

    private val _assignments = MutableStateFlow(Slot.entries.associateWith { it.default })
    val assignments: StateFlow<Map<Slot, RemoteAction>> = _assignments

    @Volatile
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _assignments.value = readSync(ctx)
            loaded = true
        }
    }

    /** Cold-start safe: the Tile can be rendered before anything else has run. */
    fun readSync(ctx: Context): Map<Slot, RemoteAction> {
        val prefs = prefs(ctx)
        return Slot.entries.associateWith { slot ->
            RemoteAction.byId(prefs.getString(slot.id, null)) ?: slot.default
        }
    }

    fun actionFor(ctx: Context, slot: Slot): RemoteAction =
        if (loaded) _assignments.value[slot] ?: slot.default else readSync(ctx)[slot] ?: slot.default

    fun assign(ctx: Context, slot: Slot, action: RemoteAction) {
        prefs(ctx).edit().putString(slot.id, action.id).apply()
        _assignments.value = _assignments.value + (slot to action)
        loaded = true
        Surfaces.refresh(ctx)
    }

    fun reset(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        _assignments.value = Slot.entries.associateWith { it.default }
        loaded = true
        Surfaces.refresh(ctx)
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
