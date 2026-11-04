package com.wristcord.discord

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Discord ids are 64-bit snowflakes sent as JSON strings. They are kept as [Long] here: a READY
 * for an account in a hundred servers carries tens of thousands of them, and a boxed String
 * per id is real memory on a watch.
 */
object Snowflake {
    /** 2015-01-01T00:00:00Z, Discord's epoch. */
    const val EPOCH_MS = 1_420_070_400_000L

    fun timestampMs(id: Long): Long = (id ushr 22) + EPOCH_MS

    /** The smallest id that could have been minted at [ms]; handy for "messages after time X". */
    fun fromTimestamp(ms: Long): Long = (ms - EPOCH_MS) shl 22

    /** Nonces in Discord's clients are snowflakes for "now". */
    fun nonce(nowMs: Long = System.currentTimeMillis()): String = fromTimestamp(nowMs).toString()

    fun parse(s: String?): Long? = s?.toLongOrNull()
}

/** Accepts `"123"` or `123`; writes strings, the way Discord sends them. */
object SnowflakeSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Snowflake", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            val p = element as? JsonPrimitive ?: return 0L
            return p.longOrNull ?: p.content.toLongOrNull() ?: 0L
        }
        return decoder.decodeString().toLongOrNull() ?: 0L
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(value.toString())
}

/** Nullable variant for optional ids such as `parent_id` or `last_message_id`. */
object NullableSnowflakeSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NullableSnowflake", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long? {
        if (decoder is JsonDecoder) {
            val p = decoder.decodeJsonElement() as? JsonPrimitive ?: return null
            return p.longOrNull ?: p.content.toLongOrNull()
        }
        return decoder.decodeString().toLongOrNull()
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeString("") else encoder.encodeString(value.toString())
    }
}

/** Lists of ids (`roles`, `recipient_ids`, `mention_roles`). */
object SnowflakeListSerializer : KSerializer<List<Long>> {
    private val delegate = kotlinx.serialization.builtins.ListSerializer(SnowflakeSerializer)
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): List<Long> = delegate.deserialize(decoder)
    override fun serialize(encoder: Encoder, value: List<Long>) = delegate.serialize(encoder, value)
}

/** Permission bitfields are decimal strings that can exceed 2^53, so they are parsed exactly. */
object PermissionSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Permissions", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long {
        if (decoder is JsonDecoder) {
            val p = decoder.decodeJsonElement() as? JsonPrimitive ?: return 0L
            return p.content.toBigIntegerOrNull()?.toLong() ?: 0L
        }
        return decoder.decodeString().toBigIntegerOrNull()?.toLong() ?: 0L
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(value.toULong().toString())
}
