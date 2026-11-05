package com.wristcord.wear.ui

import android.Manifest
import android.app.RemoteInput
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.wristcord.wear.WristCordApp
import com.wristcord.wear.discord.ImageOutbox
import com.wristcord.wear.discord.PhoneDropNetwork
import com.wristcord.wear.discord.PhoneDropServer
import com.wristcord.wear.ui.components.Caption
import com.wristcord.wear.ui.components.ListScreen
import com.wristcord.wear.ui.components.QrCode
import com.wristcord.wear.ui.components.row
import com.wristcord.wear.ui.theme.ThemedBackground
import com.wristcord.wear.ui.theme.discord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Where the picture comes from. A watch has no camera, so the phone is one of the choices. */
@Composable
fun ImageSourcesScreen(onGallery: () -> Unit, onDraw: () -> Unit, onPhone: () -> Unit) {
    ListScreen { spec ->
        row(spec, "title") { ListHeader(modifier = modifier) { Text("Send image") } }
        row(spec, "phone") {
            Button(
                onClick = onPhone, modifier = modifier, transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
                label = { Text("From phone", maxLines = 1) },
                secondaryLabel = { Text("Scan a code", maxLines = 1) },
            )
        }
        row(spec, "gallery") {
            Button(
                onClick = onGallery, modifier = modifier, transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                label = { Text("On this watch", maxLines = 1) },
            )
        }
        row(spec, "draw") {
            Button(
                onClick = onDraw, modifier = modifier, transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.Draw, contentDescription = null) },
                label = { Text("Draw", maxLines = 1) },
            )
        }
    }
}

private fun watchImages(resolver: ContentResolver): List<Uri> {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val args = Bundle().apply {
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_GALLERY)
    }
    return resolver.query(collection, arrayOf(MediaStore.Images.Media._ID), args, null)?.use { c ->
        buildList { while (c.moveToNext()) add(ContentUris.withAppendedId(collection, c.getLong(0))) }
    }.orEmpty()
}

/** Pictures already on the watch, newest first, in the two columns a round screen can show. */
@Composable
fun GalleryScreen(onPicked: (File) -> Unit) {
    val ctx = LocalContext.current
    val c = discord
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasImagePermission(ctx)) }
    var asked by remember { mutableStateOf(false) }
    var images by remember { mutableStateOf<List<Uri>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(granted) {
        if (granted) images = withContext(Dispatchers.IO) { runCatching { watchImages(ctx.contentResolver) }.getOrDefault(emptyList()) }
        else permission.launch(Manifest.permission.READ_MEDIA_IMAGES)
    }
    ListScreen { spec ->
        row(spec, "title") { ListHeader(modifier = modifier) { Text("On this watch") } }
        val list = images
        when {
            !granted && asked -> row(spec, "denied") {
                Caption("WristCord needs permission to see photos. Allow it here, or in the watch's Settings.", modifier)
            }
            !granted -> Unit
            list == null -> row(spec, "loading") { Caption("Looking for pictures…", modifier) }
            list.isEmpty() -> row(spec, "empty") {
                Caption("No pictures on this watch. Use From phone to send one from your phone.", modifier)
            }
            else -> {
                if (busy) row(spec, "busy") { Caption("Preparing…", modifier) }
                if (failed) row(spec, "failed") { Caption("Could not read that picture", modifier, color = c.textCritical) }
                for (pair in list.chunked(2)) row(spec, pair.first().toString()) {
                    Row(plain.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (uri in pair) AsyncImage(
                            model = uri,
                            contentDescription = "Picture",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.surfaceHighest)
                                .clickable(enabled = !busy) {
                                    busy = true
                                    failed = false
                                    scope.launch {
                                        val file = withContext(Dispatchers.Default) { ImageOutbox.fromUri(ctx, uri) }
                                        busy = false
                                        if (file != null) onPicked(file) else failed = true
                                    }
                                },
                        )
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
        if (!granted && asked) row(spec, "allow") {
            Button(
                onClick = { permission.launch(Manifest.permission.READ_MEDIA_IMAGES) },
                modifier = modifier, transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text("Allow", maxLines = 1) },
            )
        }
    }
}

private fun hasImagePermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

private class DoodleStroke(val color: Color) {
    val points = mutableStateListOf<Offset>()
}

private fun DoodleStroke.composePath() = Path().apply {
    val first = points.firstOrNull() ?: return@apply
    moveTo(first.x, first.y)
    if (points.size == 1) lineTo(first.x + 0.1f, first.y)
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
}

/**
 * Finger drawing. Drawing has to keep every touch from the swipe-to-dismiss around it, so a swipe
 * from the very left edge is recognised here and still means "back", as everywhere else.
 */
@Composable
fun DoodleScreen(onDone: (File) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = discord
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val strokes = remember { mutableStateListOf<DoodleStroke>() }
    var colorIndex by remember { mutableIntStateOf(0) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val edgePx = with(density) { DOODLE_BACK_EDGE.toPx() }
    val backPx = with(density) { DOODLE_BACK_DISTANCE.toPx() }
    val widthPx = with(density) { DOODLE_STROKE.toPx() }
    val color = DOODLE_COLORS[colorIndex]

    Box(Modifier.fillMaxSize().background(DOODLE_GROUND)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Drawing area" }
                .pointerInput(Unit) {
                    canvasSize = size
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        if (down.position.x < edgePx) {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                // On lift, not mid-swipe: with this screen gone, the rest of the
                                // swipe would reach the dismiss box and pop a second screen.
                                if (!change.pressed) {
                                    if (change.position.x - down.position.x > backPx) onBack()
                                    break
                                }
                            }
                            return@awaitEachGesture
                        }
                        val stroke = DoodleStroke(DOODLE_COLORS[colorIndex]).also { it.points.add(down.position) }
                        strokes.add(stroke)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.positionChange() != Offset.Zero) stroke.points.add(change.position)
                            change.consume()
                            if (!change.pressed) break
                        }
                    }
                },
        ) {
            val style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (s in strokes) drawPath(s.composePath(), s.color, style = style)
        }
        if (strokes.isEmpty()) Text(
            if (failed) "Could not save that" else "Draw with your finger",
            color = if (failed) c.textCritical else c.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DoodleButton("Undo", enabled = strokes.isNotEmpty(), onClick = { strokes.removeAt(strokes.lastIndex) }) {
                Icon(Icons.Rounded.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            DoodleButton("Change color", onClick = { colorIndex = (colorIndex + 1) % DOODLE_COLORS.size }) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(color).border(1.dp, Color.White, CircleShape))
            }
            DoodleButton("Done", enabled = strokes.isNotEmpty() && !saving, onClick = {
                saving = true
                failed = false
                val snapshot = strokes.map { s -> s.color to s.points.toList() }
                val size = canvasSize
                scope.launch {
                    val file = withContext(Dispatchers.Default) { renderDoodle(ctx, snapshot, size, widthPx) }
                    saving = false
                    if (file != null) onDone(file) else failed = true
                }
            }) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun DoodleButton(label: String, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (enabled) Color(0xFF4E5058) else Color(0xFF2B2D31))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun renderDoodle(ctx: Context, strokes: List<Pair<Color, List<Offset>>>, size: IntSize, widthPx: Float): File? {
    if (size.width <= 0 || size.height <= 0) return null
    val scale = DOODLE_OUTPUT_PX.toFloat() / max(size.width, size.height)
    val bitmap = Bitmap.createBitmap(
        (size.width * scale).toInt().coerceAtLeast(1), (size.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565,
    )
    return try {
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(DOODLE_GROUND.toArgb())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = widthPx * scale
        }
        for ((color, points) in strokes) {
            val first = points.firstOrNull() ?: continue
            paint.color = color.toArgb()
            val path = android.graphics.Path().apply {
                moveTo(first.x * scale, first.y * scale)
                if (points.size == 1) lineTo(first.x * scale + 0.1f, first.y * scale)
                for (i in 1 until points.size) lineTo(points[i].x * scale, points[i].y * scale)
            }
            canvas.drawPath(path, paint)
        }
        ImageOutbox.fromBitmap(ctx, bitmap)
    } finally {
        bitmap.recycle()
    }
}

/** A QR code that opens a one-shot upload page on the phone; see [PhoneDropServer]. */
@Composable
fun PhoneDropScreen(onReceived: (File) -> Unit) {
    val ctx = LocalContext.current
    val c = discord
    val view = LocalView.current
    var url by remember { mutableStateOf<String?>(null) }
    var noWifi by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    // The screen must outlast the walk over to the phone's camera.
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(attempt) {
        url = null
        noWifi = false
        val network = PhoneDropNetwork(ctx)
        var server: PhoneDropServer? = null
        try {
            val address = network.acquire()
            if (address == null) {
                noWifi = true
                return@LaunchedEffect
            }
            val appContext = ctx.applicationContext
            server = withContext(Dispatchers.IO) {
                val work = appContext.cacheDir.resolve("phone-drop").apply { mkdirs() }
                PhoneDropServer(address, work) { ImageOutbox.fromFile(appContext, it) }
            }
            url = server.url
            onReceived(server.awaitImage())
        } finally {
            server?.close()
            network.release()
        }
    }

    ThemedBackground {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            val u = url
            when {
                u != null -> {
                    QrCode(u, size = 124.dp, description = "QR code to open the upload page on your phone")
                    Caption("Scan with your phone. It needs the same Wi-Fi.")
                }
                noWifi -> {
                    Caption("The watch can't get on Wi-Fi. Connect it to the same network as your phone.", color = c.textCritical)
                    Button(
                        onClick = { attempt++ },
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text("Try again", maxLines = 1) },
                    )
                }
                else -> Caption("Connecting to Wi-Fi…")
            }
        }
    }
}

/** The last stop before Discord: nothing leaves the watch until Send is pressed here. */
@Composable
fun ImagePreviewScreen(channelId: Long, file: File, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val app = WristCordApp.of(ctx)
    val c = discord
    var caption by remember { mutableStateOf("") }
    val input = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_REPLY) }?.let { caption = it.toString() }
    }
    val listState = rememberTransformingLazyColumnState()
    ListScreen(
        state = listState,
        edgeButton = {
            EdgeButton(
                onClick = {
                    val text = caption
                    // On the app's scope: the upload continues after this screen is gone.
                    app.scope.launch { app.discord.sendImage(channelId, file, text) }
                    onSent()
                },
                buttonSize = EdgeButtonSize.Medium,
            ) {
                Icon(Icons.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Send", maxLines = 1)
            }
        },
    ) { spec ->
        row(spec, "title") { ListHeader(modifier = modifier) { Text("Send this?") } }
        row(spec, "image") {
            AsyncImage(
                model = file,
                contentDescription = "Picture to send",
                contentScale = ContentScale.Fit,
                modifier = plain.padding(horizontal = 18.dp).aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(c.surfaceHighest),
            )
        }
        row(spec, "caption") {
            Button(
                onClick = { runCatching { input.launch(replyIntent("Add a caption")) } },
                modifier = modifier, transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                label = { Text(if (caption.isBlank()) "Add caption" else caption, maxLines = 2) },
            )
        }
    }
}

private const val MAX_GALLERY = 60
private const val DOODLE_OUTPUT_PX = 720
private val DOODLE_STROKE = 4.dp
private val DOODLE_BACK_EDGE = 18.dp
private val DOODLE_BACK_DISTANCE = 48.dp
private val DOODLE_GROUND = Color(0xFF1E1F22)
private val DOODLE_COLORS = listOf(Color.White, Color(0xFF5865F2), Color(0xFF57F287), Color(0xFFFEE75C), Color(0xFFED4245))
