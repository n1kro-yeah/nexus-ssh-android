package com.nikro.nexusssh.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    suspend fun getAll(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun findById(id: Long): GroupEntity?

    @Upsert
    suspend fun upsert(entity: GroupEntity): Long

    @Upsert
    suspend fun upsertAll(entities: List<GroupEntity>)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE hosts SET groupId = NULL WHERE groupId = :id")
    suspend fun detachHosts(id: Long)

    @Query("UPDATE groups SET parentId = NULL WHERE parentId = :id")
    suspend fun detachChildren(id: Long)

    @androidx.room.Transaction
    suspend fun deleteCascadingToRoot(id: Long) {
        detachHosts(id)
        detachChildren(id)
        deleteById(id)
    }
}

@Dao
interface HostDao {

    @Query("SELECT * FROM hosts ORDER BY isFavorite DESC, label COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts ORDER BY isFavorite DESC, label COLLATE NOCASE ASC")
    suspend fun getAll(): List<HostEntity>

    @Query("SELECT * FROM hosts WHERE id = :id")
    fun observeById(id: Long): Flow<HostEntity?>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun findById(id: Long): HostEntity?

    @Query("SELECT * FROM hosts WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<HostEntity>

    @Query("SELECT * FROM hosts ORDER BY lastConnectedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HostEntity>>

    @Query(
        """
        SELECT * FROM hosts
        WHERE label LIKE '%' || :query || '%'
           OR hostname LIKE '%' || :query || '%'
           OR IFNULL(username, '') LIKE '%' || :query || '%'
           OR tags LIKE '%' || :query || '%'
           OR notes LIKE '%' || :query || '%'
        ORDER BY isFavorite DESC, label COLLATE NOCASE ASC
        """,
    )
    fun search(query: String): Flow<List<HostEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HostEntity): Long

    @Update
    suspend fun update(entity: HostEntity)

    @Upsert
    suspend fun upsertAll(entities: List<HostEntity>)

    @Delete
    suspend fun delete(entity: HostEntity)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE hosts SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE hosts SET groupId = :groupId WHERE id IN (:ids)")
    suspend fun moveToGroup(ids: List<Long>, groupId: Long?)

    @Query("UPDATE hosts SET keyId = NULL WHERE keyId = :keyId")
    suspend fun detachKey(keyId: Long)

    @Query("UPDATE hosts SET identityId = NULL WHERE identityId = :identityId")
    suspend fun detachIdentity(identityId: Long)

    @Query("UPDATE hosts SET jumpHostId = NULL WHERE jumpHostId = :hostId")
    suspend fun detachJumpHost(hostId: Long)

    @Query(
        "UPDATE hosts SET lastConnectedAt = :now, connectCount = connectCount + 1, updatedAt = :now WHERE id = :id",
    )
    suspend fun markConnectedAt(id: Long, now: Long)

    suspend fun markConnected(id: Long) = markConnectedAt(id, System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM hosts")
    suspend fun count(): Int
}

@Dao
interface IdentityDao {

    @Query("SELECT * FROM identities ORDER BY label COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identities ORDER BY label COLLATE NOCASE ASC")
    suspend fun getAll(): List<IdentityEntity>

    @Query("SELECT * FROM identities WHERE id = :id")
    suspend fun findById(id: Long): IdentityEntity?

    @Upsert
    suspend fun upsert(entity: IdentityEntity): Long

    @Upsert
    suspend fun upsertAll(entities: List<IdentityEntity>)

    @Query("DELETE FROM identities WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE identities SET keyId = NULL WHERE keyId = :keyId")
    suspend fun detachKey(keyId: Long)
}

@Dao
interface SshKeyDao {

    @Query("SELECT * FROM ssh_keys ORDER BY label COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys ORDER BY label COLLATE NOCASE ASC")
    suspend fun getAll(): List<SshKeyEntity>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    fun observeById(id: Long): Flow<SshKeyEntity?>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun findById(id: Long): SshKeyEntity?

    @Query("SELECT * FROM ssh_keys WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<SshKeyEntity>

    @Upsert
    suspend fun upsert(entity: SshKeyEntity): Long

    @Upsert
    suspend fun upsertAll(entities: List<SshKeyEntity>)

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Query(
        "SELECT (SELECT COUNT(*) FROM hosts WHERE keyId = :keyId) + " +
            "(SELECT COUNT(*) FROM identities WHERE keyId = :keyId)",
    )
    suspend fun countUsages(keyId: Long): Int

    @Query("UPDATE hosts SET keyId = NULL WHERE keyId = :keyId")
    suspend fun detachFromHosts(keyId: Long)

    @Query("UPDATE identities SET keyId = NULL WHERE keyId = :keyId")
    suspend fun detachFromIdentities(keyId: Long)

    /** Deleting a key must never orphan a host: the references are cleared in the same tx. */
    @androidx.room.Transaction
    suspend fun deleteById(id: Long) {
        detachFromHosts(id)
        detachFromIdentities(id)
        deleteRow(id)
    }
}

@Dao
interface KnownHostDao {

    @Query("SELECT * FROM known_hosts ORDER BY hostPattern COLLATE NOCASE ASC, port ASC")
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Query("SELECT * FROM known_hosts ORDER BY hostPattern COLLATE NOCASE ASC, port ASC")
    suspend fun getAll(): List<KnownHostEntity>

    @Query("SELECT * FROM known_hosts WHERE hostPattern = :host AND port = :port AND keyType = :keyType LIMIT 1")
    suspend fun find(host: String, port: Int, keyType: String): KnownHostEntity?

    @Query("SELECT * FROM known_hosts WHERE hostPattern = :host AND port = :port")
    suspend fun findForHost(host: String, port: Int): List<KnownHostEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: KnownHostEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<KnownHostEntity>)

    @Query("UPDATE known_hosts SET lastSeenAt = :now WHERE id = :id")
    suspend fun touchAt(id: Long, now: Long)

    suspend fun touch(id: Long) = touchAt(id, System.currentTimeMillis())

    @Query("UPDATE known_hosts SET isRevoked = :revoked WHERE id = :id")
    suspend fun setRevoked(id: Long, revoked: Boolean)

    @Query("DELETE FROM known_hosts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM known_hosts WHERE hostPattern = :host AND port = :port")
    suspend fun deleteForHost(host: String, port: Int)

    @Query("DELETE FROM known_hosts")
    suspend fun deleteAll()
}

@Dao
interface SnippetDao {

    @Query("SELECT * FROM snippets ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<SnippetEntity>

    @Query("SELECT * FROM snippets WHERE id = :id")
    suspend fun findById(id: Long): SnippetEntity?

    @Upsert
    suspend fun upsert(entity: SnippetEntity): Long

    @Upsert
    suspend fun upsertAll(entities: List<SnippetEntity>)

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface PortForwardDao {

    @Query("SELECT * FROM port_forwards ORDER BY label COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PortForwardEntity>>

    @Query("SELECT * FROM port_forwards ORDER BY label COLLATE NOCASE ASC")
    suspend fun getAll(): List<PortForwardEntity>

    @Query("SELECT * FROM port_forwards WHERE hostId = :hostId ORDER BY label COLLATE NOCASE ASC")
    fun observeForHost(hostId: Long): Flow<List<PortForwardEntity>>

    @Query("SELECT * FROM port_forwards WHERE id = :id")
    suspend fun findById(id: Long): PortForwardEntity?

    @Query("SELECT * FROM port_forwards WHERE autoStart = 1 AND enabled = 1")
    suspend fun getAutoStart(): List<PortForwardEntity>

    @Upsert
    suspend fun upsert(entity: PortForwardEntity): Long

    @Upsert
    suspend fun upsertAll(entities: List<PortForwardEntity>)

    @Query("DELETE FROM port_forwards WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM port_forwards WHERE hostId = :hostId")
    suspend fun deleteForHost(hostId: Long)
}

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE hostId = :hostId ORDER BY startedAt DESC")
    fun observeForHost(hostId: Long): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Query(
        """
        UPDATE history
        SET endedAt = :endedAt, bytesIn = :bytesIn, bytesOut = :bytesOut,
            succeeded = :succeeded, errorMessage = :error
        WHERE id = :id
        """,
    )
    suspend fun finish(
        id: Long,
        endedAt: Long,
        bytesIn: Long,
        bytesOut: Long,
        succeeded: Boolean,
        error: String?,
    )

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE startedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM history WHERE succeeded = 0 AND startedAt > :since")
    suspend fun failureCountSince(since: Long): Int
}

@Dao
interface TransferDao {

    @Query("SELECT * FROM transfers ORDER BY queuedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE state IN ('queued', 'running', 'paused') ORDER BY queuedAt ASC")
    fun observeActive(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE state = 'queued' ORDER BY queuedAt ASC LIMIT 1")
    suspend fun nextQueued(): TransferEntity?

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun findById(id: Long): TransferEntity?

    @Insert
    suspend fun insert(entity: TransferEntity): Long

    @Upsert
    suspend fun upsert(entity: TransferEntity): Long

    @Query("UPDATE transfers SET transferredBytes = :bytes WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long)

    @Query("UPDATE transfers SET state = :state, errorMessage = :error, finishedAt = :finishedAt WHERE id = :id")
    suspend fun updateState(id: Long, state: String, error: String?, finishedAt: Long?)

    @Query("UPDATE transfers SET state = 'cancelled', finishedAt = :now WHERE state IN ('queued', 'running', 'paused')")
    suspend fun cancelAll(now: Long)

    @Query("DELETE FROM transfers WHERE state IN ('done', 'failed', 'cancelled')")
    suspend fun clearFinished()

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun deleteById(id: Long)
}
