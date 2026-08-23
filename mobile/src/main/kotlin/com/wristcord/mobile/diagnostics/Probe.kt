package com.wristcord.mobile.diagnostics

import android.app.Notification
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.wristcord.mobile.audio.AudioOutputs
import com.wristcord.mobile.discord.Discord
import com.wristcord.mobile.discord.DiscordAccessibilityService
import com.wristcord.mobile.discord.DiscordNotificationListener
import com.wristcord.mobile.discord.NotificationActionStrategy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * STEP 0 - discovery. Run these dumps before trusting any selector in
 * [com.wristcord.mobile.discord.DiscordSelectors].
 *
 * Everything printed here also goes to logcat under the tag [TAG], so
 *     adb logcat -s WristCordProbe
 * gets you the same text on a laptop while you poke at the phone.
 */
object Probe {

    const val TAG = "WristCordProbe"

    /**
     * For every com.discord notification: the channel, every action title, and whether each
     * actionIntent is a broadcast, service or activity PendingIntent. Join a voice call first.
     */
    fun dumpNotifications(ctx: Context): String = report("DISCORD NOTIFICATIONS") { out ->
        if (!Discord.isInstalled(ctx)) {
            out("Discord (${Discord.PACKAGE}) is not installed on this phone.")
            return@report
        }
        if (!DiscordNotificationListener.isGranted(ctx)) {
            out("Notification access is NOT granted - grant it, then run this again.")
            return@report
        }
        val notifications = DiscordNotificationListener.snapshot()
        if (notifications == null) {
            out("Notification access is granted but the listener is not bound yet.")
            out("Toggle WristCord off and on under Notification access, then retry.")
            return@report
        }
        if (notifications.isEmpty()) {
            out("No com.discord notifications are posted right now.")
            out("Join a voice channel on the phone and run this again.")
            return@report
        }

        notifications.forEachIndexed { index, sbn ->
            val n = sbn.notification
            val extras = n.extras
            out("")
            out("[$index] key=${sbn.key}")
            out("     package        = ${sbn.packageName}")
            out("     channel        = ${n.channelId}")
            out("     category       = ${n.category}")
            out("     ongoing        = ${(n.flags and Notification.FLAG_ONGOING_EVENT) != 0}")
            out("     title          = ${extras?.getCharSequence(Notification.EXTRA_TITLE)}")
            out("     text           = ${extras?.getCharSequence(Notification.EXTRA_TEXT)}")
            out("     subText        = ${extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)}")
            out("     template       = ${extras?.getString(Notification.EXTRA_TEMPLATE)}")
            val contentIntentKind = n.contentIntent
                ?.let { NotificationActionStrategy.describe(it) }
                ?: "none"
            out("     contentIntent  = $contentIntentKind")

            val actions = n.actions
            if (actions == null || actions.isEmpty()) {
                out("     actions        = (none)")
            } else {
                out("     actions        = ${actions.size}")
                actions.forEachIndexed { i, action ->
                    val kind = action.actionIntent
                        ?.let { NotificationActionStrategy.describe(it) }
                        ?: "NO actionIntent"
                    out("       actions[$i].title = \"${action.title}\"   ->  $kind")
                    action.remoteInputs?.forEach { ri ->
                        out("            remoteInput: ${ri.resultKey} \"${ri.label}\"")
                    }
                }
            }
        }

        out("")
        out("--- what WristCord picked as the voice notification ---")
        val voice = DiscordNotificationListener.voiceNotification(ctx)
        if (voice == null) {
            out("None of the above scored as a call notification with the current selectors.")
            out("Copy the real action titles into Diagnostics > Selectors.")
        } else {
            out("key=${voice.sbn.key} ongoing=${voice.ongoing} score=${voice.score}")
            out("titles=${voice.actions.joinToString(", ") { "\"${it.title}\"" }}")
        }
    }

    /**
     * viewIdResourceName and contentDescription for every clickable node in whatever Discord
     * has on screen. Open Discord's voice panel on the phone first.
     */
    fun dumpVoicePanel(ctx: Context): String = report("DISCORD VOICE PANEL (accessibility)") { out ->
        val svc = DiscordAccessibilityService.connected()
        if (svc == null) {
            if (DiscordAccessibilityService.isGranted(ctx)) {
                out("Accessibility service is granted but not running - toggle it off and on.")
            } else {
                out("Accessibility service is NOT enabled - turn on WristCord under")
                out("Settings > Accessibility, then run this again.")
            }
            return@report
        }
        if (!svc.isDiscordForeground()) {
            out("Discord is not the foreground app right now.")
            out("Open Discord's voice panel on the phone, then dump again.")
            out("(Everything below is whatever IS on screen, for reference.)")
            out("")
        }

        val nodes = svc.collectNodes()
        if (nodes.isEmpty()) {
            out("rootInActiveWindow returned nothing.")
            return@report
        }
        out("root package = ${nodes.first().packageName}")
        out("nodes walked = ${nodes.size}")
        out("")
        out("clickable nodes:")
        var clickable = 0
        nodes.filter { it.isClickable }.forEach { node ->
            clickable++
            out("  " + describe(node))
        }
        if (clickable == 0) out("  (none)")

        out("")
        out("labelled non-clickable nodes (the click target is often their parent):")
        nodes.asSequence()
            .filter { !it.isClickable }
            .filter { node ->
                !node.contentDescription.isNullOrBlank() ||
                    !node.text.isNullOrBlank() ||
                    !node.viewIdResourceName.isNullOrBlank()
            }
            .take(80)
            .forEach { out("  " + describe(it)) }
    }

    /** Third dump: what the phone thinks its voice outputs are. */
    fun dumpAudioOutputs(ctx: Context): String = report("AUDIO OUTPUTS") { out ->
        val devices = AudioOutputs.list(ctx)
        if (devices.isEmpty()) {
            out("AudioManager.getAvailableCommunicationDevices() returned nothing.")
            return@report
        }
        devices.forEach { d ->
            out(
                "  id=${d.id} type=${d.type} (${AudioOutputs.typeName(d.type)}) " +
                    "active=${d.active} label=\"${d.label}\""
            )
        }
    }

    // isChecked is deprecated but is still the only signal telling a toggled-on mic button
    // from a toggled-off one, which is exactly what discovery is looking for.
    @Suppress("DEPRECATION")
    private fun describe(node: AccessibilityNodeInfo): String = buildString {
        append(node.className ?: "?")
        append("  id=").append(node.viewIdResourceName ?: "-")
        append("  desc=").append(node.contentDescription?.let { "\"" + it + "\"" } ?: "-")
        append("  text=").append(node.text?.let { "\"" + it + "\"" } ?: "-")
        append("  clickable=").append(node.isClickable)
        append(" enabled=").append(node.isEnabled)
        append(" checked=").append(node.isChecked)
        append(" selected=").append(node.isSelected)
    }

    private fun report(header: String, body: ((String) -> Unit) -> Unit): String {
        val lines = StringBuilder()
        val out: (String) -> Unit = { line ->
            lines.append(line).append('\n')
            Log.i(TAG, line)
        }
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        out("===== WristCord probe: $header =====")
        out("at $at")
        try {
            body(out)
        } catch (t: Throwable) {
            out("probe threw ${t.javaClass.name}: ${t.message}")
        }
        out("===== end =====")
        return lines.toString()
    }
}
