package app.coilforphoniebox.domain.repository

import app.coilforphoniebox.domain.model.AppSettings
import app.coilforphoniebox.domain.model.SessionMode
import app.coilforphoniebox.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun current(): AppSettings

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setDynamicColor(enabled: Boolean)

    suspend fun setSessionMode(mode: SessionMode)

    suspend fun setOnboardingComplete(complete: Boolean)
}
