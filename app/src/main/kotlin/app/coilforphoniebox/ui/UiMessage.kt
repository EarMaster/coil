package app.coilforphoniebox.ui

import androidx.annotation.StringRes

/**
 * A one-off message for a snackbar or toast, carried as a resource id rather than
 * finished text: view models never build user-facing strings, which is what keeps
 * every translatable string in `strings.xml` (§12.2).
 */
data class UiMessage(
    @StringRes val text: Int,
    val formatArg: String? = null,
)
