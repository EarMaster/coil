package app.coilforphoniebox.data.db

import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.FavoriteType
import app.coilforphoniebox.domain.model.LibraryAlbum
import app.coilforphoniebox.domain.model.LibraryFolder
import app.coilforphoniebox.domain.model.LibraryTrack

internal fun BoxEntity.toDomain() = Box(
    id = id,
    displayName = displayName,
    host = host,
    rpcPort = rpcPort,
    pubPort = pubPort,
    addedAt = addedAt,
    autoSessionEnabled = autoSessionEnabled,
    networkSsid = networkSsid,
    lastSeenAt = lastSeenAt,
    sortIndex = sortIndex,
)

internal fun Box.toEntity() = BoxEntity(
    id = id,
    displayName = displayName,
    host = host,
    rpcPort = rpcPort,
    pubPort = pubPort,
    addedAt = addedAt,
    autoSessionEnabled = autoSessionEnabled,
    networkSsid = networkSsid,
    lastSeenAt = lastSeenAt,
    sortIndex = sortIndex,
)

internal fun LibraryFolderEntity.toDomain() = LibraryFolder(
    boxId = boxId,
    path = path,
    parentPath = parentPath,
    displayName = displayName,
    hasChildren = hasChildren,
    cachedAt = cachedAt,
)

internal fun LibraryFolder.toEntity(contentCachedAt: Long? = null) = LibraryFolderEntity(
    boxId = boxId,
    path = path,
    parentPath = parentPath,
    displayName = displayName,
    hasChildren = hasChildren,
    // Folded once, on the way in, so a query never has to scan unfolded text.
    searchText = SearchText.haystack(displayName),
    cachedAt = cachedAt,
    contentCachedAt = contentCachedAt,
)

internal fun LibraryTrackEntity.toDomain() = LibraryTrack(
    boxId = boxId,
    url = url,
    parentPath = parentPath,
    title = title,
    artist = artist,
    album = album,
    trackNo = trackNo,
    durationSeconds = durationSeconds,
)

internal fun LibraryTrack.toEntity() = LibraryTrackEntity(
    boxId = boxId,
    url = url,
    parentPath = parentPath,
    title = title,
    artist = artist,
    album = album,
    trackNo = trackNo,
    durationSeconds = durationSeconds,
    // The file name is in the haystack because an untagged track is shown by it, and what
    // is on screen is what a user expects to be able to search for.
    searchText = SearchText.haystack(title, artist, album, url.substringAfterLast('/')),
)

internal fun LibraryAlbumEntity.toDomain() = LibraryAlbum(
    boxId = boxId,
    albumArtist = albumArtist,
    album = album,
    coverFile = coverFile,
    cachedAt = cachedAt,
)

internal fun LibraryAlbum.toEntity() = LibraryAlbumEntity(
    boxId = boxId,
    albumArtist = albumArtist,
    album = album,
    coverFile = coverFile,
    searchText = SearchText.haystack(album, albumArtist),
    cachedAt = cachedAt,
)

internal fun FavoriteEntity.toDomain() = Favorite(
    id = id,
    boxId = boxId,
    label = label,
    // An unrecognised value can only come from an edited or imported file; treating it
    // as a folder keeps the row visible and removable instead of crashing the list.
    type = runCatching { FavoriteType.valueOf(type) }.getOrDefault(FavoriteType.FOLDER),
    folder = folder,
    albumArtist = albumArtist,
    album = album,
    trackUrl = trackUrl,
    coverFile = coverFile,
    sortIndex = sortIndex,
    launchCount = launchCount,
    shortcutPinned = shortcutPinned,
)

internal fun Favorite.toEntity() = FavoriteEntity(
    id = id,
    boxId = boxId,
    label = label,
    type = type.name,
    folder = folder,
    albumArtist = albumArtist,
    album = album,
    trackUrl = trackUrl,
    coverFile = coverFile,
    sortIndex = sortIndex,
    launchCount = launchCount,
    shortcutPinned = shortcutPinned,
)
