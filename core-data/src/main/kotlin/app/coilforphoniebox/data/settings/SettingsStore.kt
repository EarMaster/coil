package app.coilforphoniebox.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.coilforphoniebox.domain.model.AppSettings
import app.coilforphoniebox.domain.model.SessionMode
import app.coilforphoniebox.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global settings only. Everything box-specific lives on the box row instead (§7.2),
 * so adding a second box never means untangling shared preferences.
 */
@Singleton
class SettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[THEME_MODE].toThemeMode(),
            dynamicColor = prefs[DYNAMIC_COLOR] ?: false,
            sessionMode = prefs[SESSION_MODE].toSessionMode(),
            activeBoxId = prefs[ACTIVE_BOX_ID],
            onboardingComplete = prefs[ONBOARDING_COMPLETE] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = put(THEME_MODE, mode.name)

    suspend fun setDynamicColor(enabled: Boolean) = put(DYNAMIC_COLOR, enabled)

    suspend fun setSessionMode(mode: SessionMode) = put(SESSION_MODE, mode.name)

    suspend fun setActiveBoxId(boxId: String?) {
        dataStore.edit { prefs ->
            if (boxId == null) prefs.remove(ACTIVE_BOX_ID) else prefs[ACTIVE_BOX_ID] = boxId
        }
    }

    suspend fun setOnboardingComplete(complete: Boolean) = put(ONBOARDING_COMPLETE, complete)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    /** An unknown stored name falls back to the default rather than throwing. */
    private fun String?.toThemeMode(): ThemeMode =
        this?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM

    private fun String?.toSessionMode(): SessionMode =
        this?.let { name -> SessionMode.entries.firstOrNull { it.name == name } } ?: SessionMode.APP_ONLY

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SESSION_MODE = stringPreferencesKey("session_mode")
        val ACTIVE_BOX_ID = stringPreferencesKey("active_box_id")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }
}
