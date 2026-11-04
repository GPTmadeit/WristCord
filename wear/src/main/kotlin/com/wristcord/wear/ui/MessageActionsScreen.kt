package com.wristcord.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.wristcord.discord.Cdn
import com.wristcord.discord.model.Emoji
import com.wristcord.discord.state.MessageStore
import com.wristcord.discord.state.Snapshot
import com.wristcord.wear.WristCordApp
import com.wristcord.wear.ui.components.Caption
import com.wristcord.wear.ui.components.ListScreen
import com.wristcord.wear.ui.components.MentionResolver
import com.wristcord.wear.ui.components.row
import com.wristcord.wear.ui.theme.discord
import kotlinx.coroutines.launch

/**
 * One message, up close: the full text, a reaction palette built from the emoji you actually
 * use in Discord (its frecency settings, synced from your account), and reply.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageActionsScreen(snapshot: Snapshot, channelId: Long, messageId: Long, onReply: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val client = WristCordApp.of(ctx).discord
    val scope = rememberCoroutineScope()
    val cm by remember(channelId) { client.state.messages.flow(channelId) }.collectAsStateWithLifecycle()
    val message = cm.messages.firstOrNull { it.id == messageId }
    val c = discord
    val palette = remember(snapshot.frecency, snapshot.emojis) { palette(snapshot) }
    val resolver = remember(snapshot.users, snapshot.channels) {
        MentionResolver(user = { snapshot.users[it]?.displayName }, channel = { id -> snapshot.channels[id]?.let { snapshot.channelTitle(it) } })
    }

    ListScreen { spec ->
        if (message == null) {
            row(spec, "gone") { Caption("Message no longer available", modifier) }
            return@ListScreen
        }
        row(spec, "msg") {
            MessageRow(message, grouped = false, snapshot = snapshot, resolver = resolver, revealed = true, modifier = plain, onClick = {}, onReact = { _, _ -> })
        }
        row(spec, "react") { ListHeader(modifier = modifier) { Text("React") } }
        row(spec, "palette") {
            FlowRow(
                modifier = modifier.padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (e in palette) {
                    val mine = message.reactions.any { it.me && MessageStore.sameEmoji(it.emoji, e) }
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (mine) c.reactionBackgroundReacted else c.surfaceHighest)
                            .clickable {
                                scope.launch { client.react(channelId, messageId, e, add = !mine) }
                                onDone()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (e.id != null) AsyncImage(model = Cdn.emoji(e, 48), contentDescription = e.name, modifier = Modifier.size(22.dp))
                        else Text(e.name.orEmpty(), fontSize = 18.sp)
                    }
                }
            }
        }
        row(spec, "reply") {
            Button(
                onClick = onReply,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.Reply, contentDescription = null, tint = c.iconDefault) },
                label = { Text("Reply") },
            )
        }
    }
}

/** Reaction frecency first, then general emoji frecency, then sensible defaults - 12 in all. */
private fun palette(s: Snapshot): List<Emoji> {
    val out = LinkedHashMap<String, Emoji>()
    fun add(key: String) {
        if (out.size >= 12) return
        val id = key.toLongOrNull()
        if (id != null) {
            // Only custom emoji from a server you are in: the rest need Nitro and would fail.
            s.emojis[id]?.let { out["c$id"] = it }
        } else {
            EmojiNames.toUnicode(key)?.let { out["u$it"] = Emoji(null, it) }
        }
    }
    s.frecency.favorites.forEach(::add)
    s.frecency.reactionEmojis.forEach(::add)
    s.frecency.emojis.forEach(::add)
    EmojiNames.defaults.forEach(::add)
    return out.values.toList()
}
