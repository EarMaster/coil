package app.coilforphoniebox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/**
 * Cover art from the box's HTTP cover cache.
 *
 * The image library here is Coil, which shares its name with this app — it is imported
 * as `coil.compose` and called "the image loader" everywhere else, to keep review
 * comments unambiguous.
 *
 * Cover art dominates the player, so the brand colour deliberately recedes here: the
 * placeholder is a neutral surface rather than something green competing with artwork
 * (§10.7).
 */
@Composable
fun CoverArt(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    placeholderIconSize: Dp = 40.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)

    if (url == null) {
        CoverPlaceholder(modifier.clip(shape), placeholderIconSize)
        return
    }

    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(shape),
        loading = { CoverPlaceholder(Modifier.fillMaxSize(), placeholderIconSize) },
        error = { CoverPlaceholder(Modifier.fillMaxSize(), placeholderIconSize) },
    )
}

@Composable
private fun CoverPlaceholder(modifier: Modifier, iconSize: Dp) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Album,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
    }
}
