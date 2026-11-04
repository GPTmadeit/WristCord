package com.wristcord.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.wristcord.discord.Cdn
import com.wristcord.discord.model.Channel
import com.wristcord.discord.model.ChannelType
import com.wristcord.discord.state.Snapshot
import com.wristcord.wear.ui.components.Avatar
import com.wristcord.wear.ui.components.Caption
import com.wristcord.wear.ui.components.ListScreen
import com.wristcord.wear.ui.components.MentionBadge
import com.wristcord.wear.ui.components.RowIconSize
import com.wristcord.wear.ui.components.UnreadDot
import com.wristcord.wear.ui.components.row
import com.wristcord.wear.ui.theme.discord

/** Direct messages and group DMs, most recent first - the phone's DM list, on the wrist. */
@Composable
fun DmListScreen(snapshot: Snapshot, onOpen: (Long) -> Unit) {
    val dms = remember(snapshot.channels) { snapshot.dms }
    val c = discord
    ListScreen { spec ->
        row(spec, "h") { ListHeader(modifier = modifier) { Text("Direct Messages") } }
        if (dms.isEmpty()) row(spec, "none") { Caption(if (snapshot.ready) "No conversations yet" else "Loading…", modifier) }
        for (ch in dms) row(spec, ch.id) {
            val badge = snapshot.channelBadge(ch.id)
            val recipient = ch.recipientIds.firstOrNull()?.let { snapshot.users[it] }
            Button(
                onClick = { onOpen(ch.id) },
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = {
                    Box {
                        when {
                            ch.type == ChannelType.GROUP_DM && ch.icon != null ->
                                Avatar(Cdn.groupIcon(ch), snapshot.channelTitle(ch), RowIconSize)
                            ch.type == ChannelType.GROUP_DM ->
                                Icon(Icons.Rounded.Groups, contentDescription = null, tint = c.iconDefault, modifier = Modifier.size(RowIconSize))
                            recipient != null -> Avatar(Cdn.avatar(recipient, 64), recipient.displayName, RowIconSize)
                            else -> Avatar(null, snapshot.channelTitle(ch), RowIconSize)
                        }
                        MentionBadge(badge.mentions, Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp))
                    }
                },
                label = {
                    Text(
                        snapshot.channelTitle(ch),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (badge.unread) FontWeight.Bold else FontWeight.Normal,
                        color = if (badge.unread) c.interactiveTextActive else c.interactiveText,
                    )
                },
                secondaryLabel = if (ch.type == ChannelType.GROUP_DM) { { Text("${ch.recipientIds.size + 1} members", maxLines = 1) } } else null,
            )
        }
    }
}

/**
 * A server's channels as Discord lays them out: uncategorised first, then each category in
 * order, text channels above voice channels inside it. Channels the account cannot see were
 * already dropped by the permission check.
 */
@Composable
fun GuildScreen(snapshot: Snapshot, guildId: Long, onOpenChannel: (Long) -> Unit, onOpenVoiceChannel: (Long) -> Unit) {
    val guild = snapshot.guilds[guildId]
    val c = discord
    val sections = remember(snapshot.channels, snapshot.hidden, guild) { sections(snapshot.channelsOf(guildId)) }

    ListScreen { spec ->
        row(spec, "h") { ListHeader(modifier = modifier) { Text(guild?.name ?: "Server", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        if (guild == null) {
            row(spec, "gone") { Caption("This server is not available right now", modifier) }
            return@ListScreen
        }
        for ((category, channels) in sections) {
            if (category != null) row(spec, "cat-${category.id}") { Caption((category.name ?: "").uppercase(), modifier) }
            for (ch in channels) row(spec, ch.id) {
                val badge = snapshot.channelBadge(ch.id)
                val muted = snapshot.isChannelMuted(ch)
                val voiceHere = if (ChannelType.isVoice(ch.type)) snapshot.voiceIn(ch.id) else emptyList()
                Button(
                    onClick = {
                        when {
                            ChannelType.isVoice(ch.type) -> onOpenVoiceChannel(ch.id)
                            ch.type == ChannelType.GUILD_FORUM || ch.type == ChannelType.GUILD_MEDIA -> Unit
                            else -> onOpenChannel(ch.id)
                        }
                    },
                    modifier = modifier,
                    transformation = transformation,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Box {
                            Icon(channelIcon(ch), contentDescription = null, tint = if (muted) c.interactiveMuted else c.channelIcon, modifier = Modifier.size(20.dp))
                            if (badge.unread && badge.mentions == 0) UnreadDot(Modifier.align(Alignment.CenterStart).offset(x = (-10).dp))
                            MentionBadge(badge.mentions, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp))
                        }
                    },
                    label = {
                        Text(
                            ch.name ?: "channel",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = when {
                                muted -> c.channelNameMuted
                                badge.unread || badge.mentions > 0 -> c.interactiveTextActive
                                else -> c.channelName
                            },
                            fontWeight = if (badge.unread || badge.mentions > 0) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    secondaryLabel = when {
                        voiceHere.isNotEmpty() -> {
                            {
                                Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                                    voiceHere.take(5).forEach { vs ->
                                        val u = snapshot.users[vs.userId]
                                        Avatar(u?.let { Cdn.avatar(it, 32) }, u?.displayName ?: "?", 14.dp)
                                    }
                                }
                                Text("  ${voiceHere.size}", color = c.textVoiceConnected)
                            }
                        }
                        ch.type == ChannelType.GUILD_FORUM || ch.type == ChannelType.GUILD_MEDIA -> { { Text("Open on your phone", maxLines = 1) } }
                        else -> null
                    },
                )
            }
        }
    }
}

private fun sections(channels: List<Channel>): List<Pair<Channel?, List<Channel>>> {
    val categories = channels.filter { it.type == ChannelType.GUILD_CATEGORY }.sortedWith(compareBy({ it.position }, { it.id }))
    val leaves = channels.filter { it.type != ChannelType.GUILD_CATEGORY && it.type !in THREADS }
    val order = compareBy<Channel>({ if (ChannelType.isVoice(it.type)) 1 else 0 }, { it.position }, { it.id })
    val out = ArrayList<Pair<Channel?, List<Channel>>>()
    val loose = leaves.filter { it.parentId == null || categories.none { c -> c.id == it.parentId } }.sortedWith(order)
    if (loose.isNotEmpty()) out += null to loose
    for (cat in categories) {
        val inside = leaves.filter { it.parentId == cat.id }.sortedWith(order)
        if (inside.isNotEmpty()) out += cat to inside
    }
    return out
}

private val THREADS = setOf(ChannelType.PUBLIC_THREAD, ChannelType.PRIVATE_THREAD, ChannelType.ANNOUNCEMENT_THREAD)

fun channelIcon(ch: Channel): ImageVector = when (ch.type) {
    ChannelType.GUILD_VOICE -> Icons.Rounded.VolumeUp
    ChannelType.GUILD_STAGE_VOICE -> Icons.Rounded.Mic
    ChannelType.GUILD_ANNOUNCEMENT -> Icons.Rounded.Campaign
    ChannelType.GUILD_FORUM, ChannelType.GUILD_MEDIA -> Icons.Rounded.Forum
    else -> Icons.Rounded.Tag
}
