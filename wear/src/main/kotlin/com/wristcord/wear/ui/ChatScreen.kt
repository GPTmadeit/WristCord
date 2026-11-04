package com.wristcord.wear.ui

import android.app.RemoteInput
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import coil3.compose.AsyncImage
import com.wristcord.discord.Cdn
import com.wristcord.discord.Snowflake
import com.wristcord.discord.model.Emoji
import com.wristcord.discord.model.Message
import com.wristcord.discord.state.PendingMessage
import com.wristcord.discord.state.Snapshot
import com.wristcord.wear.WristCordApp
import com.wristcord.wear.ui.components.Avatar
import com.wristcord.wear.ui.components.Caption
import com.wristcord.wear.ui.components.ListScreen
import com.wristcord.wear.ui.components.MarkdownText
import com.wristcord.wear.ui.components.MentionResolver
import com.wristcord.wear.ui.components.row
import com.wristcord.wear.ui.theme.discord
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * A channel or DM. Newest at the bottom like every chat, the crown scrolls history, reaching
 * the top pulls older messages, and the edge button replies by voice, keyboard or emoji -
 * whatever the watch's own input method offers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(snapshot: Snapshot, channelId: Long, onOpenMessage: (Long) -> Unit, replyTo: Long? = null, onReplyConsumed: () -> Unit = {}) {
    val ctx = LocalContext.current
    val client = WristCordApp.of(ctx).discord
    val scope = rememberCoroutineScope()
    val state by remember(channelId) { client.state.messages.flow(channelId) }.collectAsStateWithLifecycle()
    val channel = snapshot.channels[channelId]
    val c = discord
    val revealed = remember(channelId) { mutableStateMapOf<Long, Boolean>() }
    var replyingTo by remember(channelId) { mutableStateOf(replyTo) }

    LaunchedEffect(channelId) {
        client.openChannel(channelId)
        if (!state.loaded) client.loadMessages(channelId)
    }
    // Read on every device as messages land while the channel is open.
    LaunchedEffect(channelId, channel?.lastMessageId) {
        if (state.loaded) client.ackSoon(channelId)
    }

    val input = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_REPLY) }?.toString()
        val ref = replyingTo
        replyingTo = null
        onReplyConsumed()
        if (!text.isNullOrBlank()) scope.launch { client.send(channelId, text, ref) }
    }

    val title = channel?.let { snapshot.channelTitle(it) } ?: "Chat"
    val launchInput: () -> Unit = {
        val label = replyingTo?.let { id -> state.messages.firstOrNull { it.id == id }?.author?.displayName?.let { "Reply to $it" } }
            ?: if (channel?.isDm == true) "Message $title" else "Message #$title"
        // Every Wear OS build ships the input picker, but a missing one must not crash chat.
        runCatching { input.launch(replyIntent(label)) }
        client.typing(channelId)
    }
    // Arriving from message actions with a reply target: adopt it, then open the input.
    LaunchedEffect(replyTo) {
        if (replyTo != null) {
            replyingTo = replyTo
            launchInput()
        }
    }

    val items = state.messages
    val typing = snapshot.typingIn(channelId)
    val listState = rememberTransformingLazyColumnState()
    // Rows emitted below: title, status line, messages, pending sends, typing line.
    val lastIndex = 1 + items.size + state.pending.size + (if (typing.isNotEmpty()) 1 else 0)
    var pinned by remember(channelId) { mutableStateOf(false) }
    // Follow the conversation while you are at the bottom of it; leave you alone while you
    // read history. Loading older messages never moves you because rows are keyed by id.
    LaunchedEffect(items.lastOrNull()?.id, state.pending.size, typing.isNotEmpty()) {
        if (items.isEmpty() && state.pending.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItems.lastOrNull()?.index ?: 0
        if (!pinned || lastVisible >= lastIndex - 3) {
            listState.scrollToItem(lastIndex)
            pinned = true
        }
    }
    LaunchedEffect(listState, channelId) {
        snapshotFlow { listState.layoutInfo.visibleItems.firstOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { first -> if (first == 0 && state.loaded && state.hasMore && !state.loading) client.loadMessages(channelId, older = true) }
    }

    val resolver = remember(snapshot.users, snapshot.channels, channel) {
        val roles = channel?.guildId?.let { snapshot.guilds[it]?.roles }
        MentionResolver(
            user = { id -> snapshot.users[id]?.displayName },
            role = { id -> roles?.get(id)?.let { it.name to it.color } },
            channel = { id -> snapshot.channels[id]?.let { snapshot.channelTitle(it) } },
        )
    }
    ListScreen(
        state = listState,
        edgeButton = {
            EdgeButton(onClick = launchInput, buttonSize = EdgeButtonSize.Medium) {
                Icon(if (replyingTo != null) Icons.Rounded.Reply else Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (replyingTo != null) " Reply" else " Message", maxLines = 1)
            }
        },
    ) { spec ->
        row(spec, "title") { ListHeader(modifier = modifier) { Text(if (channel?.isDm == true) title else "# $title", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        when {
            !state.loaded && state.error != null -> row(spec, "err") { Caption(state.error ?: "", modifier, color = c.textCritical) }
            !state.loaded -> row(spec, "loading") { Caption("Loading messages…", modifier) }
            state.hasMore -> row(spec, "more") { Caption(if (state.loading) "Loading older…" else "Scroll up for older messages", modifier) }
            else -> row(spec, "start") { Caption(if (channel?.isDm == true) "This is the start of your conversation" else "Welcome to #$title", modifier) }
        }
        for ((i, m) in items.withIndex()) {
            val prev = items.getOrNull(i - 1)
            row(spec, m.id) {
                MessageRow(
                    m = m,
                    grouped = prev != null && grouped(prev, m),
                    snapshot = snapshot,
                    resolver = resolver,
                    revealed = revealed[m.id] == true,
                    modifier = plain,
                    onClick = {
                        if (hasSpoiler(m) && revealed[m.id] != true) revealed[m.id] = true else onOpenMessage(m.id)
                    },
                    onReact = { emoji, add -> scope.launch { client.react(channelId, m.id, emoji, add) } },
                )
            }
        }
        for (p in state.pending) row(spec, "p-${p.nonce}") {
            PendingRow(p, plain, onRetry = {
                client.state.messages.dropPending(channelId, p.nonce)
                scope.launch { client.send(channelId, p.content, p.replyTo) }
            })
        }
        if (typing.isNotEmpty()) row(spec, "typing") {
            Caption(
                when (typing.size) {
                    1 -> "${typing[0].displayName} is typing…"
                    2 -> "${typing[0].displayName} and ${typing[1].displayName} are typing…"
                    else -> "Several people are typing…"
                },
                modifier,
            )
        }
    }
}

private fun grouped(a: Message, b: Message): Boolean =
    a.author?.id == b.author?.id && !b.isSystem && !a.isSystem && b.reference == null &&
        Snowflake.timestampMs(b.id) - Snowflake.timestampMs(a.id) < 7 * 60_000L

private fun hasSpoiler(m: Message) = m.content.contains("||") || m.attachments.any { it.isSpoiler }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageRow(
    m: Message,
    grouped: Boolean,
    snapshot: Snapshot,
    resolver: MentionResolver,
    revealed: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onReact: (Emoji, Boolean) -> Unit,
) {
    val c = discord
    val mentioned = snapshot.me?.let { me -> m.mentions.any { it.id == me.id } || m.mentionEveryone } == true
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (mentioned) c.messageMentioned else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = if (grouped) 1.dp else 4.dp),
    ) {
        if (m.isSystem) {
            Text(systemLine(m, snapshot), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = c.textMuted)
            return@Column
        }
        m.referencedMessage?.let { ref ->
            Text(
                "↱ ${ref.author?.displayName ?: "Message"}: ${ref.content.ifBlank { if (ref.attachments.isNotEmpty()) "Attachment" else "…" }}",
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = c.textMuted,
            )
        }
        if (!grouped) Row(verticalAlignment = Alignment.CenterVertically) {
            val author = m.author
            Avatar(author?.let { Cdn.avatar(it, 32) }, author?.displayName ?: "?", 18.dp)
            Text(
                "  " + (m.member?.nick ?: author?.displayName ?: "Unknown"),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = roleColor(m, snapshot) ?: c.headingPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(" " + time(m.id), fontSize = 9.sp, color = c.textMuted, maxLines = 1)
        }
        if (m.content.isNotBlank()) MarkdownText(m.content, resolver = resolver, revealSpoilers = revealed)
        for (snap in m.snapshots) Row(Modifier.height(IntrinsicSize.Min).padding(top = 2.dp)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(c.borderStrong))
            Column(Modifier.padding(start = 5.dp)) {
                Caption("Forwarded")
                if (snap.message.content.isNotBlank()) MarkdownText(snap.message.content, resolver = resolver, revealSpoilers = revealed)
            }
        }
        if (m.editedTimestamp != null && m.content.isNotBlank()) Text("(edited)", fontSize = 9.sp, color = c.textMuted)
        for (a in m.attachments) {
            if (a.isImage) {
                val ratio = if ((a.width ?: 0) > 0 && (a.height ?: 0) > 0) (a.width!!.toFloat() / a.height!!).coerceIn(0.5f, 2.2f) else 1.4f
                AsyncImage(
                    model = Cdn.preview(a, 360),
                    contentDescription = a.description ?: a.filename,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surfaceHighest)
                        .then(if (a.isSpoiler && !revealed) Modifier.blur(24.dp) else Modifier),
                )
            } else {
                Text(
                    a.durationSecs?.let { "🎤 Voice message · ${it.toInt()}s" } ?: "📎 ${a.filename}",
                    fontSize = 11.sp, color = c.textLink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        for (e in m.embeds.take(2)) EmbedCard(e, resolver)
        for (s in m.stickers) {
            val url = Cdn.sticker(s, 160)
            if (url != null) AsyncImage(model = url, contentDescription = s.name, modifier = Modifier.size(72.dp).padding(top = 2.dp))
            else Caption("Sticker: ${s.name}")
        }
        if (m.reactions.isNotEmpty()) FlowRow(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(top = 3.dp),
        ) {
            for (r in m.reactions) ReactionChip(r.emoji, r.count, r.me, onClick = { onReact(r.emoji, !r.me) })
        }
    }
}

@Composable
fun ReactionChip(emoji: Emoji, count: Int, me: Boolean, onClick: () -> Unit) {
    val c = discord
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (me) c.reactionBackgroundReacted else c.reactionBackground)
            .border(1.dp, if (me) c.reactionBorderReacted else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        if (emoji.id != null) AsyncImage(model = Cdn.emoji(emoji, 32), contentDescription = emoji.name, modifier = Modifier.size(14.dp))
        else Text(emoji.name.orEmpty(), fontSize = 12.sp)
        Text(" $count", fontSize = 11.sp, color = if (me) c.reactionTextReacted else c.reactionText, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmbedCard(e: com.wristcord.discord.model.Embed, resolver: MentionResolver) {
    val c = discord
    if (e.title == null && e.description == null && e.image == null && e.thumbnail == null) return
    Row(
        Modifier
            .padding(top = 3.dp)
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .background(c.embedBackground),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(e.color?.let { Color(0xFF000000.toInt() or it) } ?: c.borderStrong))
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            e.provider?.name?.let { Text(it, fontSize = 9.sp, color = c.textMuted, maxLines = 1) }
            e.author?.name?.let { Text(it, fontSize = 10.sp, color = c.headingPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            e.title?.let { Text(it, fontSize = 11.sp, color = if (e.url != null) c.textLink else c.headingPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            e.description?.let { MarkdownText(it, resolver = resolver, maxLines = 4) }
            for (f in e.fields.take(3)) {
                Text(f.name, fontSize = 10.sp, color = c.headingPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                MarkdownText(f.value, resolver = resolver, maxLines = 2)
            }
            val img = e.image ?: e.thumbnail
            if (img != null) AsyncImage(
                model = Cdn.proxied(img.proxyUrl ?: img.url, img.width, img.height, 320),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(5.dp)),
            )
            e.footer?.text?.let { Text(it, fontSize = 9.sp, color = c.textMuted, maxLines = 1) }
        }
    }
}

@Composable
private fun PendingRow(p: PendingMessage, modifier: Modifier, onRetry: () -> Unit) {
    val c = discord
    Column(modifier.padding(horizontal = 6.dp, vertical = 2.dp).then(if (p.failed != null) Modifier.clickable(onClick = onRetry) else Modifier)) {
        Text(p.content, style = MaterialTheme.typography.bodySmall, color = if (p.failed != null) c.textCritical else c.textMuted)
        if (p.failed != null) Caption("Not sent · tap to retry", color = c.textCritical)
    }
}

@Composable
private fun roleColor(m: Message, snapshot: Snapshot): Color? {
    val gid = m.guildId ?: return null
    val roles = m.member?.roles ?: return null
    val all = snapshot.guilds[gid]?.roles ?: return null
    val top = roles.mapNotNull { all[it] }.filter { it.color != 0 }.maxByOrNull { it.position } ?: return null
    return Color(0xFF000000.toInt() or top.color)
}

private fun time(id: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Snowflake.timestampMs(id)))

private fun systemLine(m: Message, s: Snapshot): String {
    val who = m.author?.displayName ?: "Someone"
    return when (m.type) {
        1 -> "$who added someone to the group"
        2 -> "$who left the group"
        3 -> "$who started a call"
        4 -> "$who changed the channel name"
        6 -> "$who pinned a message"
        7 -> "$who joined the server"
        8, 9, 10, 11 -> "$who boosted the server"
        18 -> "$who started a thread"
        else -> m.content.ifBlank { "System message" }
    }
}

const val KEY_REPLY = "wristcord_reply"

/** The watch's own input picker: voice first, keyboard and emoji a swipe away. */
fun replyIntent(label: String): Intent {
    val input = RemoteInput.Builder(KEY_REPLY)
        .setLabel(label)
        .wearableExtender {
            setEmojisAllowed(true)
            setInputActionType(EditorInfo.IME_ACTION_SEND)
        }
        .build()
    return RemoteInputIntentHelper.createActionRemoteInputIntent().also {
        RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(input))
    }
}
