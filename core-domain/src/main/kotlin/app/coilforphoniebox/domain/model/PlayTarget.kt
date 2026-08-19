package app.coilforphoniebox.domain.model

/** Something the box can be asked to start playing. */
sealed interface PlayTarget {
    data class Folder(val path: String) : PlayTarget

    /**
     * An album, playlist or saved-track collection — whatever the albums list offered.
     *
     * [provider] and [contentUri] say *which* backend on the box owns it. They default to
     * MPD and null, which is both what a box with a single backend always means and what a
     * favourite or shortcut created before either field existed can supply — so the defaults
     * are the compatible answer rather than a guess. They go on the wire only when they say
     * something, because a box predating the provider-neutral player rejects the kwargs
     * outright.
     */
    data class Album(
        val albumArtist: String,
        val album: String,
        val provider: String = LibraryProvider.MPD,
        val contentUri: String? = null,
    ) : PlayTarget

    /** A single file, by MPD URL. */
    data class Track(val url: String) : PlayTarget
}
