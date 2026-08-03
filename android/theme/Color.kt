package app.coilforphoniebox.ui.theme

import androidx.compose.ui.graphics.Color

/** Brand colours: fixed, independent of theme. For logo, store assets and website. */
object CoilBrand {
    val Deep = Color(0xFF0F6B5C) // Primary colour, icon background
    val Mint = Color(0xFF6DDBC3) // Primary in dark mode
    val Ink = Color(0xFF14191B)  // Text, lockup on light
    val Bone = Color(0xFFE4E7E4) // Neutral background
}

// ---------------------------------------------------------------- Light

val md_light_primary = Color(0xFF0F6B5C)
val md_light_onPrimary = Color(0xFFFFFFFF)
val md_light_primaryContainer = Color(0xFFA8F2DF)
val md_light_onPrimaryContainer = Color(0xFF00201A)

val md_light_secondary = Color(0xFF4A635C)
val md_light_onSecondary = Color(0xFFFFFFFF)
val md_light_secondaryContainer = Color(0xFFCCE8DF)
val md_light_onSecondaryContainer = Color(0xFF06201A)

// Warm counterpoint: reserved for favourites and "currently playing".
val md_light_tertiary = Color(0xFF7A5900)
val md_light_onTertiary = Color(0xFFFFFFFF)
val md_light_tertiaryContainer = Color(0xFFFFDF9B)
val md_light_onTertiaryContainer = Color(0xFF261A00)

val md_light_error = Color(0xFFBA1A1A)
val md_light_onError = Color(0xFFFFFFFF)
val md_light_errorContainer = Color(0xFFFFDAD6)
val md_light_onErrorContainer = Color(0xFF410002)

val md_light_background = Color(0xFFF5FBF7)
val md_light_onBackground = Color(0xFF171D1B)
val md_light_surface = Color(0xFFF5FBF7)
val md_light_onSurface = Color(0xFF171D1B)
val md_light_surfaceVariant = Color(0xFFDBE5E0)
val md_light_onSurfaceVariant = Color(0xFF3F4945)
val md_light_outline = Color(0xFF6F7975)
val md_light_outlineVariant = Color(0xFFBFC9C4)

val md_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_light_surfaceContainerLow = Color(0xFFEFF5F1)
val md_light_surfaceContainer = Color(0xFFE9EFEB)
val md_light_surfaceContainerHigh = Color(0xFFE3E9E5)
val md_light_surfaceContainerHighest = Color(0xFFDDE4E0)

// ---------------------------------------------------------------- Dark
// Deliberately very dark and slightly green tinted: the app is used at
// bedtime, often in a darkened child's bedroom.

val md_dark_primary = Color(0xFF6DDBC3)
val md_dark_onPrimary = Color(0xFF003830)
val md_dark_primaryContainer = Color(0xFF005046)
val md_dark_onPrimaryContainer = Color(0xFF8AF8DF)

val md_dark_secondary = Color(0xFFB1CCC3)
val md_dark_onSecondary = Color(0xFF1C352F)
val md_dark_secondaryContainer = Color(0xFF334B45)
val md_dark_onSecondaryContainer = Color(0xFFCCE8DF)

val md_dark_tertiary = Color(0xFFF0C048)
val md_dark_onTertiary = Color(0xFF412D00)
val md_dark_tertiaryContainer = Color(0xFF5D4200)
val md_dark_onTertiaryContainer = Color(0xFFFFDF9B)

val md_dark_error = Color(0xFFFFB4AB)
val md_dark_onError = Color(0xFF690005)
val md_dark_errorContainer = Color(0xFF93000A)
val md_dark_onErrorContainer = Color(0xFFFFDAD6)

val md_dark_background = Color(0xFF0E1513)
val md_dark_onBackground = Color(0xFFDDE4E0)
val md_dark_surface = Color(0xFF0E1513)
val md_dark_onSurface = Color(0xFFDDE4E0)
val md_dark_surfaceVariant = Color(0xFF3F4945)
val md_dark_onSurfaceVariant = Color(0xFFBFC9C4)
val md_dark_outline = Color(0xFF899390)
val md_dark_outlineVariant = Color(0xFF3F4945)

val md_dark_surfaceContainerLowest = Color(0xFF090F0E)
val md_dark_surfaceContainerLow = Color(0xFF141B19)
val md_dark_surfaceContainer = Color(0xFF1A2220)
val md_dark_surfaceContainerHigh = Color(0xFF242C2A)
val md_dark_surfaceContainerHighest = Color(0xFF2F3735)
