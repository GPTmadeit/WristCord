package com.wristcord.wear.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButtonColors
import androidx.wear.compose.material3.RadioButtonDefaults
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.wristcord.wear.ui.theme.discord

/**
 * The selected row is filled with Discord's brand colour, which is also the theme's `primary`,
 * so the radio dot is drawn in the row's text colour to stay visible.
 */
@Composable
fun radioColors(): RadioButtonColors = RadioButtonDefaults.radioButtonColors(selectedControlColor = discord.controlPrimaryText)

/** Round avatar with an optional Discord status dot cut into its corner. */
@Composable
fun Avatar(
    url: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    status: String? = null,
) {
    val c = discord
    Box(modifier.size(size)) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(c.surfaceHighest, CircleShape),
            )
        } else {
            Initials(name, size, CircleShape)
        }
        if (status != null) {
            val dot = size * 0.34f
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = dot * 0.12f, y = dot * 0.12f)
                    .size(dot)
                    .clip(CircleShape)
                    .background(c.baseLowest, CircleShape)
                    .padding(dot * 0.18f)
                    .clip(CircleShape)
                    .background(statusColor(status), CircleShape)
            )
        }
    }
}

@Composable
fun statusColor(status: String): Color {
    val c = discord
    return when (status) {
        "online" -> c.iconStatusOnline
        "idle" -> c.iconStatusIdle
        "dnd" -> c.iconStatusDnd
        else -> c.iconStatusOffline
    }
}

fun statusLabel(status: String): String = when (status) {
    "online" -> "Online"
    "idle" -> "Idle"
    "dnd" -> "Do Not Disturb"
    "invisible" -> "Invisible"
    else -> "Online"
}

/** Server icon, or its acronym on a rounded square the way Discord's server list draws one. */
@Composable
fun GuildIcon(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.32f)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape).background(discord.guildIconBackground, shape),
        )
    } else {
        Initials(name, size, shape, modifier)
    }
}

@Composable
private fun Initials(name: String, size: Dp, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier = Modifier) {
    val c = discord
    val acronym = remember(name) {
        name.split(' ', '-', '_').filter { it.isNotBlank() }.take(3).joinToString("") { it.take(1) }.ifBlank { "?" }
    }
    Box(
        modifier.size(size).clip(shape).background(c.guildIconBackground, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            acronym,
            color = c.textDefault,
            fontSize = (size.value * 0.34f).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Discord's red mention pill. */
@Composable
fun MentionBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier
            .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(discord.badgeNotification)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** The small white unread pip. */
@Composable
fun UnreadDot(modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).clip(CircleShape).background(discord.interactiveTextActive))
}

/**
 * The QR code Discord's phone app scans. Black on white with a quiet zone, at an error
 * correction level the phone camera reads comfortably off a small, curved, glossy screen.
 */
@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier, description: String = "QR code to sign in with Discord") {
    val bitmap = remember(text) {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = description,
        filterQuality = FilterQuality.None,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(2.dp, Color.White, RoundedCornerShape(10.dp)),
    )
}

/** Muted single-line helper text, centred - the watch's standard caption. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = discord.textMuted) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        textAlign = TextAlign.Center,
    )
}
