package app.coilforphoniebox.ui.components

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/**
 * Cover art from the box's HTTP cover cache, with a stand-in for content that has none.
 *
 * The image library here is Coil, which shares its name with this app — it is imported
 * as `coil.compose` and called "the image loader" everywhere else, to keep review
 * comments unambiguous.
 *
 * Cover art dominates the player, so the brand colour deliberately recedes here: the
 * placeholder is a neutral surface rather than something green competing with artwork
 * (§10.7).
 *
 * **Three states, not two, and conflating the middle one is the trap.** A null [url] means
 * either "this has no artwork" *or* "nobody has finished asking yet" — resolving a cover is
 * two RPCs for a song and up to three for a folder, and `PlayerRepository.coverUrl`
 * deliberately starts null so a lookup cannot hold up the rest of the screen. Drawing
 * [FallbackCoverArt] on every null would therefore put abstract art on screen a second before
 * the real cover replaced it, on every track change and across a whole album grid at once.
 * [coverPending] is what separates the two: while it is set the neutral placeholder stands,
 * and the fallback appears only once the lookup has concluded with nothing.
 */
@Composable
fun CoverArt(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    placeholderIconSize: Dp = 40.dp,
    /** What stands in for missing artwork before [fallbackName] can pick a picture. */
    placeholderIcon: ImageVector = Icons.Rounded.Album,
    /**
     * Folder, album or track name this item is known by, which chooses its stand-in cover.
     * Null opts out and keeps the icon — see [FallbackCoverArt] for what counts as the name.
     */
    fallbackName: String? = null,
    /** Whether a cover lookup for this item is still outstanding. */
    coverPending: Boolean = false,
) {
    val shape = RoundedCornerShape(cornerRadius)

    val fallback = remember(fallbackName, coverPending) {
        if (coverPending) null else FallbackCoverArt.coverFor(fallbackName)
    }

    val stand: @Composable (Modifier) -> Unit = { standModifier ->
        if (fallback != null) {
            Image(
                painter = painterResource(fallback),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = standModifier,
            )
        } else {
            CoverPlaceholder(standModifier, placeholderIconSize, placeholderIcon)
        }
    }

    if (url == null) {
        stand(modifier.clip(shape))
        return
    }

    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(shape),
        // A cover that is on its way is not a cover that is missing: an image already in the
        // 96 MB disk cache arrives within the frame, and swapping a stand-in out again for
        // the one behind it would be the flicker this component exists to avoid.
        loading = { CoverPlaceholder(Modifier.fillMaxSize(), placeholderIconSize, placeholderIcon) },
        error = { stand(Modifier.fillMaxSize()) },
    )
}

@Composable
private fun CoverPlaceholder(modifier: Modifier, iconSize: Dp, icon: ImageVector) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
    }
}
