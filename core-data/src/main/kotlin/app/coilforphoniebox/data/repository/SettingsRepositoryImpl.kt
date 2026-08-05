package app.coilforphoniebox.data.repository

import app.coilforphoniebox.data.settings.SettingsStore
import app.coilforphoniebox.domain.model.AppSettings
import app.coilforphoniebox.domain.model.SessionMode
import app.coilforphoniebox.domain.model.ThemeMode
import app.coilforphoniebox.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val store: SettingsStore,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = store.settings

    override suspend fun current(): AppSettings = store.current()

    override suspend fun setThemeMode(mode: ThemeMode) = store.setThemeMode(mode)

    override suspend fun setDynamicColor(enabled: Boolean) = store.setDynamicColor(enabled)

    override suspend fun setSessionMode(mode: SessionMode) = store.setSessionMode(mode)

    override suspend fun setOnboardingComplete(complete: Boolean) =
        store.setOnboardingComplete(complete)
}
