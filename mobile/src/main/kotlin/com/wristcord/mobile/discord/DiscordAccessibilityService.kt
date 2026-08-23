package com.wristcord.mobile.discord

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.wristcord.mobile.util.EventLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Clicks Discord's own in-call controls. Used for the things Discord does not expose as a
 * notification action (deafen on most builds, and the audio output chooser).
 *
 * Requires the phone screen; that is a property of the UI being on screen, not a shortcut we
 * took. The notification path above is the one that survives a dark screen.
 */
class DiscordAccessibilityService : AccessibilityService() {

    private val discordWindowEvents = MutableSharedFlow<Long>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        EventLog.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        EventLog.w(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val interesting = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (interesting && Discord.isDiscord(event.packageName)) {
            discordWindowEvents.tryEmit(System.currentTimeMillis())
        }
    }

    fun isDiscordForeground(): Boolean =
        runCatching { Discord.isDiscord(rootInActiveWindow?.packageName) }.getOrDefault(false)

    /**
     * Waits for Discord's window. Primary signal is [onAccessibilityEvent]; the poll is a
     * safety net for the case where Discord was already in front and no event will arrive.
     */
    suspend fun awaitDiscordWindow(timeoutMs: Long): Boolean {
        if (isDiscordForeground()) return true
        val viaEvent = withTimeoutOrNull(timeoutMs) {
            discordWindowEvents.first()
            true
        }
        if (viaEvent == true && isDiscordForeground()) return true
        // Poll fallback - some launches settle without an event we can see.
        repeat(POLL_STEPS) {
            if (isDiscordForeground()) return true
            delay(POLL_INTERVAL_MS)
        }
        return isDiscordForeground()
    }

    /** Depth-first walk of the live window, newest root each call (nodes go stale fast). */
    fun collectNodes(limit: Int = MAX_NODES): List<AccessibilityNodeInfo> {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>(64)
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty() && out.size < limit) {
            val (node, depth) = stack.removeLast()
            out += node
            if (depth >= MAX_DEPTH) continue
            for (i in node.childCount - 1 downTo 0) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                stack.addLast(child to depth + 1)
            }
        }
        return out
    }

    /**
     * Finds the first node whose view id, contentDescription or text matches [pattern], then
     * climbs to the nearest ancestor that will actually accept a click.
     */
    fun findClickable(pattern: Regex): AccessibilityNodeInfo? {
        val match = collectNodes().firstOrNull { node ->
            val id = node.viewIdResourceName.orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            pattern.containsMatchIn(id) || pattern.containsMatchIn(desc) || pattern.containsMatchIn(text)
        } ?: return null
        return clickableSelfOrAncestor(match)
    }

    fun click(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)

    /** Labels of everything clickable on screen - used to make failures explain themselves. */
    fun clickableLabels(max: Int = 12): List<String> = collectNodes()
        .filter { it.isClickable }
        .mapNotNull { n ->
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: n.text?.toString()?.takeIf { it.isNotBlank() }
                ?: n.viewIdResourceName?.substringAfterLast('/')
        }
        .distinct()
        .take(max)

    private fun clickableSelfOrAncestor(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = start
        var hops = 0
        while (hops < CLICK_ANCESTOR_HOPS) {
            val current = node ?: break
            if (current.isClickable && current.isEnabled) return current
            node = runCatching { current.parent }.getOrNull()
            hops++
        }
        return start.takeIf { it.isEnabled }
    }

    companion object {
        private const val TAG = "WristCord/A11y"
        private const val MAX_NODES = 1200
        private const val MAX_DEPTH = 40
        private const val POLL_STEPS = 20
        private const val POLL_INTERVAL_MS = 150L
        private const val CLICK_ANCESTOR_HOPS = 6

        @Volatile
        private var instance: DiscordAccessibilityService? = null

        fun connected(): DiscordAccessibilityService? = instance

        fun isGranted(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(ctx, DiscordAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
