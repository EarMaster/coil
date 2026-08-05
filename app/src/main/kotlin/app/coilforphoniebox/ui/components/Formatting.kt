package app.coilforphoniebox.ui.components

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.coilforphoniebox.R
import java.text.NumberFormat
import java.util.concurrent.TimeUnit

/**
 * Durations, counts and relative times all go through the platform rather than being
 * assembled by hand (§12.2). `DateUtils` knows that some locales separate minutes and
 * seconds differently, and the plural rules differ across the launch set — French
 * treats 0 as singular where English, German, Dutch and Spanish do not.
 */
fun formatDuration(seconds: Double?): String {
    val total = seconds?.takeIf { it.isFinite() && it >= 0 }?.toLong() ?: 0L
    return DateUtils.formatElapsedTime(total)
}

/** Digits differ by locale, so even a volume level goes through the platform. */
fun formatNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)

/** "Updated 3 days ago" and friends. Returns null when nothing has been cached yet. */
@Composable
fun rememberFreshnessLabel(cachedAt: Long?): String? {
    val context = LocalContext.current
    if (cachedAt == null || cachedAt <= 0L) return null

    val elapsed = (System.currentTimeMillis() - cachedAt).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)

    val relative = when {
        minutes < 1 -> context.getString(R.string.time_just_now)
        hours < 1 -> context.resources.getQuantityString(
            R.plurals.time_minutes_ago,
            minutes.toInt(),
            minutes.toInt(),
        )

        days < 1 -> context.resources.getQuantityString(
            R.plurals.time_hours_ago,
            hours.toInt(),
            hours.toInt(),
        )

        else -> context.resources.getQuantityString(
            R.plurals.time_days_ago,
            days.toInt(),
            days.toInt(),
        )
    }
    return context.getString(R.string.library_updated, relative)
}
