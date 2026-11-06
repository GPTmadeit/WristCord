package com.wristcord.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wristcord.BuildConfig
import com.wristcord.shared.IconVariant
import com.wristcord.shared.UpdateController
import com.wristcord.shared.Updater
import com.wristcord.wear.discord.Demo
import com.wristcord.wear.voice.SlotConfig
import com.wristcord.wear.ui.Destination
import com.wristcord.wear.ui.OpenTarget
import com.wristcord.wear.ui.Screen
import com.wristcord.wear.ui.WearApp

class MainActivity : ComponentActivity() {

    private var destination by mutableStateOf(Destination.NONE)
    private var openTarget by mutableStateOf<OpenTarget?>(null)
    private var debugStack: List<Screen> = emptyList()

    private val updates: UpdateController by lazy {
        UpdateController(
            context = this,
            currentVersion = BuildConfig.VERSION_NAME,
            assetMarker = Updater.ASSET_WATCH,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SlotConfig.load(this)
        IconVariant.ensureApplied(this)
        destination = destinationOf(intent)
        openTarget = openTargetOf(intent)
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("com.wristcord.SELFTEST", false) == true) {
            Thread { com.wristcord.wear.voice.SelfTest.run(this) }.start()
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("com.wristcord.MICTEST", false) == true) {
            Thread { com.wristcord.wear.voice.SelfTest.mics(this) }.start()
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("com.wristcord.ENGINETEST", false) == true) {
            Thread { com.wristcord.wear.voice.SelfTest.engine(this) }.start()
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("com.wristcord.SCOTEST", false) == true) {
            Thread { com.wristcord.wear.voice.ScoProbe.run(this) }.start()
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(Demo.EXTRA, false) == true) {
            Demo.load(
                WristCordApp.of(this),
                theme = intent.getIntExtra("com.wristcord.DEMO_THEME", 4),
                gradient = intent.getIntExtra("com.wristcord.DEMO_GRADIENT", -1).takeIf { it >= 0 },
            )
            val screen = intent.getStringExtra("com.wristcord.DEMO_SCREEN")
            if (screen?.startsWith("incall") == true) WristCordApp.of(this).voiceCall.showDemoCall(1000, 1201)
            debugStack = demoStack(screen)
        }
        debugActions(intent)
        setContent {
            WearApp(
                destination = destination,
                updates = updates,
                onDestinationConsumed = { destination = Destination.NONE },
                openTarget = openTarget,
                onOpenTargetConsumed = { openTarget = null },
                debugStack = debugStack,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destination = destinationOf(intent)
        openTarget = openTargetOf(intent)
        debugActions(intent)
    }

    override fun onStart() {
        super.onStart()
        // On screen = connected. The Account drops the socket a minute after the last holder.
        WristCordApp.of(this).account.acquire(HOLD)
        // The surest moment to start: a foreground app is always allowed to.
        com.wristcord.wear.notify.NotificationService.sync(this)
    }

    override fun onResume() {
        super.onResume()
        // Coming back after the network dropped: skip the backoff wait.
        WristCordApp.of(this).discord.gateway.retryNow()
    }

    override fun onStop() {
        WristCordApp.of(this).account.release(HOLD)
        super.onStop()
    }

    /** Debug builds: feed the demo account fake gateway events, to exercise notifications. */
    private fun debugActions(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val app = WristCordApp.of(this)
        intent.getStringExtra("com.wristcord.DEMO_STATUS")?.let { status ->
            app.discord.state.update { it.copy(settings = it.settings.copy(status = status)) }
        }
        val event = intent.getStringExtra("com.wristcord.DEMO_EVENT")
        val data = intent.getStringExtra("com.wristcord.DEMO_EVENT_JSON")
        if (event != null && data != null) {
            app.discord.state.onDispatch(event, com.wristcord.discord.model.DiscordJson.parseToJsonElement(data))
        }
        if (intent.getBooleanExtra("com.wristcord.SERVICETEST", false)) com.wristcord.wear.notify.NotificationService.sync(this, force = true)
    }

    private fun demoStack(name: String?): List<Screen> = when (name) {
        "dms" -> listOf(Screen.Dms)
        "guild" -> listOf(Screen.Guild(1000))
        "chat" -> listOf(Screen.Guild(1000), Screen.Chat(Demo.GENERAL))
        "image-sources" -> listOf(Screen.Guild(1000), Screen.Chat(Demo.GENERAL), Screen.ImageSources(Demo.GENERAL))
        "gallery" -> listOf(Screen.Guild(1000), Screen.Chat(Demo.GENERAL), Screen.Gallery(Demo.GENERAL))
        "doodle" -> listOf(Screen.Guild(1000), Screen.Chat(Demo.GENERAL), Screen.Doodle(Demo.GENERAL))
        "phone-drop" -> listOf(Screen.Guild(1000), Screen.Chat(Demo.GENERAL), Screen.PhoneDrop(Demo.GENERAL))
        "message" -> listOf(Screen.Guild(1000), Screen.Chat(Demo.GENERAL),
            Screen.Message(Demo.GENERAL, WristCordApp.of(this).discord.state.messages.peek(Demo.GENERAL)?.messages?.lastOrNull()?.id ?: 0))
        "remote" -> listOf(Screen.Remote)
        "people", "voice", "incall" -> listOf(Screen.Guild(1000), Screen.VoiceChannel(1201))
        "incall-remote" -> listOf(Screen.Remote)
        "outputs" -> listOf(Screen.Remote, Screen.Outputs)
        "icons" -> listOf(Screen.Settings, Screen.Icons)
        "settings" -> listOf(Screen.Settings)
        "notifications" -> listOf(Screen.Settings, Screen.Notifications)
        "status" -> listOf(Screen.Settings, Screen.Status)
        "theme" -> listOf(Screen.Settings, Screen.Theme)
        "buttons" -> listOf(Screen.Settings, Screen.Buttons)
        else -> emptyList()
    }

    private fun destinationOf(intent: Intent?): Destination = when {
        intent?.getBooleanExtra(EXTRA_OPEN_OUTPUTS, false) == true -> Destination.OUTPUTS
        intent?.getBooleanExtra(EXTRA_OPEN_REMOTE, false) == true -> Destination.REMOTE
        else -> Destination.NONE
    }

    private fun openTargetOf(intent: Intent?): OpenTarget? {
        val chat = intent?.getLongExtra(EXTRA_OPEN_CHANNEL, 0L) ?: 0L
        if (chat != 0L) return OpenTarget(chat, voice = false)
        val call = intent?.getLongExtra(EXTRA_OPEN_CALL, 0L) ?: 0L
        return if (call != 0L) OpenTarget(call, voice = true) else null
    }

    companion object {
        /** Set by message and call notifications: land on that conversation or call. */
        const val EXTRA_OPEN_CHANNEL = "com.wristcord.OPEN_CHANNEL"
        const val EXTRA_OPEN_CALL = "com.wristcord.OPEN_CALL"

        /** Set by the Tile's Output button so tapping it lands straight on the headset list. */
        const val EXTRA_OPEN_OUTPUTS = "com.wristcord.OPEN_OUTPUTS"

        /** Set by the tile, chip and complication: land on the voice remote. */
        const val EXTRA_OPEN_REMOTE = "com.wristcord.OPEN_REMOTE"

        private const val HOLD = "ui"
    }
}
