package app.coilforphoniebox.transport.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Scope that lives as long as the process and owns the transport's coroutines. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TransportScope

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    @TransportScope
    fun provideTransportScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
