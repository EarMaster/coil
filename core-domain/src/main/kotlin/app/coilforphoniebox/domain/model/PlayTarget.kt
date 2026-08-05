package app.coilforphoniebox.domain.model

/** Something the box can be asked to start playing. */
sealed interface PlayTarget {
    data class Folder(val path: String) : PlayTarget

    data class Album(val albumArtist: String, val album: String) : PlayTarget

    /** A single file, by MPD URL. */
    data class Track(val url: String) : PlayTarget
}
