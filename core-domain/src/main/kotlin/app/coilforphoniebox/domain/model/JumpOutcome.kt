package app.coilforphoniebox.domain.model

/**
 * What came of asking the box to jump to a queue position.
 *
 * Three outcomes rather than a boolean, because the box has no single command for this and the
 * ways it can fall short are different enough that the user needs telling apart. A transport
 * failure is *not* one of them — that stays a `Result` failure, as everywhere else.
 */
sealed interface JumpOutcome {

    /** The box is playing the requested position. */
    data object Arrived : JumpOutcome

    /**
     * The queue had to be walked with `next`/`prev` and stopped at [position] instead of the
     * target — a step went missing, or it ran out of attempts or time.
     *
     * Worth surfacing rather than swallowing: the box *is* playing something, just not what
     * was asked for, and silently reporting success would leave the highlighted row lying.
     */
    data class Incomplete(val position: Int) : JumpOutcome

    /**
     * Nothing was sent, because this box can only reach a position by walking and shuffle is
     * on: MPD's `next` with `random` enabled goes to a *random* song rather than the following
     * one, so no number of steps arrives anywhere in particular.
     *
     * Coil says so instead of turning shuffle off on the user's behalf — and the restriction
     * disappears once a box has `play(pos=…)`, which ignores `random`.
     */
    data object BlockedByShuffle : JumpOutcome
}
