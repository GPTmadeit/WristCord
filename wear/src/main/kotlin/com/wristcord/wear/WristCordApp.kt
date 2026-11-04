package com.wristcord.wear

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.wristcord.discord.DiscordClient
import com.wristcord.wear.discord.Account
import com.wristcord.wear.discord.DeviceIdentity
import com.wristcord.wear.discord.ThemePrefs
import com.wristcord.wear.discord.TokenVault
import com.wristcord.wear.discord.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide owner of the Discord client and the signed-in account. */
class WristCordApp : Application(), SingletonImageLoader.Factory {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val discord: DiscordClient by lazy { DiscordClient(DeviceIdentity.build(this), scope) }

    val account: Account by lazy { Account(this, discord, TokenVault(this), scope) }

    override fun onCreate() {
        super.onCreate()
        ThemePrefs.load(this)
        Voice.load(this)
    }

    /** Avatars, icons and emoji ride the client's own connection pool. */
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { discord.http }))
            add(AnimatedImageDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("images"))
                .maxSizeBytes(32L * 1024 * 1024)
                .build()
        }
        .crossfade(false)
        .build()

    companion object {
        fun of(ctx: Context): WristCordApp = ctx.applicationContext as WristCordApp
    }
}
