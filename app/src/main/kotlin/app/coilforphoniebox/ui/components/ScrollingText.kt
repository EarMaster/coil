package app.coilforphoniebox.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A single line of text that scrolls when it does not fit, for titles whose end matters as
 * much as their start — a chapter number, a movement, a part two.
 *
 * The end edge fades out while the text overflows, because a plain marquee clips mid-letter
 * and a clipped letter at rest reads as a layout bug rather than as "there is more". Text that
 * fits is left alone: fading it would soften its last glyph for nothing.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.scrollingText(): Modifier = composed {
    // basicMarquee measures its content without a width limit, so the layout step below sees
    // the text's full width, and the draw step compares it with the width actually available.
    var contentWidth by remember { mutableIntStateOf(0) }

    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (contentWidth <= size.width) return@drawWithContent

            val fade = FADE_WIDTH.toPx().coerceAtMost(size.width / 2)
            val ltr = layoutDirection == LayoutDirection.Ltr
            val edge = if (ltr) size.width else 0f
            val inner = if (ltr) size.width - fade else fade
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = inner,
                    endX = edge,
                ),
                topLeft = Offset(if (ltr) inner else 0f, 0f),
                size = size.copy(width = fade),
                blendMode = BlendMode.DstIn,
            )
        }
        .basicMarquee(iterations = Int.MAX_VALUE)
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            contentWidth = placeable.width
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
}

private val FADE_WIDTH = 24.dp
