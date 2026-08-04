package app.coilforphoniebox.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.coilforphoniebox.data.db.BoxDao
import app.coilforphoniebox.data.db.CoilDatabase
import app.coilforphoniebox.data.db.FavoriteDao
import app.coilforphoniebox.data.db.LibraryDao
import app.coilforphoniebox.data.repository.BackupRepositoryImpl
import app.coilforphoniebox.data.repository.BoxRepositoryImpl
import app.coilforphoniebox.data.repository.FavoriteRepositoryImpl
import app.coilforphoniebox.data.repository.LibraryRepositoryImpl
import app.coilforphoniebox.data.repository.PlayerRepositoryImpl
import app.coilforphoniebox.data.repository.SettingsRepositoryImpl
import app.coilforphoniebox.domain.repository.BackupRepository
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.FavoriteRepository
import app.coilforphoniebox.domain.repository.LibraryRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CoilDatabase =
        // Room switches SQLite's foreign key enforcement on for us, which is what makes
        // the ON DELETE CASCADE on every child table actually fire.
        Room.databaseBuilder(context, CoilDatabase::class.java, CoilDatabase.NAME).build()

    @Provides
    fun provideBoxDao(database: CoilDatabase): BoxDao = database.boxDao()

    @Provides
    fun provideLibraryDao(database: CoilDatabase): LibraryDao = database.libraryDao()

    @Provides
    fun provideFavoriteDao(database: CoilDatabase): FavoriteDao = database.favoriteDao()

    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("coil_settings")
        }
}

@Module
@InstallIn(SingletonComponent::class)
interface RepositoryModule {

    @Binds
    fun bindBoxRepository(impl: BoxRepositoryImpl): BoxRepository

    @Binds
    fun bindPlayerRepository(impl: PlayerRepositoryImpl): PlayerRepository

    @Binds
    fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    fun bindFavoriteRepository(impl: FavoriteRepositoryImpl): FavoriteRepository

    @Binds
    fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository
}
