package app.coilforphoniebox.domain.model

/**
 * [maxLevel] comes from `get_soft_max_volume` and is what the media session
 * reports as `deviceVolumeMax`, so the phone's volume keys land in the right range.
 */
data class VolumeStatus(
    val level: Int = 0,
    val maxLevel: Int = 100,
    val muted: Boolean = false,
) {
    val fraction: Float
        get() = if (maxLevel <= 0) 0f else (level.toFloat() / maxLevel).coerceIn(0f, 1f)

    companion object {
        val Unknown = VolumeStatus()
    }
}
