package com.wristcord.shared

/**
 * One palette, both apps, the tile and the launcher icon.
 *
 * These are Google's four product colours plus the greys Pixel system surfaces use. Each
 * remote action owns one of the four, so a button means the same thing on the watch, on the
 * tile and in the phone's status card without anyone having to remember a legend.
 */
object Brand {
    /** Discord's own colour. Used for the icon ground - homage, not imitation. */
    const val BLURPLE = 0xFF5865F2.toInt()

    const val BLUE = 0xFF4285F4.toInt()
    const val RED = 0xFFEA4335.toInt()
    const val YELLOW = 0xFFFBBC04.toInt()
    const val GREEN = 0xFF34A853.toInt()

    const val BLUE_DIM = 0xFF1B3A6B.toInt()
    const val RED_DIM = 0xFF5C1D1A.toInt()
    const val YELLOW_DIM = 0xFF5C4708.toInt()
    const val GREEN_DIM = 0xFF14401F.toInt()

    /** Google's neutral ramp - #202124 / #303134 / #5F6368 / #9AA0A6 / #E8EAED. */
    const val INK = 0xFF000000.toInt()
    const val SURFACE = 0xFF202124.toInt()
    const val SURFACE_HIGH = 0xFF303134.toInt()
    const val OUTLINE = 0xFF5F6368.toInt()
    const val ON_SURFACE_VARIANT = 0xFF9AA0A6.toInt()
    const val ON_SURFACE = 0xFFE8EAED.toInt()
}

/** Everything the watch remote can be told to do. Slots map to one of these. */
enum class RemoteAction(
    val id: String,
    val label: String,
    /** Shown under the label on the big centre button. */
    val description: String,
    val accent: Int,
    val path: String?,
) {
    MUTE("mute", "Mute", "Mute your microphone", Brand.BLUE, Wire.PATH_MUTE),
    DEAFEN("deafen", "Deafen", "Mute everyone else too", Brand.YELLOW, Wire.PATH_DEAFEN),
    OUTPUT("output", "Output", "Pick the phone's audio device", Brand.GREEN, Wire.PATH_OUTPUT),
    DISCONNECT("disconnect", "Leave", "Drop out of the voice call", Brand.RED, Wire.PATH_DISCONNECT),
    REFRESH("refresh", "Refresh", "Ask the phone to resend its state", Brand.OUTLINE, Wire.PATH_REFRESH),
    NONE("none", "Empty", "Nothing in this slot", Brand.OUTLINE, null),
    ;

    /** Output opens a list rather than firing a command straight at the phone. */
    val opensList: Boolean get() = this == OUTPUT

    companion object {
        fun byId(id: String?): RemoteAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The four assignable positions, laid out like complication slots on a watch face. CENTRE is
 * deliberately the big one - it is the button you hit without looking.
 */
enum class Slot(val id: String, val label: String, val default: RemoteAction) {
    LEFT("left", "Left", RemoteAction.DEAFEN),
    CENTRE("centre", "Centre (large)", RemoteAction.MUTE),
    RIGHT("right", "Right", RemoteAction.OUTPUT),
    BOTTOM("bottom", "Bottom", RemoteAction.DISCONNECT),
}
