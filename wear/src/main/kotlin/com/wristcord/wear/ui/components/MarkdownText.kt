package com.wristcord.wear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.wristcord.discord.Cdn
import com.wristcord.discord.markdown.Block
import com.wristcord.discord.markdown.Inline
import com.wristcord.discord.markdown.Markdown
import com.wristcord.wear.ui.theme.DiscordColors
import com.wristcord.wear.ui.theme.discord
import java.text.DateFormat
import java.util.Date

/** How mentions inside a message turn into names, resolved by the screen that has the state. */
@Immutable
data class MentionResolver(
    val user: (Long) -> String? = { null },
    val role: (Long) -> Pair<String, Int>? = { null },
    val channel: (Long) -> String? = { null },
)

/** A rendered Discord message body: blocks, styled spans and inline custom emoji. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    resolver: MentionResolver = MentionResolver(),
    revealSpoilers: Boolean = false,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = discord.textDefault,
    maxLines: Int = Int.MAX_VALUE,
) {
    val blocks = remember(text) { Markdown.parse(text) }
    val jumbo = remember(blocks) { Markdown.isJumboEmoji(blocks) }
    val c = discord
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (b in blocks) BlockView(b, resolver, revealSpoilers, if (jumbo) style.copy(fontSize = 28.sp, lineHeight = 32.sp) else style, color, c, maxLines, jumbo)
    }
}

@Composable
private fun BlockView(
    b: Block,
    resolver: MentionResolver,
    reveal: Boolean,
    style: TextStyle,
    color: Color,
    c: DiscordColors,
    maxLines: Int,
    jumbo: Boolean,
) {
    when (b) {
        is Block.Paragraph -> InlineText(b.inlines, resolver, reveal, style, color, c, maxLines, emojiSize = if (jumbo) 30.sp else 0.sp)
        is Block.Heading -> InlineText(
            b.inlines, resolver, reveal,
            style.copy(fontWeight = FontWeight.Bold, fontSize = when (b.level) { 1 -> 17.sp; 2 -> 15.sp; else -> 14.sp }),
            c.headingPrimary, c, maxLines,
        )
        is Block.Subtext -> InlineText(b.inlines, resolver, reveal, style.copy(fontSize = 10.sp), c.textMuted, c, maxLines)
        is Block.ListItem -> Row(Modifier.padding(start = (b.depth * 8).dp)) {
            Text("${b.marker} ", style = style, color = c.textMuted)
            InlineText(b.inlines, resolver, reveal, style, color, c, maxLines)
        }
        is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.borderStrong))
            Column(Modifier.padding(start = 6.dp)) {
                for (inner in b.blocks) BlockView(inner, resolver, reveal, style, color, c, maxLines, false)
            }
        }
        is Block.CodeBlock -> Text(
            b.code,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(c.backgroundCode)
                .padding(6.dp),
            style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            color = c.textCode,
        )
    }
}

@Composable
private fun InlineText(
    inlines: List<Inline>,
    resolver: MentionResolver,
    reveal: Boolean,
    style: TextStyle,
    color: Color,
    c: DiscordColors,
    maxLines: Int,
    emojiSize: TextUnit = 0.sp,
) {
    val (annotated, emojis) = remember(inlines, reveal, c, resolver) {
        val found = ArrayList<Inline.CustomEmoji>()
        buildAnnotatedString { append(inlines, resolver, reveal, c, found) } to found
    }
    val size = if (emojiSize.value > 0) emojiSize else (style.fontSize.value * 1.3f).sp
    val inline = remember(annotated, size) {
        emojis.associate { e ->
            "emoji:${e.id}" to InlineTextContent(Placeholder(size, size, PlaceholderVerticalAlign.TextCenter)) {
                AsyncImage(model = Cdn.emoji(e.id, e.animated, 48), contentDescription = ":${e.name}:")
            }
        }
    }
    Text(annotated, style = style, color = color, inlineContent = inline, maxLines = maxLines)
}

private fun AnnotatedString.Builder.append(
    nodes: List<Inline>,
    resolver: MentionResolver,
    reveal: Boolean,
    c: DiscordColors,
    emojis: MutableList<Inline.CustomEmoji>,
) {
    for (n in nodes) when (n) {
        is Inline.Text -> append(n.text)
        is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(n.children, resolver, reveal, c, emojis) }
        is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(n.children, resolver, reveal, c, emojis) }
        is Inline.Underline -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(n.children, resolver, reveal, c, emojis) }
        is Inline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(n.children, resolver, reveal, c, emojis) }
        is Inline.Spoiler -> withStyle(
            if (reveal) SpanStyle(background = c.spoilerRevealed)
            // Hidden: text drawn in its own background colour, exactly as Discord blanks it.
            else SpanStyle(background = c.spoilerHidden, color = c.spoilerHidden)
        ) { append(n.children, resolver, reveal, c, emojis) }
        is Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.backgroundCode, color = c.textCode, fontSize = 0.9.em)) { append(n.text) }
        is Inline.Link -> withStyle(SpanStyle(color = c.textLink)) { append(n.children, resolver, reveal, c, emojis) }
        is Inline.UserMention -> mention("@" + (resolver.user(n.id) ?: "unknown-user"), c)
        is Inline.RoleMention -> {
            val r = resolver.role(n.id)
            if (r != null && r.second != 0) {
                val col = Color(0xFF000000.toInt() or r.second)
                withStyle(SpanStyle(color = col, background = col.copy(alpha = 0.15f))) { append("@" + r.first) }
            } else mention("@" + (r?.first ?: "unknown-role"), c)
        }
        is Inline.ChannelMention -> mention("#" + (resolver.channel(n.id) ?: "unknown"), c)
        is Inline.Everyone -> mention(n.text, c)
        is Inline.SlashCommand -> mention("/" + n.name, c)
        is Inline.CustomEmoji -> {
            emojis += n
            appendInlineContent("emoji:${n.id}", ":${n.name}:")
        }
        is Inline.Timestamp -> withStyle(SpanStyle(background = c.modSubtle)) { append(formatTimestamp(n.unixSeconds, n.style)) }
    }
}

private fun AnnotatedString.Builder.mention(text: String, c: DiscordColors) =
    withStyle(SpanStyle(color = c.mentionForeground, background = c.mentionBackground, fontWeight = FontWeight.Medium)) { append(text) }

/** Discord's `<t:...:style>` formats, in the watch's locale. */
fun formatTimestamp(unixSeconds: Long, style: Char, nowMs: Long = System.currentTimeMillis()): String {
    val ms = unixSeconds * 1000
    val date = Date(ms)
    return when (style) {
        't' -> DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
        'T' -> DateFormat.getTimeInstance(DateFormat.MEDIUM).format(date)
        'd' -> DateFormat.getDateInstance(DateFormat.SHORT).format(date)
        'D' -> DateFormat.getDateInstance(DateFormat.LONG).format(date)
        'F' -> DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(date)
        'R' -> relative(ms - nowMs)
        else -> DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(date)
    }
}

fun relative(deltaMs: Long): String {
    val future = deltaMs > 0
    val s = kotlin.math.abs(deltaMs) / 1000
    val (n, unit) = when {
        s < 45 -> return if (future) "in a few seconds" else "a few seconds ago"
        s < 3600 -> (s / 60).coerceAtLeast(1) to "minute"
        s < 86_400 -> (s / 3600) to "hour"
        s < 2_592_000 -> (s / 86_400) to "day"
        s < 31_536_000 -> (s / 2_592_000) to "month"
        else -> (s / 31_536_000) to "year"
    }
    val label = if (n == 1L) "${if (unit == "hour") "an" else "a"} $unit" else "$n ${unit}s"
    return if (future) "in $label" else "$label ago"
}
