package com.wristcord.wear.voice

/**
 * Discord's voice server in its MLS role (the external sender), from native/dave's `testing`
 * feature: a host build of libwristcord_dave that Gradle builds for the unit tests only.
 */
class TestExternalSender(groupId: Long) : AutoCloseable {
    private var handle = nativeNew(groupId)

    /** dave_mls_external_sender_package (25). */
    val externalSenderPackage: ByteArray get() = nativePackage(handle)

    /** The proposals of an append dave_mls_proposals (27) adding the owner of [keyPackage]. */
    fun proposeAdd(epoch: Long, keyPackage: ByteArray): ByteArray = nativeProposeAdd(handle, epoch, keyPackage)

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary("wristcord_dave")
        }

        /** Splits a dave_mls_commit_welcome (28) the way the server does: commit, then welcome. */
        fun splitCommitWelcome(data: ByteArray): Pair<ByteArray, ByteArray?> {
            val n = nativeFirstMessageLength(data)
            require(n > 0) { "not an MLS message" }
            return data.copyOfRange(0, n) to data.copyOfRange(n, data.size).takeIf { it.isNotEmpty() }
        }

        @JvmStatic private external fun nativeNew(groupId: Long): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativePackage(handle: Long): ByteArray
        @JvmStatic private external fun nativeProposeAdd(handle: Long, epoch: Long, keyPackage: ByteArray): ByteArray
        @JvmStatic private external fun nativeFirstMessageLength(data: ByteArray): Int
    }
}
