package com.nikro.nexusssh.di

import com.nikro.nexusssh.core.crypto.SshKeyGenerator
import com.nikro.nexusssh.core.crypto.SshKeyImporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Scope that lives as long as the process, used by services and background maintenance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Plain collaborators that have no dependencies of their own.
 *
 * The key generator and importer are stateless; a single instance avoids re-registering the
 * BouncyCastle provider lookup on every screen that offers to make a key.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideKeyGenerator(): SshKeyGenerator = SshKeyGenerator()

    @Provides
    @Singleton
    fun provideKeyImporter(): SshKeyImporter = SshKeyImporter()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
