package com.wristcord.shared

import com.google.android.gms.wearable.DataMap

/**
 * The complete watch <-> phone contract. This file lives in `shared/kotlin` and is added to
 * BOTH modules' main source set, so there is exactly one definition of every path and key.
 */
object Wire {

    /** Advertised via res/values/wear.xml so each side can resolve the other's node id. */
    const val CAPABILITY_PHONE = "wristcord_phone"
    const val CAPABILITY_WATCH = "wristcord_watch"

    // ---- watch -> phone (MessageClient) ----------------------------------------------------
    const val PATH_MUTE = "/discord/mute"
    const val PATH_DEAFEN = "/discord/deafen"
    const val PATH_DISCONNECT = "/discord/disconnect"
    const val PATH_OUTPUT = "/discord/output"

    /** "tell me the current state again" - fired when the watch app or tile comes to the front. */
    const val PATH_REFRESH = "/discord/refresh"

    // ---- phone -> watch --------------------------------------------------------------------
    /** DataClient item holding [VoiceState]. */
    const val PATH_STATE = "/wristcord/state"

    /** MessageClient reply carrying [ActionResult] for one command. Never silent. */
    const val PATH_RESULT = "/wristcord/result"

    val ALL_ACTION_PATHS = listOf(PATH_MUTE, PATH_DEAFEN, PATH_DISCONNECT, PATH_OUTPUT, PATH_REFRESH)
}

/**
 * Tri-state, because "we could not tell" is a real and common answer: Discord only exposes a
 * mute/deafen action on its notification while a voice call is running, and the deafen action
 * is not always there at all. Rendering "off" when we mean "unknown" would lie to the user.
 */
enum class Toggle {
    ON, OFF, UNKNOWN;

    fun wire(): Int = when (this) {
        ON -> 1
        OFF -> 0
        UNKNOWN -> -1
    }

    companion object {
        fun of(v: Int): Toggle = when (v) {
            1 -> ON
            0 -> OFF
            else -> UNKNOWN
        }
    }
}

/** One entry in the phone's audio output picker. */
data class OutputDevice(
    val id: Int,
    val type: Int,
    val label: String,
    val active: Boolean,
) {
    fun encode(): String = listOf(id.toString(), type.toString(), if (active) "1" else "0", label)
        .joinToString(FIELD_SEP)

    companion object {
        private const val FIELD_SEP = "\u001f"

        fun decode(s: String): OutputDevice? {
            val parts = s.split(FIELD_SEP, limit = 4)
            if (parts.size < 4) return null
            val id = parts[0].toIntOrNull() ?: return null
            val type = parts[1].toIntOrNull() ?: return null
            return OutputDevice(id = id, type = type, label = parts[3], active = parts[2] == "1")
        }
    }
}

/** Everything the watch needs to render. Published by the phone on every observed change. */
data class VoiceState(
    val inCall: Boolean = false,
    val channel: String = "",
    val muted: Toggle = Toggle.UNKNOWN,
    val deafened: Toggle = Toggle.UNKNOWN,
    val outputs: List<OutputDevice> = emptyList(),
    /** Human-readable reason the phone cannot act right now, or "" when everything is wired up. */
    val blocker: String = "",
    val updatedAt: Long = 0L,
) {
    fun toDataMap(): DataMap = DataMap().apply {
        putBoolean(K_IN_CALL, inCall)
        putString(K_CHANNEL, channel)
        putInt(K_MUTED, muted.wire())
        putInt(K_DEAFENED, deafened.wire())
        putStringArray(K_OUTPUTS, outputs.map { it.encode() }.toTypedArray())
        putString(K_BLOCKER, blocker)
        putLong(K_UPDATED_AT, updatedAt)
    }

    companion object {
        private const val K_IN_CALL = "in_call"
        private const val K_CHANNEL = "channel"
        private const val K_MUTED = "muted"
        private const val K_DEAFENED = "deafened"
        private const val K_OUTPUTS = "outputs"
        private const val K_BLOCKER = "blocker"
        private const val K_UPDATED_AT = "updated_at"

        fun fromDataMap(map: DataMap): VoiceState = VoiceState(
            inCall = map.getBoolean(K_IN_CALL, false),
            channel = map.getString(K_CHANNEL, ""),
            muted = Toggle.of(map.getInt(K_MUTED, -1)),
            deafened = Toggle.of(map.getInt(K_DEAFENED, -1)),
            outputs = (map.getStringArray(K_OUTPUTS) ?: emptyArray()).mapNotNull(OutputDevice::decode),
            blocker = map.getString(K_BLOCKER, ""),
            updatedAt = map.getLong(K_UPDATED_AT, 0L),
        )
    }
}

/** The phone's answer to one command. Sent for every command, success or failure. */
data class ActionResult(
    val path: String,
    val ok: Boolean,
    /** Short, user-facing. Shown on the watch and toasted on the phone. */
    val message: String,
    val strategy: String = "",
) {
    fun toBytes(): ByteArray = DataMap().apply {
        putString(K_PATH, path)
        putBoolean(K_OK, ok)
        putString(K_MESSAGE, message)
        putString(K_STRATEGY, strategy)
    }.toByteArray()

    companion object {
        private const val K_PATH = "path"
        private const val K_OK = "ok"
        private const val K_MESSAGE = "message"
        private const val K_STRATEGY = "strategy"

        fun fromBytes(bytes: ByteArray): ActionResult {
            val map = DataMap.fromByteArray(bytes)
            return ActionResult(
                path = map.getString(K_PATH, ""),
                ok = map.getBoolean(K_OK, false),
                message = map.getString(K_MESSAGE, ""),
                strategy = map.getString(K_STRATEGY, ""),
            )
        }
    }
}
