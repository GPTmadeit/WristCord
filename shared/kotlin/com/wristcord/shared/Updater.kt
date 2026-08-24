package com.wristcord.shared

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-update straight from the project's GitHub Releases page.
 *
 * Both APKs use this; [ASSET_PHONE] / [ASSET_WATCH] pick which asset belongs to which device.
 *
 * Safety: the downloaded APK's signing certificate is compared against the certificate of the
 * app that is asking to be updated, and a mismatch aborts before the installer is ever
 * involved. Android would reject the install anyway, but failing here means the user gets a
 * sentence explaining why instead of an opaque system error - and it means WristCord will never
 * hand the package installer something that did not come from the same key as itself.
 */
object Updater {

    const val REPO = "GPTmadeit/WristCord"
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"

    /** Substring identifying each device's asset in the release. */
    const val ASSET_PHONE = "-phone-"
    const val ASSET_WATCH = "-watch-"

    private const val TAG = "WristCord/Update"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    @Immutable
    data class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val assetName: String,
        val assetUrl: String,
        val assetBytes: Long,
    ) {
        val version: String get() = tag.removePrefix("v")
    }

    @Immutable
    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data class UpToDate(val current: String) : Status
        data class Available(val release: Release) : Status
        data class Downloading(val release: Release, val percent: Int) : Status
        data class Verifying(val release: Release) : Status
        data class Installing(val release: Release) : Status
        data class Failed(val message: String) : Status
    }

    /**
     * Returns the newest release when it is newer than [currentVersion], or null when we are
     * already current. Throws nothing - failures come back as [Result.failure].
     */
    suspend fun check(currentVersion: String, assetMarker: String): Result<Release?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = get(API)
                val json = JSONObject(body)
                val tag = json.optString("tag_name").ifBlank { error("Release has no tag") }
                val assets = json.optJSONArray("assets") ?: error("Release has no assets")

                var name = ""
                var url = ""
                var size = 0L
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.optString("name")
                    // "fallback" assets are debug builds attached for manual recovery; never
                    // let the auto-updater pick one up.
                    if (assetName.contains("fallback", ignoreCase = true)) continue
                    if (assetName.contains(assetMarker) && assetName.endsWith(".apk")) {
                        name = assetName
                        url = asset.optString("browser_download_url")
                        size = asset.optLong("size")
                        break
                    }
                }
                if (url.isBlank()) error("No ${assetMarker.trim('-')} APK in release $tag")

                val release = Release(
                    tag = tag,
                    title = json.optString("name").ifBlank { tag },
                    notes = json.optString("body"),
                    assetName = name,
                    assetUrl = url,
                    assetBytes = size,
                )
                if (isNewer(release.version, currentVersion)) release else null
            }.onFailure { Log.w(TAG, "check failed", it) }
        }

    /** Downloads the asset to the cache dir, reporting 0..100 as it goes. */
    suspend fun download(
        ctx: Context,
        release: Release,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, release.assetName)

            val connection = open(release.assetUrl)
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val total = if (release.assetBytes > 0) release.assetBytes else connection.contentLengthLong
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            connection.disconnect()
            target
        }.onFailure { Log.w(TAG, "download failed", it) }
    }

    /**
     * True when [apk] is signed by the same certificate as the running app. A false here means
     * the download is not a build of this app and must not be installed.
     */
    fun signedBySameKey(ctx: Context, apk: File): Boolean {
        val mine = ownCertificateSha256(ctx) ?: return false
        val theirs = apkCertificateSha256(ctx, apk) ?: return false
        Log.i(TAG, "signature check: running=$mine download=$theirs")
        return mine.equals(theirs, ignoreCase = true)
    }

    fun ownCertificateSha256(ctx: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageInfo(
            ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES
        )
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(::sha256)
    }.getOrNull()

    private fun apkCertificateSha256(ctx: Context, apk: File): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageArchiveInfo(
            apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES
        )
        info?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(::sha256)
    }.getOrNull()

    /**
     * Hands [apk] to the system package installer. The user still confirms in the system UI;
     * this only stages the bytes.
     */
    suspend fun install(ctx: Context, apk: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply { setAppPackageName(ctx.packageName) }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(SESSION_ENTRY, 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val callback = PendingIntent.getBroadcast(
                    ctx,
                    sessionId,
                    Intent(InstallResultReceiver.ACTION).setPackage(ctx.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        }.onFailure { Log.w(TAG, "install failed", it) }
    }

    fun canRequestInstalls(ctx: Context): Boolean =
        runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** Semantic-ish compare that tolerates "v" prefixes and differing segment counts. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun parts(version: String): List<Int> = version
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    private fun get(url: String): String = open(url, accept = "application/vnd.github+json").let {
        val text = it.inputStream.bufferedReader().use { reader -> reader.readText() }
        it.disconnect()
        text
    }

    private fun open(url: String, accept: String? = null): HttpURLConnection {
        var current = URL(url)
        var redirects = 0
        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "WristCord")
                accept?.let { setRequestProperty("Accept", it) }
            }
            val code = connection.responseCode
            if (code in 300..399 && redirects < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) error("Redirect without Location")
                // GitHub sends release assets to a signed S3 URL; only ever follow to https.
                val next = URL(current, location)
                require(next.protocol == "https") { "Refusing non-HTTPS redirect" }
                current = next
                redirects++
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                error("GitHub returned HTTP $code")
            }
            return connection
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private const val MAX_REDIRECTS = 5
    private const val SESSION_ENTRY = "wristcord.apk"
}

/**
 * Receives the package installer's verdict. The interesting case is
 * [PackageInstaller.STATUS_PENDING_USER_ACTION], which is the system asking us to show its own
 * confirmation dialog - that is the point at which the user actually approves the update.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { report(context, "Could not open the installer: ${it.message}") }
                } else {
                    report(context, "Installer asked for confirmation but sent no intent")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> report(context, null)

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                report(context, "Update failed ($status)${message?.let { ": $it" } ?: ""}")
            }
        }
    }

    private fun report(context: Context, error: String?) {
        Log.i("WristCord/Update", error ?: "install succeeded")
        listener?.invoke(context, error)
    }

    companion object {
        const val ACTION = "com.wristcord.INSTALL_RESULT"

        /** Set by whichever module wants to surface the outcome in its own UI. */
        @Volatile
        var listener: ((Context, String?) -> Unit)? = null
    }
}
