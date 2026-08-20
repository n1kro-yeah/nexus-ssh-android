package com.nikro.nexusssh.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.nikro.nexusssh.domain.model.BackspaceMode
import com.nikro.nexusssh.domain.model.ForwardType
import com.nikro.nexusssh.domain.model.Protocol
import com.nikro.nexusssh.domain.model.SshKeyType
import kotlinx.serialization.json.Json

/**
 * Room converters.
 *
 * Enums are stored by *name* rather than ordinal so reordering an enum in a later release cannot
 * silently reinterpret existing rows. Collections are stored as JSON, which keeps the schema flat
 * and makes the backup format trivially derivable from the entity.
 */
class Converters {

    @TypeConverter
    fun protocolToString(value: Protocol): String = value.name

    @TypeConverter
    fun stringToProtocol(value: String): Protocol =
        runCatching { Protocol.valueOf(value) }.getOrDefault(Protocol.SSH)

    @TypeConverter
    fun keyTypeToString(value: SshKeyType): String = value.name

    @TypeConverter
    fun stringToKeyType(value: String): SshKeyType =
        runCatching { SshKeyType.valueOf(value) }.getOrDefault(SshKeyType.UNKNOWN)

    @TypeConverter
    fun forwardTypeToString(value: ForwardType): String = value.name

    @TypeConverter
    fun stringToForwardType(value: String): ForwardType =
        runCatching { ForwardType.valueOf(value) }.getOrDefault(ForwardType.LOCAL)

    @TypeConverter
    fun backspaceToString(value: BackspaceMode): String = value.name

    @TypeConverter
    fun stringToBackspace(value: String): BackspaceMode =
        runCatching { BackspaceMode.valueOf(value) }.getOrDefault(BackspaceMode.DELETE)

    @TypeConverter
    fun stringListToJson(value: List<String>): String = JSON.encodeToString(value)

    @TypeConverter
    fun jsonToStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else runCatching { JSON.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())

    @TypeConverter
    fun stringMapToJson(value: Map<String, String>): String = JSON.encodeToString(value)

    @TypeConverter
    fun jsonToStringMap(value: String): Map<String, String> =
        if (value.isBlank()) emptyMap() else runCatching { JSON.decodeFromString<Map<String, String>>(value) }.getOrDefault(emptyMap())

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

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
