package app.coilforphoniebox.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow

/**
 * The building blocks of the context menus on the player and in the library.
 *
 * Every favourite entry names its target: "save this" alone leaves the user guessing
 * whether the folder, the album or the one track is meant, which is the ambiguity these
 * menus exist to remove.
 */
@Composable
fun ActionMenuItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
        onClick = onClick,
    )
}

/**
 * A favourite entry. [detail] is the target's own name as it comes from the box — shown
 * verbatim, never composed into a sentence, because it is content and not UI text (§12.4).
 * The star is filled exactly when the target is already saved, so the menu doubles as the
 * answer to "which of these is a favourite?".
 */
@Composable
fun FavoriteMenuItem(
    text: String,
    detail: String?,
    saved: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(text)
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        leadingIcon = {
            Icon(
                imageVector = if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        },
        onClick = onClick,
    )
}
