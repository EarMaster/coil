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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.mutableStateOf
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
import app.coilforphoniebox.ui.components.ActionMenuItem
import app.coilforphoniebox.ui.components.CoverArt
import app.coilforphoniebox.ui.components.DetailRow
import app.coilforphoniebox.ui.components.DetailsSheet
import app.coilforphoniebox.ui.components.EmptyState
import app.coilforphoniebox.ui.components.FavoriteMenuItem
import app.coilforphoniebox.ui.components.formatDuration
import app.coilforphoniebox.ui.components.formatNumber
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
    var details by remember { mutableStateOf<DetailsTarget?>(null) }

    // Starting playback closes the sheet; favouriting from it does not, because the star
    // filling in is the confirmation — a snackbar would sit behind the sheet unseen.
    when (val target = details) {
        is DetailsTarget.Folder -> FolderDetailsSheet(
            folder = target.folder,
            favourite = "folder:${target.folder.path}" in favouriteKeys,
            onPlay = {
                details = null
                viewModel.play(PlayTarget.Folder(target.folder.path))
            },
            onToggleFavourite = {
                viewModel.toggleFavorite(
                    target.folder.displayName,
                    PlayTarget.Folder(target.folder.path),
                )
            },
            onDismiss = { details = null },
        )

        is DetailsTarget.Track -> TrackDetailsSheet(
            track = target.track,
            favourite = "track:${target.track.url}" in favouriteKeys,
            onPlay = {
                details = null
                viewModel.play(PlayTarget.Track(target.track.url))
            },
            onToggleFavourite = {
                viewModel.toggleFavorite(
                    target.track.displayTitle,
                    PlayTarget.Track(target.track.url),
                )
            },
            onDismiss = { details = null },
        )

        null -> Unit
    }

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
                    onDetails = { details = DetailsTarget.Folder(folder) },
                )
            }

            items(state.content.tracks, key = { it.url }) { track ->
                TrackRow(
                    track = track,
                    favourite = "track:${track.url}" in favouriteKeys,
                    onPlay = { viewModel.play(PlayTarget.Track(track.url)) },
                    onToggleFavourite = {
                        viewModel.toggleFavorite(track.displayTitle, PlayTarget.Track(track.url))
                    },
                    onDetails = { details = DetailsTarget.Track(track) },
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

/**
 * A folder. Tapping opens it, the note button plays it, and both the ⋮ button and a long
 * press open the same menu — the long press alone was the only way to favourite a folder,
 * which is not something a user finds by accident.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: LibraryFolder,
    favourite: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDetails: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
                onLongClickLabel = stringResource(R.string.action_more),
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 11.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
            ItemMenu(
                expanded = menuOpen,
                onExpandedChange = { menuOpen = it },
                playLabel = stringResource(R.string.action_play_folder),
                favouriteLabel = stringResource(
                    if (favourite) R.string.action_favourite_remove_folder
                    else R.string.action_favourite_add_folder,
                ),
                favourite = favourite,
                onPlay = onPlay,
                onToggleFavourite = onToggleFavourite,
                onDetails = onDetails,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: LibraryTrack,
    favourite: Boolean,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDetails: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onPlay,
                onLongClick = { menuOpen = true },
                onLongClickLabel = stringResource(R.string.action_more),
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 11.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
            if (favourite) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = stringResource(R.string.action_favourite_remove),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
            }
            ItemMenu(
                expanded = menuOpen,
                onExpandedChange = { menuOpen = it },
                playLabel = stringResource(R.string.action_play),
                favouriteLabel = stringResource(
                    if (favourite) R.string.action_favourite_remove_track
                    else R.string.action_favourite_add_track,
                ),
                favourite = favourite,
                onPlay = onPlay,
                onToggleFavourite = onToggleFavourite,
                onDetails = onDetails,
            )
        }
    }
}

/**
 * The ⋮ button and the menu behind it, shared by folders, tracks and albums. Its three
 * entries are the same everywhere; only the wording of the first two changes with the kind
 * of item, so that "Play" and "Save as favourite" never leave open *what* is meant.
 */
@Composable
private fun ItemMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    playLabel: String,
    favouriteLabel: String,
    favourite: Boolean,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.action_more),
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            ActionMenuItem(
                text = playLabel,
                icon = Icons.Rounded.PlayArrow,
                onClick = {
                    onExpandedChange(false)
                    onPlay()
                },
            )
            FavoriteMenuItem(
                text = favouriteLabel,
                detail = null,
                saved = favourite,
                onClick = {
                    onExpandedChange(false)
                    onToggleFavourite()
                },
            )
            ActionMenuItem(
                text = stringResource(R.string.action_details),
                icon = Icons.Rounded.Info,
                onClick = {
                    onExpandedChange(false)
                    onDetails()
                },
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
    var details by remember { mutableStateOf<LibraryAlbum?>(null) }

    // Opening the tab is what asks the box for its albums the first time.
    LaunchedEffect(activeBox?.id) { viewModel.onAlbumsShown() }

    details?.let { album ->
        AlbumDetailsSheet(
            album = album,
            box = activeBox,
            favourite = "album:${album.albumArtist}/${album.album}" in favouriteKeys,
            onPlay = {
                details = null
                viewModel.play(PlayTarget.Album(album.albumArtist, album.album))
            },
            onToggleFavourite = {
                viewModel.toggleFavorite(
                    label = album.album,
                    target = PlayTarget.Album(album.albumArtist, album.album),
                    coverFile = album.coverFile,
                )
            },
            onDismiss = { details = null },
        )
    }

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
                onDetails = { details = album },
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
    onDetails: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Covers are fetched as cells appear rather than all at once during a refresh: one
    // RPC per album on a socket the box shares with its card reader (§6).
    LaunchedEffect(album.albumArtist, album.album, album.coverFile) {
        if (album.coverFile == null) onRequestCover()
    }

    val coverUrl = remember(album.coverFile, box?.host) {
        album.coverFile?.let { file -> box?.coverUrl(file) }
    }

    Column(
        Modifier.combinedClickable(
            onClick = onPlay,
            onLongClick = { menuOpen = true },
            onLongClickLabel = stringResource(R.string.action_more),
        ),
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
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(20.dp),
                )
            }
            // Over the artwork rather than under the title: the grid has no spare row,
            // and the star badge moves to the opposite corner to make room.
            ItemMenu(
                expanded = menuOpen,
                onExpandedChange = { menuOpen = it },
                playLabel = stringResource(R.string.action_play),
                favouriteLabel = stringResource(
                    if (favourite) R.string.action_favourite_remove_album
                    else R.string.action_favourite_add_album,
                ),
                favourite = favourite,
                onPlay = onPlay,
                onToggleFavourite = onToggleFavourite,
                onDetails = onDetails,
                modifier = Modifier.align(Alignment.TopEnd),
            )
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

/** What the folder tab is currently showing details for. */
private sealed interface DetailsTarget {
    data class Folder(val folder: LibraryFolder) : DetailsTarget

    data class Track(val track: LibraryTrack) : DetailsTarget
}

/**
 * The three details sheets differ only in which fields exist for the kind of item, all of
 * them read from the cache. Nothing here asks the box anything (§6).
 */
@Composable
private fun FolderDetailsSheet(
    folder: LibraryFolder,
    favourite: Boolean,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDismiss: () -> Unit,
) {
    DetailsSheet(
        title = folder.displayName,
        subtitle = null,
        coverUrl = null,
        placeholderIcon = Icons.Rounded.Folder,
        rows = listOf(
            DetailRow(
                label = stringResource(R.string.details_path),
                // The root's children have a bare name as their path, which is still the
                // path the box takes.
                value = folder.path,
            ),
        ),
        footnote = rememberFreshnessLabel(folder.cachedAt),
        favouriteLabel = stringResource(
            if (favourite) R.string.action_favourite_remove_folder
            else R.string.action_favourite_add_folder,
        ),
        favourite = favourite,
        onToggleFavourite = onToggleFavourite,
        onPlay = onPlay,
        onDismiss = onDismiss,
    )
}

@Composable
private fun TrackDetailsSheet(
    track: LibraryTrack,
    favourite: Boolean,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = buildList {
        track.artist?.takeIf { it.isNotBlank() }?.let {
            add(DetailRow(stringResource(R.string.details_artist), it))
        }
        track.album?.takeIf { it.isNotBlank() }?.let {
            add(DetailRow(stringResource(R.string.details_album), it))
        }
        track.trackNo?.let {
            add(DetailRow(stringResource(R.string.details_track_number), formatNumber(it)))
        }
        track.durationSeconds?.takeIf { it > 0 }?.let {
            add(DetailRow(stringResource(R.string.details_duration), formatDuration(it)))
        }
        add(DetailRow(stringResource(R.string.details_file), track.url))
    }

    DetailsSheet(
        title = track.displayTitle,
        subtitle = track.artist?.takeIf { it.isNotBlank() },
        coverUrl = null,
        placeholderIcon = Icons.Rounded.MusicNote,
        rows = rows,
        footnote = null,
        favouriteLabel = stringResource(
            if (favourite) R.string.action_favourite_remove_track
            else R.string.action_favourite_add_track,
        ),
        favourite = favourite,
        onToggleFavourite = onToggleFavourite,
        onPlay = onPlay,
        onDismiss = onDismiss,
    )
}

@Composable
private fun AlbumDetailsSheet(
    album: LibraryAlbum,
    box: PhonieBox?,
    favourite: Boolean,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDismiss: () -> Unit,
) {
    val coverUrl = remember(album.coverFile, box?.host) {
        album.coverFile?.let { file -> box?.coverUrl(file) }
    }

    DetailsSheet(
        title = album.album.ifBlank { stringResource(R.string.library_unknown_album) },
        subtitle = album.albumArtist.ifBlank { stringResource(R.string.library_unknown_artist) },
        coverUrl = coverUrl,
        placeholderIcon = Icons.Rounded.Album,
        rows = listOf(
            DetailRow(
                label = stringResource(R.string.details_album_artist),
                value = album.albumArtist.ifBlank { stringResource(R.string.library_unknown_artist) },
            ),
        ),
        footnote = rememberFreshnessLabel(album.cachedAt),
        favouriteLabel = stringResource(
            if (favourite) R.string.action_favourite_remove_album
            else R.string.action_favourite_add_album,
        ),
        favourite = favourite,
        onToggleFavourite = onToggleFavourite,
        onPlay = onPlay,
        onDismiss = onDismiss,
    )
}

/** Path separator between breadcrumb segments; not translated. */
private const val SEGMENT_SEPARATOR = " / "
