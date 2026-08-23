package com.wristcord.wear.link

import android.content.Context
import android.util.Base64
import com.google.android.gms.wearable.DataMap
import com.wristcord.shared.ActionResult
import com.wristcord.shared.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The watch's copy of what the phone last told us.
 *
 * Persisted, because the Tile can be asked for a layout in a cold process where no Compose
 * screen has ever run - it has to be able to render "Muted" without waiting on the phone.
 */
object StateStore {

    private const val PREFS = "wristcord_watch_state"
    private const val K_STATE = "state_blob"
    private const val K_ERROR = "error"
    private const val K_ERROR_AT = "error_at"

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Set while a command is in flight so the UI can show it is doing something. */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    @Volatile
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _state.value = readSync(ctx)
            _error.value = prefs(ctx).getString(K_ERROR, null)
            loaded = true
        }
    }

    /** Cold-start safe read used by the Tile. Never touches the flows' loaded flag. */
    fun readSync(ctx: Context): VoiceState {
        val blob = prefs(ctx).getString(K_STATE, null) ?: return VoiceState()
        return runCatching {
            VoiceState.fromDataMap(DataMap.fromByteArray(Base64.decode(blob, Base64.NO_WRAP)))
        }.getOrDefault(VoiceState())
    }

    fun readErrorSync(ctx: Context): String? = prefs(ctx).getString(K_ERROR, null)

    fun write(ctx: Context, state: VoiceState) {
        _state.value = state
        loaded = true
        prefs(ctx).edit()
            .putString(K_STATE, Base64.encodeToString(state.toDataMap().toByteArray(), Base64.NO_WRAP))
            .apply()
    }

    fun onResult(ctx: Context, result: ActionResult) {
        _pending.value = null
        if (result.ok) clearError(ctx) else setError(ctx, result.message)
    }

    fun setPending(path: String?) {
        _pending.value = path
    }

    fun setError(ctx: Context, message: String) {
        _error.value = message
        _pending.value = null
        prefs(ctx).edit()
            .putString(K_ERROR, message)
            .putLong(K_ERROR_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearError(ctx: Context) {
        _error.value = null
        prefs(ctx).edit().remove(K_ERROR).remove(K_ERROR_AT).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
