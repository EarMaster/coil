package app.coilforphoniebox.screenshot

import app.coilforphoniebox.data.di.RepositoryModule
import app.coilforphoniebox.domain.repository.BackupRepository
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.FavoriteRepository
import app.coilforphoniebox.domain.repository.LibraryRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.domain.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Puts the fakes in place of the real repositories for the whole-app goldens.
 *
 * This replaces `:core-data`'s `RepositoryModule` outright, so nothing in a screenshot run
 * ever opens a socket, a database or a DataStore file. `DatabaseModule` is left alone and
 * simply never asked for anything — Room is provided lazily, and with every repository faked
 * there is no longer a caller.
 *
 * Each fake is a singleton *and* is exposed under its own type as well as the interface, so a
 * test can inject the concrete class and change what the box appears to be doing before
 * composing. That is what makes one screen capturable in several states without a module per
 * state.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [RepositoryModule::class])
object FakeRepositoryModule {

    @Provides
    @Singleton
    fun provideFakeBoxes(): FakeBoxRepository = FakeBoxRepository()

    @Provides
    @Singleton
    fun provideFakePlayer(): FakePlayerRepository = FakePlayerRepository(
        status = Fixtures.playing,
        // A URL rather than null, so the whole-app goldens show artwork where a real box
        // would have it. The image loader answers it with a flat colour — see ScreenshotTest.
        coverUrl = "http://phoniebox.local/cover-cache/missing-key.jpg",
    )

    @Provides
    @Singleton
    fun provideFakeLibrary(): FakeLibraryRepository = FakeLibraryRepository(
        folders = mapOf(
            "" to Fixtures.libraryRoot,
            Fixtures.detectiveStories.path to Fixtures.detectiveStories,
        ),
        albums = Fixtures.albums,
        results = Fixtures.searchResults,
    )

    @Provides
    @Singleton
    fun provideFakeFavorites(): FakeFavoriteRepository = FakeFavoriteRepository(Fixtures.favorites)

    @Provides
    @Singleton
    fun provideFakeSettings(): FakeSettingsRepository = FakeSettingsRepository()

    @Provides
    @Singleton
    fun provideFakeBackup(): FakeBackupRepository = FakeBackupRepository()

    @Provides
    fun bindBoxes(fake: FakeBoxRepository): BoxRepository = fake

    @Provides
    fun bindPlayer(fake: FakePlayerRepository): PlayerRepository = fake

    @Provides
    fun bindLibrary(fake: FakeLibraryRepository): LibraryRepository = fake

    @Provides
    fun bindFavorites(fake: FakeFavoriteRepository): FavoriteRepository = fake

    @Provides
    fun bindSettings(fake: FakeSettingsRepository): SettingsRepository = fake

    @Provides
    fun bindBackup(fake: FakeBackupRepository): BackupRepository = fake
}
