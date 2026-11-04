package com.wristcord.discord.markdown

/**
 * Discord's message markup, parsed into a small tree the watch renders. Covers what people
 * actually send: emphasis, spoilers, code, quotes, headings, subtext, lists, masked links,
 * mentions, custom emoji and dynamic timestamps.
 */
sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Bold(val children: List<Inline>) : Inline
    data class Italic(val children: List<Inline>) : Inline
    data class Underline(val children: List<Inline>) : Inline
    data class Strike(val children: List<Inline>) : Inline
    data class Spoiler(val children: List<Inline>) : Inline
    data class Code(val text: String) : Inline
    data class Link(val url: String, val children: List<Inline>) : Inline
    data class UserMention(val id: Long) : Inline
    data class ChannelMention(val id: Long) : Inline
    data class RoleMention(val id: Long) : Inline
    /** `@everyone` / `@here`. */
    data class Everyone(val text: String) : Inline
    data class CustomEmoji(val id: Long, val name: String, val animated: Boolean) : Inline
    /** `<t:unix:style>`; style is one of t T d D f F R, default f. */
    data class Timestamp(val unixSeconds: Long, val style: Char) : Inline
    data class SlashCommand(val name: String) : Inline
}

sealed interface Block {
    data class Paragraph(val inlines: List<Inline>) : Block
    data class Heading(val level: Int, val inlines: List<Inline>) : Block
    data class Subtext(val inlines: List<Inline>) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class CodeBlock(val language: String, val code: String) : Block
    data class ListItem(val marker: String, val depth: Int, val inlines: List<Inline>) : Block
}

object Markdown {

    fun parse(text: String): List<Block> = blocks(text.replace("\r\n", "\n"), allowQuote = true)

    /** True when the message is nothing but 1-30 emoji - Discord shows those large. */
    fun isJumboEmoji(blocks: List<Block>): Boolean {
        val p = blocks.singleOrNull() as? Block.Paragraph ?: return false
        var count = 0
        for (i in p.inlines) when (i) {
            is Inline.CustomEmoji -> count++
            is Inline.Text -> {
                val t = i.text.trim()
                if (t.isEmpty()) continue
                if (!t.all { Character.isSurrogate(it) || it == '‍' || it == '️' || Character.getType(it) == Character.OTHER_SYMBOL.toInt() || it == ' ' }) return false
                count += t.codePoints().filter { Character.getType(it) == Character.OTHER_SYMBOL.toInt() }.count().toInt()
            }
            else -> return false
        }
        return count in 1..30
    }

    // ---- blocks ------------------------------------------------------------------------------

    private fun blocks(text: String, allowQuote: Boolean): List<Block> {
        val out = ArrayList<Block>()
        val lines = text.split('\n')
        var i = 0
        val para = StringBuilder()

        fun flush() {
            if (para.isNotEmpty()) {
                out += Block.Paragraph(inlines(para.toString()))
                para.setLength(0)
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val first = trimmed.removePrefix("```")
                    // ```code``` on one line
                    val closeSame = first.indexOf("```")
                    if (closeSame >= 0) {
                        out += Block.CodeBlock("", first.substring(0, closeSame))
                        val rest = first.substring(closeSame + 3)
                        if (rest.isNotBlank()) para.append(rest)
                        i++
                        continue
                    }
                    val lang = first.trim().takeIf { it.matches(Regex("[A-Za-z0-9_+#.-]{1,20}")) } ?: ""
                    val code = StringBuilder(if (lang.isEmpty() && first.isNotEmpty()) first + "\n" else "")
                    i++
                    var closed = false
                    while (i < lines.size) {
                        val l = lines[i]
                        val end = l.indexOf("```")
                        if (end >= 0) {
                            code.append(l.substring(0, end))
                            closed = true
                            val tail = l.substring(end + 3)
                            if (tail.isNotBlank()) para.append(tail)
                            i++
                            break
                        }
                        code.append(l).append('\n')
                        i++
                    }
                    out += Block.CodeBlock(lang, if (closed) code.toString().trimEnd('\n') else code.toString())
                    continue
                }
                allowQuote && trimmed.startsWith(">>> ") || allowQuote && trimmed == ">>>" -> {
                    flush()
                    val rest = (listOf(trimmed.removePrefix(">>>").removePrefix(" ")) + lines.drop(i + 1)).joinToString("\n")
                    out += Block.Quote(blocks(rest, allowQuote = false))
                    i = lines.size
                    continue
                }
                allowQuote && (trimmed.startsWith("> ") || trimmed == ">") -> {
                    flush()
                    val quoted = StringBuilder()
                    while (i < lines.size) {
                        val t = lines[i].trimStart()
                        if (!(t.startsWith("> ") || t == ">")) break
                        if (quoted.isNotEmpty()) quoted.append('\n')
                        quoted.append(t.removePrefix(">").removePrefix(" "))
                        i++
                    }
                    out += Block.Quote(blocks(quoted.toString(), allowQuote = false))
                    continue
                }
                HEADING.matches(trimmed) -> {
                    flush()
                    val m = HEADING.matchEntire(trimmed)!!
                    out += Block.Heading(m.groupValues[1].length, inlines(m.groupValues[2]))
                }
                trimmed.startsWith("-# ") -> {
                    flush()
                    out += Block.Subtext(inlines(trimmed.removePrefix("-# ")))
                }
                LIST.matches(line) -> {
                    flush()
                    val m = LIST.matchEntire(line)!!
                    val depth = m.groupValues[1].length / 2
                    val marker = m.groupValues[2].let { if (it == "-" || it == "*") "•" else it }
                    out += Block.ListItem(marker, depth, inlines(m.groupValues[3]))
                }
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line)
                }
            }
            i++
        }
        flush()
        return out
    }

    // ---- inlines -----------------------------------------------------------------------------

    fun inlines(s: String): List<Inline> = Parser(s).parse(0, s.length)

    private class Parser(val s: String) {
        fun parse(from: Int, to: Int): List<Inline> {
            val out = ArrayList<Inline>()
            val text = StringBuilder()
            fun flushText() {
                if (text.isNotEmpty()) {
                    out += Inline.Text(text.toString())
                    text.setLength(0)
                }
            }
            var i = from
            while (i < to) {
                val c = s[i]
                if (c == '\\' && i + 1 < to && !s[i + 1].isLetterOrDigit() && !s[i + 1].isWhitespace()) {
                    text.append(s[i + 1]); i += 2; continue
                }
                if (c == '`') {
                    val ticks = if (s.startsWith("``", i)) 2 else 1
                    val close = s.indexOf("`".repeat(ticks), i + ticks)
                    if (close in (i + ticks + 1)..to - ticks) {
                        flushText()
                        out += Inline.Code(s.substring(i + ticks, close).let { if (ticks == 2) it.trim() else it })
                        i = close + ticks
                        continue
                    }
                }
                if (c == '<') {
                    val gt = s.indexOf('>', i)
                    if (gt in (i + 2) until to) {
                        val token = s.substring(i, gt + 1)
                        val node = angle(token)
                        if (node != null) {
                            flushText()
                            out += node
                            i = gt + 1
                            continue
                        }
                    }
                }
                var matched = false
                for ((delim, make) in DELIMS) {
                    if (!s.startsWith(delim, i)) continue
                    if (delim == "_" && i > from && s[i - 1].isLetterOrDigit()) continue
                    val close = findClose(delim, i + delim.length, to)
                    if (close < 0) continue
                    if (delim == "_" && close + 1 < to && s[close + 1].isLetterOrDigit()) continue
                    flushText()
                    out += make(parse(i + delim.length, close))
                    i = close + delim.length
                    matched = true
                    break
                }
                if (matched) continue
                if (c == '[') {
                    val link = maskedLink(i, to)
                    if (link != null) {
                        flushText()
                        out += Inline.Link(link.second, parse(i + 1, link.third))
                        i = link.first
                        continue
                    }
                }
                if ((c == 'h') && (s.startsWith("https://", i) || s.startsWith("http://", i)) && (i == from || !s[i - 1].isLetterOrDigit())) {
                    var end = i
                    while (end < to && !s[end].isWhitespace() && s[end] != '<') end++
                    while (end > i && s[end - 1] in ".,:;!?)\"'" && !(s[end - 1] == ')' && s.substring(i, end).count { it == '(' } > s.substring(i, end - 1).count { it == ')' })) end--
                    flushText()
                    val url = s.substring(i, end)
                    out += Inline.Link(url, listOf(Inline.Text(url)))
                    i = end
                    continue
                }
                if (c == '@' && (s.startsWith("@everyone", i) || s.startsWith("@here", i))) {
                    flushText()
                    val t = if (s.startsWith("@everyone", i)) "@everyone" else "@here"
                    out += Inline.Everyone(t)
                    i += t.length
                    continue
                }
                text.append(c)
                i++
            }
            flushText()
            return out
        }

        private fun findClose(delim: String, from: Int, to: Int): Int {
            var j = from
            while (j < to) {
                if (s[j] == '\\') { j += 2; continue }
                if (s[j] == '`') {
                    val close = s.indexOf('`', j + 1)
                    if (close in (j + 1) until to) { j = close + 1; continue }
                }
                if (s.startsWith(delim, j) && j > from) {
                    // `**` must not be the start of `***` closing a nested italic.
                    if (delim == "*" && s.startsWith("**", j) && !s.startsWith("***", j)) { j += 2; continue }
                    return j
                }
                j++
            }
            return -1
        }

        /** Returns (endExclusive, url, textEnd) for `[text](url)`. */
        private fun maskedLink(i: Int, to: Int): Triple<Int, String, Int>? {
            val close = s.indexOf("](", i)
            if (close < 0 || close >= to) return null
            val end = s.indexOf(')', close + 2)
            if (end < 0 || end >= to) return null
            val url = s.substring(close + 2, end).trim().removePrefix("<").removeSuffix(">")
            if (!(url.startsWith("https://") || url.startsWith("http://"))) return null
            return Triple(end + 1, url, close)
        }

        private fun angle(token: String): Inline? {
            USER.matchEntire(token)?.let { return Inline.UserMention(it.groupValues[1].toLong()) }
            CHANNEL.matchEntire(token)?.let { return Inline.ChannelMention(it.groupValues[1].toLong()) }
            ROLE.matchEntire(token)?.let { return Inline.RoleMention(it.groupValues[1].toLong()) }
            EMOJI.matchEntire(token)?.let {
                return Inline.CustomEmoji(it.groupValues[3].toLong(), it.groupValues[2], it.groupValues[1] == "a")
            }
            TIME.matchEntire(token)?.let {
                return Inline.Timestamp(it.groupValues[1].toLong(), it.groupValues[2].firstOrNull() ?: 'f')
            }
            COMMAND.matchEntire(token)?.let { return Inline.SlashCommand(it.groupValues[1]) }
            ANGLE_LINK.matchEntire(token)?.let { val u = it.groupValues[1]; return Inline.Link(u, listOf(Inline.Text(u))) }
            return null
        }
    }

    private val DELIMS: List<Pair<String, (List<Inline>) -> Inline>> = listOf(
        "||" to { c -> Inline.Spoiler(c) },
        "**" to { c -> Inline.Bold(c) },
        "__" to { c -> Inline.Underline(c) },
        "~~" to { c -> Inline.Strike(c) },
        "*" to { c -> Inline.Italic(c) },
        "_" to { c -> Inline.Italic(c) },
    )

    private val HEADING = Regex("^(#{1,3}) (.+)$")
    private val LIST = Regex("^(\\s*)([-*]|\\d{1,3}\\.) (.+)$")
    private val USER = Regex("<@!?(\\d{1,21})>")
    private val CHANNEL = Regex("<#(\\d{1,21})>")
    private val ROLE = Regex("<@&(\\d{1,21})>")
    private val EMOJI = Regex("<(a?):(\\w{2,32}):(\\d{1,21})>")
    private val TIME = Regex("<t:(-?\\d{1,13})(?::([tTdDfFR]))?>")
    private val COMMAND = Regex("</([\\w\\- ]{1,96}):\\d{1,21}>")
    private val ANGLE_LINK = Regex("<(https?://[^>\\s]+)>")
}
