package com.wristcord.discord

import com.wristcord.discord.gateway.ZlibStream
import com.wristcord.discord.markdown.Block
import com.wristcord.discord.markdown.Inline
import com.wristcord.discord.markdown.Markdown
import com.wristcord.discord.model.Overwrite
import com.wristcord.discord.model.Role
import com.wristcord.discord.model.Channel
import com.wristcord.discord.proto.Frecency
import com.wristcord.discord.proto.ProtoWriter
import com.wristcord.discord.proto.Theme
import com.wristcord.discord.proto.UserSettings
import com.wristcord.discord.remoteauth.RemoteAuth
import com.wristcord.discord.state.Permissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class ZlibStreamTest {

    private fun syncFlushed(deflater: Deflater, text: String): ByteArray {
        deflater.setInput(text.toByteArray())
        val buf = ByteArray(1 shl 16)
        val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
        return buf.copyOf(n)
    }

    @Test
    fun `shared context across payloads and split frames`() {
        val deflater = Deflater()
        val z = ZlibStream()
        val a = syncFlushed(deflater, """{"op":10,"d":{"heartbeat_interval":41250}}""")
        val b = syncFlushed(deflater, """{"op":11,"d":null}""")
        assertEquals("""{"op":10,"d":{"heartbeat_interval":41250}}""", String(z.feed(a)!!))
        // A payload split over two WebSocket frames only decodes once the suffix arrives.
        assertNull(z.feed(b.copyOfRange(0, 3)))
        assertEquals("""{"op":11,"d":null}""", String(z.feed(b.copyOfRange(3, b.size))!!))
    }
}

class SettingsProtoTest {

    private fun sample(): ByteArray = ProtoWriter().apply {
        // status (11): status "dnd", custom status "On my wrist" with an emoji.
        messageField(11) {
            wrappedString(1, "dnd")
            messageField(2) {
                stringField(1, "On my wrist")
                stringField(3, "⌚")
            }
        }
        // appearance (13): theme MIDNIGHT (4), gradient preset 9 (Chroma Glow).
        messageField(13) {
            varintField(1, 4)
            messageField(3) { messageField(2) { varintField(1, 9) } }
        }
        // guild_folders (14): one real folder of two guilds, one loose guild.
        messageField(14) {
            messageField(1) {
                bytesField(1, packed(222L, 333L))
                messageField(2) { varintField(1, 77) }
                wrappedString(3, "Gaming")
            }
            messageField(1) {
                bytesField(1, packed(111L))
            }
        }
        // an unknown field Discord might add later must be skipped
        stringField(99, "future")
    }.toByteArray()

    private fun packed(vararg ids: Long): ByteArray {
        val out = ByteArray(ids.size * 8)
        ids.forEachIndexed { i, v -> for (b in 0 until 8) out[i * 8 + b] = ((v ushr (8 * b)) and 0xFF).toByte() }
        return out
    }

    @Test
    fun `parses theme gradient status and folders`() {
        val s = UserSettings.parse(sample())
        assertEquals(Theme.ONYX, s.theme)
        assertEquals(9, s.gradientPreset)
        assertEquals("dnd", s.status)
        assertEquals("On my wrist", s.customStatus?.text)
        assertEquals("⌚", s.customStatus?.emojiName)
        assertEquals(listOf(222L, 333L, 111L), s.guildOrder())
        assertEquals("Gaming", s.folders[0].name)
        assertEquals(77L, s.folders[0].id)
        assertNull(s.folders[1].id)
    }

    @Test
    fun `proto theme names map the way Discord's UI does`() {
        assertEquals(Theme.DARK, Theme.fromProto(0))
        assertEquals(Theme.ASH, Theme.fromProto(1))
        assertEquals(Theme.LIGHT, Theme.fromProto(2))
        assertEquals(Theme.DARK, Theme.fromProto(3))
        assertEquals(Theme.ONYX, Theme.fromProto(4))
    }

    @Test
    fun `status patch round-trips`() {
        val patch = UserSettings.statusPatch("idle")
        assertEquals("idle", UserSettings.parseBase64(patch).status)
        val merged = UserSettings.merge(UserSettings.parse(sample()), Base64.getDecoder().decode(patch))
        assertEquals("idle", merged.status)
        assertEquals(Theme.ONYX, merged.theme) // untouched by a status-only partial
    }

    @Test
    fun `frecency ranks by score`() {
        fun item(score: Long) = ProtoWriter().apply { varintField(1, 3); varintField(4, score) }.toByteArray()
        val bytes = ProtoWriter().apply {
            messageField(6) {
                messageField(1) { stringField(1, "thumbsup"); bytesField(2, item(10)) }
                messageField(1) { stringField(1, "fire"); bytesField(2, item(90)) }
                messageField(1) { stringField(1, "1087779238483484754"); bytesField(2, item(40)) }
            }
        }.toByteArray()
        val f = Frecency.parseBase64(Base64.getEncoder().encodeToString(bytes))
        assertEquals(listOf("fire", "1087779238483484754", "thumbsup"), f.emojis)
    }
}

class MarkdownTest {

    @Test
    fun `inline styles nest`() {
        val i = Markdown.inlines("hi **bold _and italic_** ~~gone~~ ||secret|| `x*y`")
        assertEquals(Inline.Text("hi "), i[0])
        val bold = i[1] as Inline.Bold
        assertEquals(Inline.Text("bold "), bold.children[0])
        assertTrue(bold.children[1] is Inline.Italic)
        assertTrue(i.any { it is Inline.Strike })
        assertTrue(i.any { it is Inline.Spoiler })
        assertTrue(i.contains(Inline.Code("x*y")))
    }

    @Test
    fun `snake_case is not italic`() {
        assertEquals(listOf(Inline.Text("some_snake_case word")), Markdown.inlines("some_snake_case word"))
    }

    @Test
    fun `mentions emoji timestamps and links`() {
        val i = Markdown.inlines("<@123456789012345678> in <#223456789012345678> <@&323456789012345678> <a:party:423456789012345678> <t:1700000000:R> https://discord.com/x. [docs](https://docs.discord.food)")
        assertTrue(i.contains(Inline.UserMention(123456789012345678)))
        assertTrue(i.contains(Inline.ChannelMention(223456789012345678)))
        assertTrue(i.contains(Inline.RoleMention(323456789012345678)))
        assertTrue(i.contains(Inline.CustomEmoji(423456789012345678, "party", true)))
        assertTrue(i.contains(Inline.Timestamp(1700000000, 'R')))
        assertTrue(i.any { it is Inline.Link && it.url == "https://discord.com/x" })
        assertTrue(i.any { it is Inline.Link && it.url == "https://docs.discord.food" && it.children == listOf(Inline.Text("docs")) })
    }

    @Test
    fun `blocks`() {
        val b = Markdown.parse("# Title\n> quoted\n> more\n```kt\nval x = 1\n```\n- item\n-# small")
        assertEquals(1, (b[0] as Block.Heading).level)
        assertEquals(1, (b[1] as Block.Quote).blocks.size)
        assertEquals(Block.CodeBlock("kt", "val x = 1"), b[2])
        assertTrue(b[3] is Block.ListItem)
        assertTrue(b[4] is Block.Subtext)
    }

    @Test
    fun `jumbo emoji`() {
        assertTrue(Markdown.isJumboEmoji(Markdown.parse("🔥🔥")))
        assertTrue(Markdown.isJumboEmoji(Markdown.parse("<:wave:423456789012345678>")))
        assertFalse(Markdown.isJumboEmoji(Markdown.parse("ok 🔥")))
    }
}

class PermissionsTest {
    private val guild = 1L
    private val me = 10L
    private val roles = mapOf(
        guild to Role(guild, "@everyone", permissions = Permissions.VIEW_CHANNEL or Permissions.SEND_MESSAGES),
        5L to Role(5L, "mods", permissions = 0),
    )

    @Test
    fun `everyone overwrite hides, role overwrite shows`() {
        val secret = Channel(100, overwrites = listOf(Overwrite(guild, 0, deny = Permissions.VIEW_CHANNEL)))
        assertFalse(Permissions.canView(secret, guild, 99, me, emptyList(), roles))
        val modsOnly = secret.copy(overwrites = secret.overwrites + Overwrite(5L, 0, allow = Permissions.VIEW_CHANNEL))
        assertTrue(Permissions.canView(modsOnly, guild, 99, me, listOf(5L), roles))
        val memberDenied = modsOnly.copy(overwrites = modsOnly.overwrites + Overwrite(me, 1, deny = Permissions.VIEW_CHANNEL))
        assertFalse(Permissions.canView(memberDenied, guild, 99, me, listOf(5L), roles))
        // The owner sees everything.
        assertTrue(Permissions.canView(memberDenied, guild, me, me, emptyList(), roles))
    }
}

class RemoteAuthCryptoTest {

    @Test
    fun `decrypts OAEP-SHA256 and fingerprints the SPKI key`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, pair.public,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        val ct = Base64.getEncoder().encodeToString(cipher.doFinal("123:0:abc:neo".toByteArray()))
        val pt = String(RemoteAuth.decrypt(pair, ct))
        assertEquals("123:0:abc:neo", pt)
        val user = RemoteAuth.parseUser(pt)
        assertEquals(123L, user.id)
        assertEquals("neo", user.username)
        val fp = RemoteAuth.fingerprint(pair)
        assertEquals(43, fp.length) // 32 bytes, base64url, no padding
        assertFalse(fp.contains('='))
    }
}

class IdentityTest {
    @Test
    fun `super properties mirror the Android client`() {
        val p = ClientIdentity.forTests().superProperties
        assertEquals("\"Discord Android\"", p["browser"].toString())
        assertEquals("\"351.11 - rn\"", p["client_version"].toString())
        assertEquals("35101100000000", p["client_build_number"].toString())
        assertEquals("Discord-Android/351011;RNA", Discord.USER_AGENT)
    }

    @Test
    fun snowflakes() {
        assertEquals(1_420_070_400_000L, Snowflake.timestampMs(0))
        val id = Snowflake.fromTimestamp(1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, Snowflake.timestampMs(id))
    }
}
