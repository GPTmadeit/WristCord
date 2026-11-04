package com.wristcord.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.wristcord.discord.Cdn
import com.wristcord.discord.gateway.GatewayState
import com.wristcord.discord.state.Snapshot
import com.wristcord.wear.discord.VoiceSnapshot
import com.wristcord.wear.ui.components.Avatar
import com.wristcord.wear.ui.components.Caption
import com.wristcord.wear.ui.components.GuildIcon
import com.wristcord.wear.ui.components.ListScreen
import com.wristcord.wear.ui.components.MentionBadge
import com.wristcord.wear.ui.components.RowIconSize
import com.wristcord.wear.ui.components.UnreadDot
import com.wristcord.wear.ui.components.row
import com.wristcord.wear.ui.components.statusLabel
import com.wristcord.wear.ui.theme.discord

/**
 * Home is Discord's server rail turned on its side for a round screen: you, the call you are
 * in (if any), direct messages, then your servers in the order and folders you keep on the
 * phone, each with Discord's own unread pip and mention count.
 */
@Composable
fun HomeScreen(
    snapshot: Snapshot,
    gateway: GatewayState,
    voice: VoiceSnapshot,
    onOpenVoice: () -> Unit,
    onOpenDms: () -> Unit,
    onOpenGuild: (Long) -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = discord
    val guilds = remember(snapshot.guilds, snapshot.settings) { snapshot.guildsInOrder() }
    val folderOf = remember(snapshot.settings) {
        buildMap { snapshot.settings.folders.filter { it.id != null && it.name != null }.forEach { f -> f.guildIds.firstOrNull()?.let { put(it, f.name!!) } } }
    }
    val me = snapshot.me
    val status = snapshot.settings.status.ifBlank { "online" }

    ListScreen { spec ->
        if (me != null) row(spec, "me") {
            val custom = snapshot.settings.customStatus?.takeIf { it.isActive() }
            Button(
                onClick = onOpenStatus,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Avatar(Cdn.avatar(me, 64), me.displayName, RowIconSize, status = status) },
                label = { Text(me.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
                secondaryLabel = {
                    Text(
                        custom?.let { listOf(it.emojiName, it.text).filter { s -> s.isNotBlank() }.joinToString(" ") } ?: statusLabel(status),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }

        connectionLine(gateway, snapshot.ready)?.let { line -> row(spec, "conn") { Caption(line, modifier) } }

        if (voice.inCall) row(spec, "voice") {
            Button(
                onClick = onOpenVoice,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.buttonColors(containerColor = c.controlConnected, contentColor = c.controlConnectedText, secondaryContentColor = c.controlConnectedText.copy(alpha = 0.8f)),
                icon = { Icon(if (voice.muted) Icons.Rounded.MicOff else Icons.Rounded.VolumeUp, contentDescription = null) },
                label = { Text(voice.channel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text(voiceLine(voice), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }

        row(spec, "dms") {
            val badge = snapshot.dmBadge()
            Button(
                onClick = onOpenDms,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = {
                    Box {
                        Icon(Icons.Rounded.Forum, contentDescription = null, tint = c.iconBrand, modifier = Modifier.size(RowIconSize))
                        MentionBadge(badge.mentions, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
                    }
                },
                label = { Text("Direct Messages", fontWeight = if (badge.unread) FontWeight.Bold else FontWeight.Normal) },
            )
        }

        if (guilds.isNotEmpty()) row(spec, "servers") { ListHeader(modifier = modifier) { Text("Servers") } }

        for (g in guilds) {
            folderOf[g.id]?.let { name -> row(spec, "folder-${g.id}") { Caption(name.uppercase(), modifier) } }
            row(spec, g.id) {
                val badge = snapshot.guildBadge(g.id)
                val inVoiceHere = voice.inCall && voice.guildId == g.id
                Button(
                    onClick = { onOpenGuild(g.id) },
                    modifier = modifier,
                    transformation = transformation,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Box {
                            GuildIcon(Cdn.guildIcon(g.id, g.icon, 64), g.name, RowIconSize)
                            if (badge.unread && badge.mentions == 0) UnreadDot(Modifier.align(Alignment.CenterStart).offset(x = (-9).dp))
                            MentionBadge(badge.mentions, Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp))
                        }
                    },
                    label = {
                        Text(
                            g.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (badge.unread || badge.mentions > 0) c.interactiveTextActive else c.interactiveText,
                            fontWeight = if (badge.unread || badge.mentions > 0) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    secondaryLabel = if (inVoiceHere) { { Text("In voice", color = c.textVoiceConnected) } } else null,
                )
            }
        }

        if (snapshot.ready && guilds.isEmpty()) row(spec, "none") { Caption("No servers yet", modifier) }

        row(spec, "settings") {
            Button(
                onClick = onOpenSettings,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.Settings, contentDescription = null, tint = c.iconDefault) },
                label = { Text("Settings") },
            )
        }
    }
}

fun connectionLine(g: GatewayState, ready: Boolean): String? = when (g) {
    is GatewayState.Connected -> null
    is GatewayState.Connecting, is GatewayState.Identifying -> if (ready) "Reconnecting…" else "Connecting to Discord…"
    is GatewayState.Waiting -> "Offline · retrying in ${((g.retryAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)}s"
    is GatewayState.Fatal -> g.reason
    is GatewayState.Idle -> if (ready) null else "Connecting to Discord…"
}

fun voiceLine(v: VoiceSnapshot): String = buildList {
    if (v.guild.isNotBlank()) add(v.guild)
    when (v.device) {
        "phone" -> add("on your phone")
        "desktop" -> add("on desktop")
        "web" -> add("in a browser")
        "console" -> add("on console")
        "watch" -> add("on this watch")
    }
    when {
        v.deafened -> add("deafened")
        v.muted -> add("muted")
    }
}.joinToString(" · ")
