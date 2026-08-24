package com.wristcord.wear.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wristcord.R
import com.wristcord.shared.Brand
import com.wristcord.shared.Updater

/**
 * Updates pulled straight from the project's GitHub releases. The watch downloads its own
 * asset; the phone downloads the phone one. Neither device can update the other, so both apps
 * carry this screen.
 */
@Composable
fun UpdateScreen(
    status: Updater.Status,
    currentVersion: String,
    canInstall: Boolean,
    onCheck: () -> Unit,
    onInstall: (Updater.Release) -> Unit,
) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()

    ScreenScaffold(scrollState = scroll) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(contentPadding)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ListHeader { Text("Updates") }

            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = Color(Brand.BLUE),
                modifier = Modifier.size(28.dp),
            )

            Text(
                "Installed: $currentVersion",
                style = MaterialTheme.typography.labelSmall,
                color = Color(Brand.ON_SURFACE_VARIANT),
                textAlign = TextAlign.Center,
            )

            when (status) {
                is Updater.Status.Idle -> {
                    Body("Check GitHub for a newer watch build.")
                    CheckButton(onCheck)
                }

                is Updater.Status.Checking -> Body("Checking GitHub...")

                is Updater.Status.UpToDate -> {
                    Body("You are on the latest release.")
                    CheckButton(onCheck, label = "Check again")
                }

                is Updater.Status.Available -> {
                    Body("${status.release.tag} is available.")
                    Text(
                        status.release.assetName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(Brand.OUTLINE),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!canInstall) {
                        Body("Allow WristCord to install apps first.")
                        Button(
                            onClick = { ctx.openInstallPermission() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            label = { Text("Open setting") },
                        )
                    }
                    Button(
                        onClick = { onInstall(status.release) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (canInstall) "Download & install" else "Try anyway") },
                    )
                }

                is Updater.Status.Downloading -> {
                    Body("Downloading ${status.percent}%")
                    LinearProgressIndicator(
                        progress = { status.percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }

                is Updater.Status.Verifying -> Body("Checking the signature matches this app...")

                is Updater.Status.Installing -> Body("Handing over to the installer. Confirm on screen.")

                is Updater.Status.Failed -> {
                    Text(
                        status.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    CheckButton(onCheck, label = "Try again")
                }
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Color(Brand.ON_SURFACE),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun CheckButton(onCheck: () -> Unit, label: String = "Check for updates") {
    Button(
        onClick = onCheck,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        label = { Text(label) },
    )
}

private fun android.content.Context.openInstallPermission() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
