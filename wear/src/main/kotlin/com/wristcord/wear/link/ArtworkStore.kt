package com.wristcord.wear.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The server / channel picture the phone lifted off Discord's call notification, cached on the
 * watch so it can sit behind the controls the way album art sits behind YouTube Music's
 * transport.
 *
 * Assets arrive out of band and have to be pulled with a second call, so we only do that when
 * the fingerprint in [com.wristcord.shared.VoiceState.artworkHash] actually changes.
 */
object ArtworkStore {

    private const val TAG = "WristCord/Artwork"
    private const val PREFS = "wristcord_artwork"
    private const val KEY_HASH = "hash"
    private const val FILE = "artwork.bin"

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap

    @Volatile
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            _bitmap.value = decode(file(ctx))
        }
    }

    fun currentHash(ctx: Context): String = prefs(ctx).getString(KEY_HASH, "").orEmpty()

    /** Resolves [asset] and caches it, unless [hash] says we already have this picture. */
    suspend fun update(ctx: Context, asset: Asset?, hash: String) {
        val app = ctx.applicationContext
        if (hash.isBlank() || asset == null) {
            clear(app)
            return
        }
        if (hash == currentHash(app) && _bitmap.value != null) return

        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                Wearable.getDataClient(app).getFdForAsset(asset).await()
                    .inputStream.use { it.readBytes() }
            }.getOrNull()
        }
        if (bytes == null || bytes.isEmpty()) {
            Log.w(TAG, "could not resolve artwork asset")
            return
        }

        withContext(Dispatchers.IO) {
            runCatching {
                file(app).writeBytes(bytes)
                prefs(app).edit().putString(KEY_HASH, hash).apply()
            }
        }
        _bitmap.value = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        loaded = true
        Log.i(TAG, "artwork updated ($hash, ${bytes.size} bytes)")
    }

    fun clear(ctx: Context) {
        val app = ctx.applicationContext
        _bitmap.value = null
        loaded = true
        runCatching {
            file(app).delete()
            prefs(app).edit().remove(KEY_HASH).apply()
        }
    }

    private fun decode(file: File): Bitmap? = runCatching {
        if (!file.exists()) null else BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
