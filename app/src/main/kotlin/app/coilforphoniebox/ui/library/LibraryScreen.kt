package app.coilforphoniebox.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.Box as PhonieBox
import app.coilforphoniebox.domain.model.LibraryAlbum
import app.coilforphoniebox.domain.model.LibraryFolder
import app.coilforphoniebox.domain.model.LibraryTrack
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.ui.components.CoverArt
import app.coilforphoniebox.ui.components.EmptyState
import app.coilforphoniebox.ui.components.rememberFreshnessLabel

private const val TAB_FOLDERS = 0
private const val TAB_ALBUMS = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_FOLDERS) }

    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == TAB_FOLDERS,
                onClick = { selectedTab = TAB_FOLDERS },
                text = { Text(stringResource(R.string.library_tab_folders)) },
            )
            Tab(
                selected = selectedTab == TAB_ALBUMS,
                onClick = { selectedTab = TAB_ALBUMS },
                text = { Text(stringResource(R.string.library_tab_albums)) },
            )
        }

        when (selectedTab) {
            TAB_FOLDERS -> FolderTab(viewModel)
            else -> AlbumTab(viewModel)
        }
    }
}

@Composable
private fun FolderTab(viewModel: LibraryViewModel) {
    val state by viewModel.folderState.collectAsStateWithLifecycle()
    val favouriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val freshness = rememberFreshnessLabel(state.content.cachedAt)

    Column(Modifier.fillMaxSize()) {
        Breadcrumb(
            segments = state.segments,
            canGoUp = state.canGoUp,
            onUp = viewModel::goUp,
            onSegment = viewModel::openSegment,
            onRoot = { viewModel.openFolder("") },
        )

        if (state.content.isEmpty && !state.refreshing) {
            EmptyState(
                icon = Icons.Rounded.FolderOff,
                title = stringResource(R.string.library_empty_folder),
                body = freshness ?: stringResource(R.string.library_never_updated),
                actionLabel = stringResource(R.string.action_refresh),
                onAction = viewModel::refreshCurrentFolder,
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(state.content.folders, key = { it.path }) { folder ->
                FolderRow(
                    folder = folder,
                    favourite = "folder:${folder.path}" in favouriteKeys,
                    onOpen = { viewModel.openFolder(folder.path) },
                    onPlay = { viewModel.play(PlayTarget.Folder(folder.path)) },
                    onToggleFavourite = {
                        viewModel.toggleFavorite(folder.displayName, PlayTarget.Folder(folder.path))
                    },
                )
            }

            items(state.content.tracks, key = { it.url }) { track ->
                TrackRow(
                    track = track,
                    onPlay = { viewModel.play(PlayTarget.Track(track.url)) },
                )
            }

            if (freshness != null) {
                item {
                    Text(
                        text = freshness,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(
    segments: List<String>,
    canGoUp: Boolean,
    onUp: () -> Unit,
    onSegment: (Int) -> Unit,
    onRoot: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUp, enabled = canGoUp) {
            Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = stringResource(R.string.action_up),
            )
        }

        // Scrolls rather than truncates: a deep path would otherwise hide the current
        // folder's name, which is the one part that matters. Long German and Dutch names
        // make this the first place a fixed width would have bitten (§12.3).
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.library_root),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onRoot),
            )
            segments.forEachIndexed { index, segment ->
                Text(
                    text = SEGMENT_SEPARATOR,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = segment,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (index == segments.lastIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    modifier = Modifier.clickable { onSegment(index) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: LibraryFolder,
    favourite: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onToggleFavourite),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(Icons.Rounded.Folder)
            Spacer(Modifier.size(12.dp))
            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (favourite) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = stringResource(R.string.action_favourite_remove),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
            }
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = stringResource(R.string.action_play_folder),
                )
            }
        }
    }
}

@Composable
private fun TrackRow(track: LibraryTrack, onPlay: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(Icons.Rounded.MusicNote)
            Spacer(Modifier.size(12.dp))
            Text(
                text = track.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RowIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun AlbumTab(viewModel: LibraryViewModel) {
    val state by viewModel.albumState.collectAsStateWithLifecycle()
    val favouriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val activeBox by viewModel.activeBox.collectAsStateWithLifecycle()
    val freshness = rememberFreshnessLabel(state.cachedAt)

    // Opening the tab is what asks the box for its albums the first time.
    LaunchedEffect(activeBox?.id) { viewModel.onAlbumsShown() }

    if (state.albums.isEmpty() && !state.refreshing) {
        EmptyState(
            icon = Icons.Rounded.Album,
            title = stringResource(R.string.library_empty_albums),
            body = stringResource(R.string.library_never_updated),
            actionLabel = stringResource(R.string.action_refresh),
            onAction = viewModel::refreshAlbums,
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 148.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.albums, key = { "${it.albumArtist}|${it.album}" }) { album ->
            AlbumCell(
                album = album,
                box = activeBox,
                favourite = "album:${album.albumArtist}/${album.album}" in favouriteKeys,
                onRequestCover = { viewModel.requestAlbumCover(album) },
                onPlay = { viewModel.play(PlayTarget.Album(album.albumArtist, album.album)) },
                onToggleFavourite = {
                    viewModel.toggleFavorite(
                        label = album.album,
                        target = PlayTarget.Album(album.albumArtist, album.album),
                        coverFile = album.coverFile,
                    )
                },
            )
        }

        if (freshness != null) {
            item {
                Text(
                    text = freshness,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCell(
    album: LibraryAlbum,
    box: PhonieBox?,
    favourite: Boolean,
    onRequestCover: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    // Covers are fetched as cells appear rather than all at once during a refresh: one
    // RPC per album on a socket the box shares with its card reader (§6).
    LaunchedEffect(album.albumArtist, album.album, album.coverFile) {
        if (album.coverFile == null) onRequestCover()
    }

    val coverUrl = remember(album.coverFile, box?.host) {
        album.coverFile?.let { file -> box?.coverUrl(file) }
    }

    Column(
        Modifier.combinedClickable(onClick = onPlay, onLongClick = onToggleFavourite),
    ) {
        Box {
            CoverArt(
                url = coverUrl,
                contentDescription = stringResource(R.string.player_cover_art),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                cornerRadius = 14.dp,
            )
            if (favourite) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = stringResource(R.string.action_favourite_remove),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = album.album.ifBlank { stringResource(R.string.library_unknown_album) },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = album.albumArtist.ifBlank { stringResource(R.string.library_unknown_artist) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** Path separator between breadcrumb segments; not translated. */
private const val SEGMENT_SEPARATOR = " / "
