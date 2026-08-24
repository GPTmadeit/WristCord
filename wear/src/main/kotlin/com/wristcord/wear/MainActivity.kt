package com.wristcord.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wristcord.BuildConfig
import com.wristcord.R
import com.wristcord.shared.Capabilities
import com.wristcord.shared.IconVariant
import com.wristcord.shared.UpdateController
import com.wristcord.shared.Updater
import com.wristcord.shared.Wire
import com.wristcord.wear.link.ArtworkStore
import com.wristcord.wear.link.Commands
import com.wristcord.wear.link.SlotConfig
import com.wristcord.wear.link.StateStore
import com.wristcord.wear.ui.WearApp

class MainActivity : ComponentActivity() {

    private var openOutputs by mutableStateOf(false)

    private val updates: UpdateController by lazy {
        UpdateController(
            context = this,
            currentVersion = BuildConfig.VERSION_NAME,
            assetMarker = Updater.ASSET_WATCH,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StateStore.load(this)
        SlotConfig.load(this)
        ArtworkStore.load(this)
        IconVariant.ensureApplied(this)
        Capabilities.ensure(
            ctx = this,
            declared = resources.getStringArray(R.array.android_wear_capabilities),
            expected = Wire.CAPABILITY_WATCH,
        )
        openOutputs = intent?.getBooleanExtra(EXTRA_OPEN_OUTPUTS, false) == true
        setContent {
            WearApp(
                openOutputs = openOutputs,
                updates = updates,
                onOutputsConsumed = { openOutputs = false },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openOutputs = intent.getBooleanExtra(EXTRA_OPEN_OUTPUTS, false)
    }

    override fun onResume() {
        super.onResume()
        // Ask the phone to republish - the watch may have been asleep through the last change.
        Commands.refresh(this)
    }

    companion object {
        /** Set by the Tile's Output button so tapping it lands straight on the device list. */
        const val EXTRA_OPEN_OUTPUTS = "com.wristcord.OPEN_OUTPUTS"
    }
}
