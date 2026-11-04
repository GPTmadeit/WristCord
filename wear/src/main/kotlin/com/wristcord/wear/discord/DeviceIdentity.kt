package com.wristcord.wear.discord

import android.content.Context
import android.os.Build
import com.wristcord.discord.ClientIdentity
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Who this watch says it is. Discord shows the model in Settings -> Devices, so the session
 * reads "Pixel Watch 4" there rather than a generic phone.
 */
object DeviceIdentity {

    private const val PREFS = "wristcord_identity"
    private const val K_VENDOR = "device_vendor_id"

    fun build(ctx: Context): ClientIdentity {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val vendor = prefs.getString(K_VENDOR, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(K_VENDOR, it).apply()
        }
        return ClientIdentity(
            deviceModel = Build.MODEL ?: "Wear OS",
            deviceCodename = Build.DEVICE ?: "wear",
            osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            osSdkVersion = Build.VERSION.SDK_INT,
            systemLocale = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id,
            deviceVendorId = vendor,
            clientLaunchId = UUID.randomUUID().toString(),
        )
    }
}
