package com.wristcord.wear.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.wristcord.wear.ui.theme.ThemedBackground

/** Per-item helpers so every row morphs at the screen edge the same way. */
class ListItemScope(val item: TransformingLazyColumnItemScope, val spec: TransformationSpec) {
    val modifier: Modifier get() = Modifier.fillMaxWidth().transformedHeight(item, spec)

    /**
     * For free-form content (message bodies) that does not scale with the edge morph: shrinking
     * the slot without shrinking what is drawn in it would let rows overlap at the screen edge.
     */
    val plain: Modifier get() = Modifier.fillMaxWidth()
    val transformation: SurfaceTransformation get() = with(item) { SurfaceTransformation(spec) }
}

fun TransformingLazyColumnScope.row(
    spec: TransformationSpec,
    key: Any? = null,
    content: @Composable ListItemScope.() -> Unit,
) = item(key = key) { ListItemScope(this, spec).content() }

/** The standard WristCord screen: Discord ground, a curved scrolling list, optional edge button. */
@Composable
fun ListScreen(
    state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val spec = rememberTransformationSpec()
    ThemedBackground {
        if (edgeButton != null) {
            ScreenScaffold(scrollState = state, edgeButton = edgeButton) { padding ->
                TransformingLazyColumn(state = state, contentPadding = padding) { content(spec) }
            }
        } else {
            ScreenScaffold(scrollState = state) { padding ->
                TransformingLazyColumn(state = state, contentPadding = padding) { content(spec) }
            }
        }
    }
}

val RowIconSize = 26.dp
