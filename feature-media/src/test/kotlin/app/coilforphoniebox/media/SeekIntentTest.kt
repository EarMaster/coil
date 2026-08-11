package app.coilforphoniebox.media

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * media3 resolves a seek against the timeline it was given and hands down the result; these are
 * the readings of that result that were wrong, and each one reached the box as a command it
 * should not have been sent.
 */
class SeekIntentTest {

    private fun intent(
        seekCommand: Int,
        mediaItemIndex: Int = 0,
        positionMs: Long = C.TIME_UNSET,
        queuePosition: Int? = 3,
        shuffle: Boolean = false,
        elapsedSeconds: Double? = null,
    ) = seekIntentFor(
        seekCommand = seekCommand,
        mediaItemIndex = mediaItemIndex,
        positionMs = positionMs,
        queuePosition = queuePosition,
        shuffle = shuffle,
        elapsedSeconds = elapsedSeconds,
    )

    /** The end of a trusted queue: sending `next` here runs the box's end-of-playlist action. */
    @Test
    fun `an unresolved next in the real queue is ignored`() {
        assertEquals(
            SeekIntent.Ignore,
            intent(Player.COMMAND_SEEK_TO_NEXT, mediaItemIndex = C.INDEX_UNSET),
        )
        assertEquals(
            SeekIntent.Ignore,
            intent(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, mediaItemIndex = C.INDEX_UNSET),
        )
    }

    /**
     * The fallback timeline is one item, so media3 says "nothing to seek to" about every next and
     * previous. Reading that as "do nothing" would disable the notification's buttons outright.
     */
    @Test
    fun `an unresolved next outside the queue still means next`() {
        assertEquals(
            SeekIntent.Next,
            intent(
                Player.COMMAND_SEEK_TO_NEXT,
                mediaItemIndex = C.INDEX_UNSET,
                queuePosition = null,
            ),
        )
    }

    /**
     * With shuffle on, media3 calls the last position the end of the timeline — it has no shuffle
     * order to consult — while MPD would happily pick another track.
     */
    @Test
    fun `an unresolved next with shuffle on still means next`() {
        assertEquals(
            SeekIntent.Next,
            intent(Player.COMMAND_SEEK_TO_NEXT, mediaItemIndex = C.INDEX_UNSET, shuffle = true),
        )
    }

    @Test
    fun `a resolved next means next`() {
        assertEquals(SeekIntent.Next, intent(Player.COMMAND_SEEK_TO_NEXT, mediaItemIndex = 4))
    }

    /** Position zero on the current item is how media3 spells "start this track again". */
    @Test
    fun `previous on the current item at position zero restarts the track`() {
        assertEquals(
            SeekIntent.WithinTrack(0.0),
            intent(Player.COMMAND_SEEK_TO_PREVIOUS, mediaItemIndex = 3, positionMs = 0L),
        )
    }

    @Test
    fun `previous naming another item goes back a track`() {
        assertEquals(
            SeekIntent.Previous,
            intent(Player.COMMAND_SEEK_TO_PREVIOUS, mediaItemIndex = 2, positionMs = 0L),
        )
    }

    /**
     * Outside the queue media3 cannot tell the two apart — a one-item timeline has no previous
     * item, so every previous arrives as position zero on index zero — so the same boundary is
     * applied here against what the box says it has played.
     */
    @Test
    fun `previous outside the queue follows the elapsed position`() {
        assertEquals(
            SeekIntent.WithinTrack(0.0),
            intent(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                positionMs = 0L,
                queuePosition = null,
                elapsedSeconds = 40.0,
            ),
        )
        assertEquals(
            SeekIntent.Previous,
            intent(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                positionMs = 0L,
                queuePosition = null,
                elapsedSeconds = 1.5,
            ),
        )
    }

    @Test
    fun `a queue row tapped in a controller is a jump to that position`() {
        assertEquals(
            SeekIntent.ToQueuePosition(6),
            intent(Player.COMMAND_SEEK_TO_MEDIA_ITEM, mediaItemIndex = 6, positionMs = 0L),
        )
    }

    /**
     * The crash-adjacent one: outside the queue, index 0 is the *playing song*, not queue
     * position 0 — sending it to `playAt` would restart the album from its first track.
     */
    @Test
    fun `seeking to the only item of a fallback timeline stays inside the track`() {
        assertEquals(
            SeekIntent.WithinTrack(12.5),
            intent(
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                mediaItemIndex = 0,
                positionMs = 12_500L,
                queuePosition = null,
            ),
        )
    }

    @Test
    fun `an ordinary scrub seeks inside the track`() {
        assertEquals(
            SeekIntent.WithinTrack(90.0),
            intent(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, positionMs = 90_000L),
        )
    }

    /** `C.TIME_UNSET` divided down is a seek to some 292 million years before the track began. */
    @Test
    fun `an unset position is ignored rather than sent as a number`() {
        assertEquals(
            SeekIntent.Ignore,
            intent(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, positionMs = C.TIME_UNSET),
        )
    }

    @Test
    fun `a negative position is clamped rather than sent`() {
        assertEquals(
            SeekIntent.WithinTrack(0.0),
            intent(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, positionMs = -5_000L),
        )
    }
}
