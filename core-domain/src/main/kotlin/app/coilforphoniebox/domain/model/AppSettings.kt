package app.coilforphoniebox.domain.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * How far the media session is allowed to reach beyond the app (§8.3).
 *
 * Listed by increasing reach. [OFF] means no media session at all: no notification, no
 * lock screen controls, and nothing that could put either up — Coil then controls the box
 * only from its own screens. [APP_ONLY] is the default: no persistent service, no battery
 * cost, controls only while Coil is open. [AUTOMATIC] keeps a foreground service alive so
 * controls appear on their own when the box starts playing.
 */
enum class SessionMode { OFF, APP_ONLY, AUTOMATIC }

/**
 * How the favourites tab lays its entries out.
 *
 * [GRID] is the default and what favourites are for — a wall of covers a child can aim at
 * without reading. [LIST] trades that reach for density: one row each, so a collection that
 * has outgrown a screenful of tiles can be scanned by name instead of scrolled through.
 */
enum class FavoritesLayout { GRID, LIST }

/**
 * What order the favourites tab shows its entries in.
 *
 * [MANUAL] is the default: the arrangement the user made with move up and move down, which
 * before any move is the order they were saved in. It stays the default because a wall of
 * covers is something a parent arranges — the most-played tile goes top left — and an update
 * that reshuffled that arrangement would take work away rather than add any.
 *
 * [NAME] sorts by label instead, for a collection large enough that finding a title matters
 * more than where it sits. It does not touch [Favorite.sortIndex], so switching back to
 * [MANUAL] brings the arrangement back exactly as it was.
 *
 * [NAME_DESC] is the same order reversed, reached by choosing the alphabetical entry again.
 * It is a third value rather than a `descending` flag beside the enum so that the order
 * stays *one* setting: one DataStore key, one field in the backup file, and a `when` the
 * compiler can still check is exhaustive.
 */
enum class FavoritesSort { MANUAL, NAME, NAME_DESC }

/** Global settings. Everything box-specific lives on [Box] instead (§7.2). */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You would displace the brand colour, so it is off by default (§10.9). */
    val dynamicColor: Boolean = false,
    val sessionMode: SessionMode = SessionMode.APP_ONLY,
    val favoritesLayout: FavoritesLayout = FavoritesLayout.GRID,
    val favoritesSort: FavoritesSort = FavoritesSort.MANUAL,
    /**
     * Whether cover art may be fetched from somewhere other than the box.
     *
     * A provider-neutral box can answer a cover request with an absolute URL belonging to
     * the backend the content came from — Spotify hands back `https://i.scdn.co/…` rather
     * than a name in the box's own cache. Loading one means the phone talks to a third
     * party, which is the one thing §16 and the Data Safety declaration promise it does
     * not do, so it stays off until the user says otherwise.
     */
    val loadExternalCoverArt: Boolean = false,
    val activeBoxId: String? = null,
    val onboardingComplete: Boolean = false,
)
