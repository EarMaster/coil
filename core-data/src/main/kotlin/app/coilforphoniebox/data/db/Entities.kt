package app.coilforphoniebox.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Schema per implementation plan §6.3.
 *
 * Every table carries `boxId` from the very first commit, even though the launch UI is
 * single-box oriented. Adding the column later would mean a Room migration across
 * every table plus a rewrite of the shortcut deep link format — cheap now, expensive
 * in six months (§7.6).
 *
 * `ON DELETE CASCADE` throughout means removing a box takes its library cache and its
 * favourites with it in one step.
 */
@Entity(tableName = "boxes")
data class BoxEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val host: String,
    val rpcPort: Int,
    val pubPort: Int,
    val addedAt: Long,
    val autoSessionEnabled: Boolean,
    val networkSsid: String?,
    val lastSeenAt: Long?,
    val sortIndex: Int,
)

@Entity(
    tableName = "library_folders",
    primaryKeys = ["boxId", "path"],
    foreignKeys = [
        ForeignKey(
            entity = BoxEntity::class,
            parentColumns = ["id"],
            childColumns = ["boxId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("boxId", "parentPath")],
)
data class LibraryFolderEntity(
    val boxId: String,
    /** Relative to the box's music library root — exactly what `play_folder` takes. */
    val path: String,
    val parentPath: String?,
    val displayName: String,
    val hasChildren: Boolean,
    /** Folded name for searching; see [SearchText]. Not indexed — `LIKE '%x%'` cannot use one. */
    @ColumnInfo(defaultValue = "") val searchText: String,
    /** When this row was written, i.e. when its parent level was last scanned. */
    val cachedAt: Long,
    /**
     * When *this folder's own* contents were last scanned, as opposed to when the row
     * itself was written. Drives the "Updated 3 days ago" hint for a level, and is null
     * for a folder that has been seen in a listing but never opened.
     */
    val contentCachedAt: Long?,
)

@Entity(
    tableName = "library_tracks",
    primaryKeys = ["boxId", "url"],
    foreignKeys = [
        ForeignKey(
            entity = BoxEntity::class,
            parentColumns = ["id"],
            childColumns = ["boxId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("boxId", "parentPath")],
)
data class LibraryTrackEntity(
    val boxId: String,
    val url: String,
    val parentPath: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val trackNo: Int?,
    val durationSeconds: Double?,
    /** Folded title, artist, album and file name; see [SearchText]. */
    @ColumnInfo(defaultValue = "") val searchText: String,
)

/**
 * One row of the albums list.
 *
 * [contentUri] is part of the key because artist-and-album stopped being unique the moment a
 * box could have two backends: the same record can be both on the box's disk and in a
 * streaming account, and the two are played by different calls. It is `""` rather than null
 * for everything MPD owns — a primary key column cannot be null, and the domain model maps
 * the empty string back to null at the boundary.
 */
@Entity(
    tableName = "library_albums",
    primaryKeys = ["boxId", "albumArtist", "album", "contentUri"],
    foreignKeys = [
        ForeignKey(
            entity = BoxEntity::class,
            parentColumns = ["id"],
            childColumns = ["boxId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("boxId")],
)
data class LibraryAlbumEntity(
    val boxId: String,
    val albumArtist: String,
    val album: String,
    val coverFile: String?,
    /** Folded album and album artist; see [SearchText]. */
    @ColumnInfo(defaultValue = "") val searchText: String,
    val cachedAt: Long,
    /** `""` for MPD, which has no handle of its own — see the note on the entity. */
    @ColumnInfo(defaultValue = "") val contentUri: String = "",
    @ColumnInfo(defaultValue = "mpd") val provider: String = "mpd",
    @ColumnInfo(defaultValue = "album") val contentType: String = "album",
)

@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(
            entity = BoxEntity::class,
            parentColumns = ["id"],
            childColumns = ["boxId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("boxId")],
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val boxId: String,
    val label: String,
    /** `FOLDER`, `ALBUM` or `TRACK`, stored as its name so the column stays readable. */
    val type: String,
    val folder: String?,
    val albumArtist: String?,
    val album: String?,
    /** MPD URL of a single file, set only for a `TRACK` row (schema version 2). */
    val trackUrl: String?,
    /**
     * Which backend owns an `ALBUM` row, and that backend's handle for it (schema version 4).
     *
     * Nullable rather than defaulted, because null is the true answer for every favourite
     * saved before a box could have more than one backend, and for every folder and track
     * row. A null reads as MPD, which is what those rows are.
     */
    val provider: String?,
    val contentUri: String?,
    val coverFile: String?,
    val sortIndex: Int,
    val launchCount: Int,
    val shortcutPinned: Boolean,
)
