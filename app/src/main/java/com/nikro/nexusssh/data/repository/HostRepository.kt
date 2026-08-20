package com.nikro.nexusssh.data.repository

import com.nikro.nexusssh.data.local.GroupDao
import com.nikro.nexusssh.data.local.HostDao
import com.nikro.nexusssh.data.local.IdentityDao
import com.nikro.nexusssh.data.local.toDomain
import com.nikro.nexusssh.data.local.toEntity
import com.nikro.nexusssh.domain.model.Host
import com.nikro.nexusssh.domain.model.HostGroup
import com.nikro.nexusssh.domain.model.Identity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hosts, groups and identities — the address book of the app.
 *
 * The repository owns the entity/domain mapping and the small amount of logic that belongs to the
 * data itself (grouping for the sidebar, resolving a ProxyJump chain, applying group defaults).
 */
@Singleton
class HostRepository @Inject constructor(
    private val hostDao: HostDao,
    private val groupDao: GroupDao,
    private val identityDao: IdentityDao,
) {

    /** A group header with the hosts under it; `group == null` is the "Ungrouped" bucket. */
    data class GroupedHosts(
        val group: HostGroup?,
        val hosts: List<Host>,
    ) {
        val title: String get() = group?.name ?: "Ungrouped"
        val isEmpty: Boolean get() = hosts.isEmpty()
    }

    // ---------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------

    fun observeHosts(): Flow<List<Host>> =
        hostDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeGroups(): Flow<List<HostGroup>> =
        groupDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeIdentities(): Flow<List<Identity>> =
        identityDao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Hosts arranged for the main list: favourites first, then one section per group. */
    fun observeGrouped(): Flow<List<GroupedHosts>> =
        combine(observeHosts(), observeGroups()) { hosts, groups ->
            val byGroup = hosts.groupBy { it.groupId }
            val sections = groups.map { group ->
                GroupedHosts(group, byGroup[group.id].orEmpty())
            }
            val ungrouped = byGroup[null].orEmpty()
            if (ungrouped.isEmpty()) sections else sections + GroupedHosts(null, ungrouped)
        }

    fun observeFavorites(): Flow<List<Host>> =
        observeHosts().map { hosts -> hosts.filter { it.isFavorite } }

    fun observeRecent(limit: Int = 8): Flow<List<Host>> =
        hostDao.observeRecent(limit).map { list ->
            list.map { it.toDomain() }.filter { it.lastConnectedAt != null }
        }

    fun search(query: String): Flow<List<Host>> =
        hostDao.search(query).map { list -> list.map { it.toDomain() } }

    fun observeHost(id: Long): Flow<Host?> =
        hostDao.observeById(id).map { it?.toDomain() }

    suspend fun host(id: Long): Host? = hostDao.findById(id)?.toDomain()

    suspend fun hosts(ids: List<Long>): List<Host> = hostDao.findByIds(ids).map { it.toDomain() }

    suspend fun allHosts(): List<Host> = hostDao.getAll().map { it.toDomain() }

    suspend fun allGroups(): List<HostGroup> = groupDao.getAll().map { it.toDomain() }

    suspend fun group(id: Long): HostGroup? = groupDao.findById(id)?.toDomain()

    suspend fun identity(id: Long): Identity? = identityDao.findById(id)?.toDomain()

    suspend fun allIdentities(): List<Identity> = identityDao.getAll().map { it.toDomain() }

    suspend fun count(): Int = hostDao.count()

    // ---------------------------------------------------------------------------------------
    // Writes
    // ---------------------------------------------------------------------------------------

    /** Inserts or updates; returns the row id (the same value for updates). */
    suspend fun save(host: Host): Long {
        val stamped = host.copy(updatedAt = System.currentTimeMillis())
        return if (host.id == 0L) {
            hostDao.insert(stamped.toEntity())
        } else {
            hostDao.update(stamped.toEntity())
            host.id
        }
    }

    suspend fun saveAll(hosts: List<Host>) = hostDao.upsertAll(hosts.map { it.toEntity() })

    suspend fun delete(host: Host) {
        // Anything pointing at this host as a jump host has to be cleaned up first.
        hostDao.detachJumpHost(host.id)
        hostDao.deleteById(host.id)
    }

    suspend fun deleteById(id: Long) {
        hostDao.detachJumpHost(id)
        hostDao.deleteById(id)
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = hostDao.setFavorite(id, favorite)

    suspend fun moveToGroup(ids: List<Long>, groupId: Long?) = hostDao.moveToGroup(ids, groupId)

    suspend fun markConnected(id: Long) = hostDao.markConnected(id)

    suspend fun saveGroup(group: HostGroup): Long = groupDao.upsert(group.toEntity())

    suspend fun saveGroups(groups: List<HostGroup>) = groupDao.upsertAll(groups.map { it.toEntity() })

    /** Removes a group without deleting its hosts; they fall back to "Ungrouped". */
    suspend fun deleteGroup(id: Long) = groupDao.deleteCascadingToRoot(id)

    suspend fun saveIdentity(identity: Identity): Long = identityDao.upsert(identity.toEntity())

    suspend fun saveIdentities(identities: List<Identity>) =
        identityDao.upsertAll(identities.map { it.toEntity() })

    suspend fun deleteIdentity(id: Long) = identityDao.deleteById(id)

    /** Called when a key is removed so no host keeps a dangling reference. */
    suspend fun detachKey(keyId: Long) {
        hostDao.detachKey(keyId)
        identityDao.detachKey(keyId)
    }

    // ---------------------------------------------------------------------------------------
    // Derived data
    // ---------------------------------------------------------------------------------------

    /**
     * Walks `jumpHostId` to build the ProxyJump chain, ordered from the first hop to the last.
     *
     * A malformed configuration (a host used as its own jump host, or a loop) would otherwise hang
     * the connection, so the walk stops on a repeat and at [MAX_JUMPS].
     */
    suspend fun resolveJumpChain(host: Host): List<Host> {
        val chain = ArrayDeque<Host>()
        val seen = mutableSetOf(host.id)
        var currentId = host.jumpHostId
        while (currentId != null && chain.size < MAX_JUMPS) {
            if (!seen.add(currentId)) break
            val hop = hostDao.findById(currentId)?.toDomain() ?: break
            // Nearest hop is added to the front: SSH connects through them in order.
            chain.addFirst(hop)
            currentId = hop.jumpHostId
        }
        return chain.toList()
    }

    /** Group defaults fill in the blanks of a host, the way `ssh_config` inheritance does. */
    suspend fun applyGroupDefaults(host: Host): Host {
        val group = host.groupId?.let { groupDao.findById(it)?.toDomain() } ?: return host
        return host.copy(
            identityId = host.identityId ?: group.defaultIdentityId,
            port = if (host.port > 0) host.port else group.defaultPort ?: 22,
            jumpHostId = host.jumpHostId ?: group.defaultJumpHostId,
            agentForwarding = host.agentForwarding || group.agentForwarding,
        )
    }

    /** Ancestors of a group, nearest first — used for breadcrumbs. */
    suspend fun groupPath(groupId: Long?): List<HostGroup> {
        val path = mutableListOf<HostGroup>()
        var currentId = groupId
        val seen = mutableSetOf<Long>()
        while (currentId != null && seen.add(currentId) && path.size < MAX_JUMPS) {
            val group = groupDao.findById(currentId)?.toDomain() ?: break
            path += group
            currentId = group.parentId
        }
        return path
    }

    /** Every distinct tag across all hosts, for the filter chips. */
    fun observeTags(): Flow<List<String>> =
        observeHosts().map { hosts -> hosts.flatMap { it.tags }.distinct().sorted() }

    private companion object {
        const val MAX_JUMPS = 10
    }
}
