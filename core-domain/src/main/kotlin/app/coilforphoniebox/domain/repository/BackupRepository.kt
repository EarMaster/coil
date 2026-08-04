package app.coilforphoniebox.domain.repository

/**
 * Settings export and import. This is the only mitigation for the Play Store /
 * GitHub signing split, where switching channels means uninstalling first and losing
 * app data (§13.2).
 */
interface BackupRepository {
    /** Serialises boxes, favourites and global settings to JSON. */
    suspend fun export(): String

    /**
     * Merges [content] into the current data. Boxes are matched on host and port so
     * importing the same file twice does not duplicate them.
     */
    suspend fun importFrom(content: String): Result<Unit>
}
