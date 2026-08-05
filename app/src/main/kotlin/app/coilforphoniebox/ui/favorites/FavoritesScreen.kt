package app.coilforphoniebox.ui.favorites

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.coilforphoniebox.ui.components.CoverArt
import app.coilforphoniebox.ui.components.EmptyState
import app.coilforphoniebox.ui.components.shareLink

@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel,
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

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 148.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        items(state.favorites, key = { it.id }) { favorite ->
            FavoriteCell(
                favorite = favorite,
                box = state.activeBox,
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

@Composable
private fun FavoriteCell(
    favorite: Favorite,
    box: PhonieBox?,
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
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

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
                // Folders and single tracks have no artwork, so the placeholder is what
                // tells a saved folder apart from a saved track at a glance.
                placeholderIcon = when (favorite.type) {
                    FavoriteType.FOLDER -> Icons.Rounded.Folder
                    FavoriteType.ALBUM -> Icons.Rounded.Album
                    FavoriteType.TRACK -> Icons.Rounded.MusicNote
                },
            )

            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.action_more),
                )
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_add_to_home_screen)) },
                    onClick = {
                        menuOpen = false
                        onPin(coverUrl)
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
                            menuOpen = false
                            clipboard.setText(AnnotatedString(link))
                            onLinkCopied()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_share_link)) },
                        onClick = {
                            menuOpen = false
                            context.shareLink(link, favorite.label)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move_up)) },
                    onClick = {
                        menuOpen = false
                        onMove(true)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move_down)) },
                    onClick = {
                        menuOpen = false
                        onMove(false)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove)) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
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
