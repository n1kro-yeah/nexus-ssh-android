package com.nikro.nexusssh.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The single Room database.
 *
 * Enum and collection converters live in [Converters]. Keeping them outside this file avoids
 * duplicate Room converters and lets KSP generate one stable implementation for every DAO.
 */
@Database(
    entities = [
        GroupEntity::class,
        HostEntity::class,
        IdentityEntity::class,
        SshKeyEntity::class,
        KnownHostEntity::class,
        SnippetEntity::class,
        PortForwardEntity::class,
        HistoryEntity::class,
        TransferEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NexusDatabase : RoomDatabase() {

    abstract fun groupDao(): GroupDao

    abstract fun hostDao(): HostDao

    abstract fun identityDao(): IdentityDao

    abstract fun sshKeyDao(): SshKeyDao

    abstract fun knownHostDao(): KnownHostDao

    abstract fun snippetDao(): SnippetDao

    abstract fun portForwardDao(): PortForwardDao

    abstract fun historyDao(): HistoryDao

    abstract fun transferDao(): TransferDao

    companion object {
        const val NAME = "nexus-ssh.db"
    }
}
