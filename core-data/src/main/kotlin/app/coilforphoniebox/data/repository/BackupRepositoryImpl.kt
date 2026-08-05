package app.coilforphoniebox.data.repository

import app.coilforphoniebox.data.db.BoxDao
import app.coilforphoniebox.data.db.FavoriteDao
import app.coilforphoniebox.data.db.toDomain
import app.coilforphoniebox.data.db.toEntity
import app.coilforphoniebox.data.settings.SettingsStore
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.FavoriteType
import app.coilforphoniebox.domain.model.FavoritesLayout
import app.coilforphoniebox.domain.model.SessionMode
import app.coilforphoniebox.domain.model.ThemeMode
import app.coilforphoniebox.domain.repository.BackupRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings export and import.
 *
 * Play Store builds and GitHub APKs carry different signatures, so moving between them
 * means uninstalling first and losing app data. That cannot be fixed, only made
 * survivable — which is what this is for (§13.2).
 *
 * The library cache is deliberately not exported: it is rebuilt from the box in
 * seconds, and including it would make the file large for no gain.
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val boxDao: BoxDao,
    private val favoriteDao: FavoriteDao,
    private val settings: SettingsStore,
) : BackupRepository {

    @Serializable
    private data class BackupFile(
        val version: Int = FORMAT_VERSION,
        @SerialName("boxes") val boxes: List<BoxBackup> = emptyList(),
        @SerialName("settings") val settings: SettingsBackup = SettingsBackup(),
    )

    @Serializable
    private data class BoxBackup(
        val displayName: String,
        val host: String,
        val rpcPort: Int,
        val pubPort: Int,
        val autoSessionEnabled: Boolean = false,
        val networkSsid: String? = null,
        val favorites: List<FavoriteBackup> = emptyList(),
    )

    @Serializable
    private data class FavoriteBackup(
        val label: String,
        val type: String,
        val folder: String? = null,
        val albumArtist: String? = null,
        val album: String? = null,
        /** Added with format version 2, together with `TRACK` favourites. */
        val trackUrl: String? = null,
        val sortIndex: Int = 0,
        /**
         * Saves the box a round of cover lookups after a reinstall. The name is a hash in
         * the box's own cover cache, so it stays valid for the same box and means nothing
         * on another one — which is fine, since favourites are matched to a box by address.
         */
        val coverFile: String? = null,
    )

    @Serializable
    private data class SettingsBackup(
        val themeMode: String = ThemeMode.SYSTEM.name,
        val dynamicColor: Boolean = false,
        val sessionMode: String = SessionMode.APP_ONLY.name,
        val favoritesLayout: String = FavoritesLayout.GRID.name,
    )

    override suspend fun export(): String {
        val boxes = boxDao.observeAll().first().map { it.toDomain() }
        val current = settings.current()

        val file = BackupFile(
            boxes = boxes.map { box ->
                BoxBackup(
                    displayName = box.displayName,
                    host = box.host,
                    rpcPort = box.rpcPort,
                    pubPort = box.pubPort,
                    autoSessionEnabled = box.autoSessionEnabled,
                    networkSsid = box.networkSsid,
                    favorites = favoriteDao.observeForBox(box.id).first().map { entity ->
                        val favorite = entity.toDomain()
                        FavoriteBackup(
                            label = favorite.label,
                            type = favorite.type.name,
                            folder = favorite.folder,
                            albumArtist = favorite.albumArtist,
                            album = favorite.album,
                            trackUrl = favorite.trackUrl,
                            sortIndex = favorite.sortIndex,
                            coverFile = favorite.coverFile,
                        )
                    },
                )
            },
            settings = SettingsBackup(
                themeMode = current.themeMode.name,
                dynamicColor = current.dynamicColor,
                sessionMode = current.sessionMode.name,
                favoritesLayout = current.favoritesLayout.name,
            ),
        )
        return codec.encodeToString(file)
    }

    override suspend fun importFrom(content: String): Result<Unit> = runCatching {
        val file = codec.decodeFromString<BackupFile>(content)
        require(file.version <= FORMAT_VERSION) { "Unsupported backup version ${file.version}" }

        for (backup in file.boxes) {
            // Matched on address, so importing the same file twice does not produce a
            // second copy of every box — and does not orphan the existing favourites.
            val existing = boxDao.findByAddress(backup.host, backup.rpcPort)
            val boxId = existing?.id ?: java.util.UUID.randomUUID().toString()

            if (existing == null) {
                boxDao.insert(
                    Box(
                        id = boxId,
                        displayName = backup.displayName,
                        host = backup.host,
                        rpcPort = backup.rpcPort,
                        pubPort = backup.pubPort,
                        addedAt = System.currentTimeMillis(),
                        autoSessionEnabled = backup.autoSessionEnabled,
                        networkSsid = backup.networkSsid,
                        sortIndex = boxDao.maxSortIndex() + 1,
                    ).toEntity(),
                )
            }

            val existingFavorites = favoriteDao.observeForBox(boxId).first().map { it.toDomain() }
            for (favorite in backup.favorites) {
                val type = runCatching { FavoriteType.valueOf(favorite.type) }
                    .getOrDefault(FavoriteType.FOLDER)
                val alreadyThere = existingFavorites.any {
                    it.type == type && it.folder == favorite.folder &&
                        it.albumArtist == favorite.albumArtist && it.album == favorite.album &&
                        it.trackUrl == favorite.trackUrl
                }
                if (alreadyThere) continue

                favoriteDao.insert(
                    Favorite(
                        boxId = boxId,
                        label = favorite.label,
                        type = type,
                        folder = favorite.folder,
                        albumArtist = favorite.albumArtist,
                        album = favorite.album,
                        trackUrl = favorite.trackUrl,
                        sortIndex = favorite.sortIndex,
                        coverFile = favorite.coverFile,
                    ).toEntity(),
                )
            }
        }

        settings.setThemeMode(
            ThemeMode.entries.firstOrNull { it.name == file.settings.themeMode } ?: ThemeMode.SYSTEM,
        )
        settings.setDynamicColor(file.settings.dynamicColor)
        settings.setSessionMode(
            SessionMode.entries.firstOrNull { it.name == file.settings.sessionMode }
                ?: SessionMode.APP_ONLY,
        )
        settings.setFavoritesLayout(
            FavoritesLayout.entries.firstOrNull { it.name == file.settings.favoritesLayout }
                ?: FavoritesLayout.GRID,
        )
    }

    private val codec = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private companion object {
        /**
         * 2 added `trackUrl`. The bump is deliberate even though the field is optional:
         * an older build would import a `TRACK` row without its URL, which is a
         * favourite that cannot play. Refusing the file says so instead.
         *
         * `coverFile` and `favoritesLayout` arrived later and deliberately did *not* bump
         * it: an older build that drops them loses a cover it can resolve again from the
         * box and a layout preference, not the ability to play anything.
         */
        const val FORMAT_VERSION = 2
    }
}
