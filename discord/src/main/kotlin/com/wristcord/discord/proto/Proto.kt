package com.wristcord.discord.proto

import java.io.ByteArrayOutputStream

/**
 * Just enough protobuf to read and write the parts of Discord's settings protos WristCord uses.
 * Unknown fields are skipped on read, which is what makes a partial reader safe: Discord adds
 * fields to these messages all the time.
 */
class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    fun hasMore(): Boolean = pos < end

    /** Returns (fieldNumber, wireType) or null at the end. */
    fun next(): Pair<Int, Int>? {
        if (pos >= end) return null
        val tag = varint()
        return (tag ushr 3).toInt() to (tag and 7).toInt()
    }

    fun varint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }

    fun fixed64(): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun fixed32(): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun bytes(): ByteArray {
        val len = varint().toInt()
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun string(): String = String(bytes(), Charsets.UTF_8)

    /** A reader over the next length-delimited field, advancing past it. */
    fun message(): ProtoReader {
        val len = varint().toInt()
        val r = ProtoReader(buf, pos, pos + len)
        pos += len
        return r
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> varint()
            1 -> pos += 8
            2 -> pos += varint().toInt()
            5 -> pos += 4
            else -> error("unsupported wire type $wireType")
        }
    }

    /** Packed repeated fixed64 (e.g. GuildFolder.guild_ids) - also accepts the unpacked form. */
    fun packedFixed64(wireType: Int): List<Long> {
        if (wireType == 1) return listOf(fixed64())
        val len = varint().toInt()
        val stop = pos + len
        val out = ArrayList<Long>(len / 8)
        while (pos < stop) out += fixed64()
        return out
    }

    /** google.protobuf.*Value wrappers: the value is field 1 of a nested message. */
    fun wrappedString(): String? {
        val m = message()
        var out: String? = ""
        while (true) {
            val (f, w) = m.next() ?: break
            if (f == 1 && w == 2) out = m.string() else m.skip(w)
        }
        return out
    }

    fun wrappedVarint(): Long? {
        val m = message()
        var out = 0L
        while (true) {
            val (f, w) = m.next() ?: break
            if (f == 1 && w == 0) out = m.varint() else m.skip(w)
        }
        return out
    }
}

class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun varint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                out.write(x.toInt())
                return
            }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    fun varintField(field: Int, v: Long) {
        tag(field, 0)
        varint(v)
    }

    fun fixed64Field(field: Int, v: Long) {
        tag(field, 1)
        for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    fun bytesField(field: Int, bytes: ByteArray) {
        tag(field, 2)
        varint(bytes.size.toLong())
        out.write(bytes)
    }

    fun stringField(field: Int, s: String) = bytesField(field, s.toByteArray(Charsets.UTF_8))

    fun messageField(field: Int, build: ProtoWriter.() -> Unit) = bytesField(field, ProtoWriter().apply(build).toByteArray())

    /** google.protobuf.StringValue. */
    fun wrappedString(field: Int, s: String) = messageField(field) { stringField(1, s) }
}
