package com.wristcord.discord

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Endpoints and protocol constants, as Discord's own Android client (351.11) sets them.
 * Every value here is cited in docs/discord-protocol.md.
 */
object Discord {
    const val API_VERSION = 9
    const val API_BASE = "https://discord.com/api/v$API_VERSION"
    const val ORIGIN = "https://discord.com"

    /** The Android app asks for zstd when its native module supports it; zlib is the other half. */
    const val GATEWAY_URL = "wss://gateway.discord.gg/?encoding=json&v=$API_VERSION&compress=zlib-stream"
    const val REMOTE_AUTH_URL = "wss://remote-auth-gateway.discord.gg/?v=2"

    const val CDN = "https://cdn.discordapp.com"
    const val MEDIA_PROXY = "https://media.discordapp.net"

    /** What the QR code encodes; the phone's scanner recognises this prefix. */
    const val REMOTE_AUTH_QR_PREFIX = "https://discord.com/ra/"

    /**
     * Android 351.11 sends 1734655 (GatewayCapabilities.tsx). That value sets three bits no
     * public documentation describes (17, 19, 20); this is the same set without them, so the
     * READY payload keeps the shape this client parses.
     */
    const val CAPABILITIES = 30719

    /** `versionCode` of the Discord build this client mirrors. */
    const val MIRRORED_VERSION_CODE = 351011
    const val MIRRORED_VERSION = "351.11"

    /** getSuperProperties.tsx: parseInt("35101100000000", 10). */
    const val CLIENT_BUILD_NUMBER = 35101100000000L

    /** From the app's dex: "Discord-Android/" + versionCode + ";RNA". */
    const val USER_AGENT = "Discord-Android/$MIRRORED_VERSION_CODE;RNA"
}

/**
 * The device-specific half of the client identity. The Android layer fills it from `Build`;
 * tests use [ClientIdentity.forTests].
 */
data class ClientIdentity(
    /** Shown in Discord's Settings -> Devices, e.g. "Pixel Watch 4". */
    val deviceModel: String,
    /** `Build.DEVICE`, e.g. "kirby". Discord calls this `device`. */
    val deviceCodename: String,
    /** Android release, e.g. "16". */
    val osVersion: String,
    val osSdkVersion: Int,
    /** BCP-47, e.g. "en-US". */
    val systemLocale: String,
    /** IANA zone, e.g. "Europe/London". */
    val timezone: String,
    /** Random per install, kept for the life of the install - Discord's own app does the same. */
    val deviceVendorId: String,
    /** Random per process start. */
    val clientLaunchId: String,
) {
    /**
     * The `properties` object sent in IDENTIFY and, base64-encoded, as `X-Super-Properties` on
     * every REST call. Field set and order follow getSuperProperties.tsx's Android branch.
     */
    val superProperties: JsonObject by lazy {
        buildJsonObject {
            put("os", "Android")
            put("browser", "Discord Android")
            put("device", deviceCodename)
            put("system_locale", systemLocale)
            put("has_client_mods", false)
            put("client_version", "${Discord.MIRRORED_VERSION} - rn")
            put("release_channel", "googleRelease")
            put("device_vendor_id", deviceVendorId)
            put("design_id", 2)
            put("browser_user_agent", "")
            put("browser_version", "")
            put("os_version", osVersion)
            put("os_sdk_version", osSdkVersion.toString())
            put("client_build_number", Discord.CLIENT_BUILD_NUMBER)
            put("client_event_source", JsonNull)
            put("client_launch_id", clientLaunchId)
            put("device_model_name", deviceModel)
        }
    }

    val superPropertiesBase64: String by lazy {
        Base64.getEncoder().encodeToString(superProperties.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun forTests() = ClientIdentity(
            deviceModel = "Pixel Watch 4",
            deviceCodename = "test",
            osVersion = "16",
            osSdkVersion = 36,
            systemLocale = "en-US",
            timezone = "UTC",
            deviceVendorId = "00000000-0000-4000-8000-000000000000",
            clientLaunchId = "00000000-0000-4000-8000-000000000001",
        )
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
