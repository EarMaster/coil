package app.coilforphoniebox.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.Box as PhonieBox
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.FavoriteType
import app.coilforphoniebox.domain.model.FavoritesLayout
import app.coilforphoniebox.ui.components.CoverArt
import app.coilforphoniebox.ui.components.EmptyState
import app.coilforphoniebox.ui.components.shareLink

/**
 * The favourites tab, in one of two layouts (§7.2).
 *
 * [layout] comes from settings via the shell rather than from this screen's own view model,
 * because the control that changes it lives in the top bar — one preference, one owner.
 */
@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel,
    layout: FavoritesLayout,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.favorites.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.StarBorder,
            title = stringResource(R.string.favourites_empty_title),
            body = stringResource(R.string.favourites_empty_body),
            modifier = modifier,
        )
        return
    }

    // A single column of a grid is a list, so both layouts share one lazy container and one
    // item block — which keeps the two from drifting apart in what they can do.
    val compact = layout == FavoritesLayout.LIST

    LazyVerticalGrid(
        columns = if (compact) GridCells.Fixed(1) else GridCells.Adaptive(minSize = 148.dp),
        contentPadding = if (compact) PaddingValues(vertical = 8.dp) else PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 12.dp),
        modifier = modifier,
    ) {
        items(state.favorites, key = { it.id }) { favorite ->
            // Cover art is fetched for the entries that are actually on screen, never for
            // the whole table at once — see FavoritesViewModel.ensureCover.
            LaunchedEffect(favorite.id, favorite.coverFile) { viewModel.ensureCover(favorite) }

            FavoriteEntry(
                favorite = favorite,
                box = state.activeBox,
                compact = compact,
                link = viewModel.linkFor(favorite),
                onPlay = { viewModel.play(favorite) },
                onRemove = { viewModel.remove(favorite) },
                onPin = { coverUrl -> viewModel.requestPin(favorite, coverUrl) },
                onMove = { up -> viewModel.move(favorite, up) },
                onLinkCopied = viewModel::onLinkCopied,
            )
        }
    }
}

/**
 * One favourite, as a cover tile or as a row.
 *
 * Everything that is not layout lives here — the cover URL, the menu and what it offers —
 * so the two shapes differ in appearance only and neither can quietly lose an action.
 */
@Composable
private fun FavoriteEntry(
    favorite: Favorite,
    box: PhonieBox?,
    compact: Boolean,
    /** `coil://play` link for this favourite; null for a row that cannot be played. */
    link: String?,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onPin: (String?) -> Unit,
    onMove: (Boolean) -> Unit,
    onLinkCopied: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val coverUrl = remember(favorite.coverFile, box?.host) {
        favorite.coverFile?.let { file -> box?.coverUrl(file) }
    }

    val menuButton: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.action_more),
                )
            }

            FavoriteMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                label = favorite.label,
                link = link,
                onPin = { onPin(coverUrl) },
                onMove = onMove,
                onRemove = onRemove,
                onLinkCopied = onLinkCopied,
            )
        }
    }

    if (compact) {
        FavoriteRow(favorite, coverUrl, onPlay, menuButton)
    } else {
        FavoriteCell(favorite, coverUrl, onPlay, menuButton)
    }
}

/** The big-cover tile: what favourites are for, and what a child aims at without reading. */
@Composable
private fun FavoriteCell(
    favorite: Favorite,
    coverUrl: String?,
    onPlay: () -> Unit,
    menuButton: @Composable () -> Unit,
) {
    Column {
        Box {
            CoverArt(
                url = coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clickable(onClick = onPlay),
                cornerRadius = 14.dp,
                placeholderIcon = favorite.placeholderIcon,
            )

            Box(Modifier.align(Alignment.TopEnd)) { menuButton() }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = favorite.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(4.dp))
    }
}

/**
 * The compact row, for a collection that has outgrown a screenful of tiles: the same cover
 * at thumbnail size, and the label on one line so four times as many fit.
 */
@Composable
private fun FavoriteRow(
    favorite: Favorite,
    coverUrl: String?,
    onPlay: () -> Unit,
    menuButton: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        CoverArt(
            url = coverUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            cornerRadius = 8.dp,
            placeholderIconSize = 24.dp,
            placeholderIcon = favorite.placeholderIcon,
        )

        Text(
            text = favorite.label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        )

        menuButton()
    }
}

@Composable
private fun FavoriteMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    label: String,
    link: String?,
    onPin: () -> Unit,
    onMove: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onLinkCopied: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_add_to_home_screen)) },
            onClick = {
                onDismiss()
                onPin()
            },
        )
        // The link is what a home screen shortcut points at. Handing it out lets an
        // automation app, an NFC tag or a link in a note start this favourite on its
        // own box — and it is the only way to see it, since the box id in it is a
        // UUID shown nowhere else.
        if (link != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy_link)) },
                onClick = {
                    onDismiss()
                    clipboard.setText(AnnotatedString(link))
                    onLinkCopied()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share_link)) },
                onClick = {
                    onDismiss()
                    context.shareLink(link, label)
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_move_up)) },
            onClick = {
                onDismiss()
                onMove(true)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_move_down)) },
            onClick = {
                onDismiss()
                onMove(false)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_remove)) },
            onClick = {
                onDismiss()
                onRemove()
            },
        )
    }
}

/**
 * What stands in for artwork the box has none of. A folder and a single track usually have
 * no cover of their own, so the placeholder is what tells the two apart at a glance.
 */
private val Favorite.placeholderIcon: ImageVector
    get() = when (type) {
        FavoriteType.FOLDER -> Icons.Rounded.Folder
        FavoriteType.ALBUM -> Icons.Rounded.Album
        FavoriteType.TRACK -> Icons.Rounded.MusicNote
    }
