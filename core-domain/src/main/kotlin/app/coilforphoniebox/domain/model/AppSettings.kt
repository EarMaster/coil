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

/** Global settings. Everything box-specific lives on [Box] instead (§7.2). */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You would displace the brand colour, so it is off by default (§10.9). */
    val dynamicColor: Boolean = false,
    val sessionMode: SessionMode = SessionMode.APP_ONLY,
    val favoritesLayout: FavoritesLayout = FavoritesLayout.GRID,
    val activeBoxId: String? = null,
    val onboardingComplete: Boolean = false,
)
