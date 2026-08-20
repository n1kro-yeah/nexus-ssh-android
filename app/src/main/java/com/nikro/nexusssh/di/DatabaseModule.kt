package com.nikro.nexusssh.di

import android.content.Context
import androidx.room.Room
import com.nikro.nexusssh.data.local.GroupDao
import com.nikro.nexusssh.data.local.HistoryDao
import com.nikro.nexusssh.data.local.HostDao
import com.nikro.nexusssh.data.local.IdentityDao
import com.nikro.nexusssh.data.local.KnownHostDao
import com.nikro.nexusssh.data.local.NexusDatabase
import com.nikro.nexusssh.data.local.PortForwardDao
import com.nikro.nexusssh.data.local.SnippetDao
import com.nikro.nexusssh.data.local.SshKeyDao
import com.nikro.nexusssh.data.local.TransferDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room wiring.
 *
 * Everything in the database is either non-sensitive metadata or a sealed blob, so the file itself
 * needs no extra encryption layer - the keys and passwords inside it are already sealed by the
 * hardware-backed vault before they are written.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NexusDatabase =
        Room.databaseBuilder(context, NexusDatabase::class.java, NexusDatabase.NAME)
            // The schema is versioned from 1; a destructive fallback would lose someone's hosts,
            // so migrations are added explicitly instead.
            .build()

    @Provides
    fun provideGroupDao(database: NexusDatabase): GroupDao = database.groupDao()

    @Provides
    fun provideHostDao(database: NexusDatabase): HostDao = database.hostDao()

    @Provides
    fun provideIdentityDao(database: NexusDatabase): IdentityDao = database.identityDao()

    @Provides
    fun provideSshKeyDao(database: NexusDatabase): SshKeyDao = database.sshKeyDao()

    @Provides
    fun provideKnownHostDao(database: NexusDatabase): KnownHostDao = database.knownHostDao()

    @Provides
    fun provideSnippetDao(database: NexusDatabase): SnippetDao = database.snippetDao()

    @Provides
    fun providePortForwardDao(database: NexusDatabase): PortForwardDao = database.portForwardDao()

    @Provides
    fun provideHistoryDao(database: NexusDatabase): HistoryDao = database.historyDao()

    @Provides
    fun provideTransferDao(database: NexusDatabase): TransferDao = database.transferDao()
}
