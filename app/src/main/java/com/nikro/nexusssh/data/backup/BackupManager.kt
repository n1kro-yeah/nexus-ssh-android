package com.nikro.nexusssh.data.backup

import com.nikro.nexusssh.core.crypto.CryptoVault
import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.data.local.GroupDao
import com.nikro.nexusssh.data.local.HostDao
import com.nikro.nexusssh.data.local.IdentityDao
import com.nikro.nexusssh.data.local.KnownHostDao
import com.nikro.nexusssh.data.local.PortForwardDao
import com.nikro.nexusssh.data.local.SnippetDao
import com.nikro.nexusssh.data.local.SshKeyDao
import com.nikro.nexusssh.data.local.toDomain
import com.nikro.nexusssh.data.local.toEntity
import com.nikro.nexusssh.domain.model.Host
import com.nikro.nexusssh.domain.model.HostGroup
import com.nikro.nexusssh.domain.model.Identity
import com.nikro.nexusssh.domain.model.KnownHost
import com.nikro.nexusssh.domain.model.PortForwardRule
import com.nikro.nexusssh.domain.model.Snippet
import com.nikro.nexusssh.domain.model.SshKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted export/import of the whole vault.
 *
 * The archive is a single JSON document. Secrets are re-sealed with a password the user types at
 * export time ([CryptoVault.sealPortable]), because the device-bound Keystore key cannot leave a
 * device: a raw database copy would be unrecoverable on a new phone.
 */
@Singleton
class BackupManager @Inject constructor(
    private val vault: CryptoVault,
    private val hostDao: HostDao,
    private val groupDao: GroupDao,
    private val identityDao: IdentityDao,
    private val keyDao: SshKeyDao,
    private val snippetDao: SnippetDao,
    private val forwardDao: PortForwardDao,
    private val knownHostDao: KnownHostDao,
) {

    @Serializable
    data class Archive(
        val version: Int = FORMAT_VERSION,
        val createdAt: Long = System.currentTimeMillis(),
        val application: String = "Nexus SSH",
        val includesSecrets: Boolean,
        val groups: List<HostGroup> = emptyList(),
        val hosts: List<Host> = emptyList(),
        val identities: List<Identity> = emptyList(),
        val keys: List<SshKey> = emptyList(),
        val snippets: List<Snippet> = emptyList(),
        val forwards: List<PortForwardRule> = emptyList(),
        val knownHosts: List<KnownHost> = emptyList(),
    )

    data class ImportSummary(
        val groups: Int = 0,
        val hosts: Int = 0,
        val identities: Int = 0,
        val keys: Int = 0,
        val snippets: Int = 0,
        val forwards: Int = 0,
        val knownHosts: Int = 0,
        val warnings: List<String> = emptyList(),
    ) {
        val total: Int get() = groups + hosts + identities + keys + snippets + forwards + knownHosts
    }

    /**
     * Builds the archive text.
     *
     * @param password when non-null every secret is re-sealed with it; when null secrets are
     * dropped entirely and only the structure is exported.
     */
    suspend fun export(password: CharArray?): String {
        val includeSecrets = password != null && password.isNotEmpty()

        fun reseal(sealed: String?): String? {
            if (!includeSecrets || sealed == null) return null
            val plaintext = vault.open(sealed) ?: return null
            return vault.sealPortable(plaintext, password!!.copyOf())
        }

        val archive = Archive(
            includesSecrets = includeSecrets,
            groups = groupDao.getAll().map { it.toDomain() },
            hosts = hostDao.getAll().map { entity ->
                entity.toDomain().copy(sealedPassword = reseal(entity.sealedPassword))
            },
            identities = identityDao.getAll().map { entity ->
                entity.toDomain().copy(sealedPassword = reseal(entity.sealedPassword))
            },
            keys = keyDao.getAll().mapNotNull { entity ->
                val privateKey = reseal(entity.sealedPrivateKey)
                if (includeSecrets && privateKey == null) {
                    AppLogger.w(TAG, "Skipping key ${entity.label}: the vault could not open it")
                    return@mapNotNull null
                }
                entity.toDomain().copy(
                    sealedPrivateKey = privateKey ?: "",
                    sealedPassphrase = reseal(entity.sealedPassphrase),
                )
            },
            snippets = snippetDao.getAll().map { it.toDomain() },
            forwards = forwardDao.getAll().map { it.toDomain() },
            knownHosts = knownHostDao.getAll().map { it.toDomain() },
        )

        password?.fill(Char.MIN_VALUE)
        AppLogger.i(TAG, "Exported ${archive.hosts.size} hosts, secrets=$includeSecrets")
        return JSON.encodeToString(archive)
    }

    /**
     * Restores an archive. IDs are re-assigned and cross-references are remapped onto new IDs.
     */
    suspend fun import(text: String, password: CharArray?, merge: Boolean = true): ImportSummary {
        val archive = runCatching { JSON.decodeFromString<Archive>(text) }.getOrElse {
            AppLogger.e(TAG, "Unreadable archive", it)
            return ImportSummary(warnings = listOf("The file is not a Nexus SSH archive"))
        }
        if (archive.version > FORMAT_VERSION) {
            return ImportSummary(warnings = listOf("The archive was written by a newer version of the app"))
        }

        val warnings = mutableListOf<String>()
        val needsPassword = archive.includesSecrets
        if (needsPassword && (password == null || password.isEmpty())) {
            return ImportSummary(warnings = listOf("This archive is encrypted; a password is required"))
        }

        fun unseal(portable: String?): String? {
            if (portable.isNullOrBlank() || password == null) return null
            return try {
                vault.seal(vault.openPortable(portable, password.copyOf()))
            } catch (error: Throwable) {
                warnings += "A secret could not be decrypted - wrong password?"
                AppLogger.w(TAG, "Portable unseal failed: ${error.message}")
                null
            }
        }

        if (!merge) {
            AppLogger.w(TAG, "Replace mode requested; importing archive records with new IDs")
        }

        // Groups are inserted first, then parent pointers are remapped in a second pass.
        val groupIdMap = mutableMapOf<Long, Long>()
        archive.groups.sortedBy { it.parentId ?: 0 }.forEach { group ->
            val newId = groupDao.upsert(group.copy(id = 0, parentId = null).toEntity())
            groupIdMap[group.id] = newId
        }
        archive.groups.forEach { group ->
            val newId = groupIdMap[group.id] ?: return@forEach
            val parent = group.parentId?.let { groupIdMap[it] }
            if (parent != null) {
                groupDao.findById(newId)?.let { groupDao.upsert(it.copy(parentId = parent)) }
            }
        }

        val keyIdMap = mutableMapOf<Long, Long>()
        archive.keys.forEach { key ->
            val sealedPrivate = unseal(key.sealedPrivateKey)
            if (sealedPrivate == null && key.sealedPrivateKey.isNotBlank()) {
                warnings += "Key \"${key.label}\" was skipped"
                return@forEach
            }
            val newId = keyDao.upsert(
                key.copy(
                    id = 0,
                    sealedPrivateKey = sealedPrivate.orEmpty(),
                    sealedPassphrase = unseal(key.sealedPassphrase),
                ).toEntity(),
            )
            keyIdMap[key.id] = newId
        }

        val identityIdMap = mutableMapOf<Long, Long>()
        archive.identities.forEach { identity ->
            val newId = identityDao.upsert(
                identity.copy(
                    id = 0,
                    sealedPassword = unseal(identity.sealedPassword),
                    keyId = identity.keyId?.let { keyIdMap[it] },
                ).toEntity(),
            )
            identityIdMap[identity.id] = newId
        }

        // Hosts are also a two-pass import because jump hosts reference other hosts.
        val hostIdMap = mutableMapOf<Long, Long>()
        archive.hosts.forEach { host ->
            val newId = hostDao.insert(
                host.copy(
                    id = 0,
                    groupId = host.groupId?.let { groupIdMap[it] },
                    identityId = host.identityId?.let { identityIdMap[it] },
                    keyId = host.keyId?.let { keyIdMap[it] },
                    jumpHostId = null,
                    sealedPassword = unseal(host.sealedPassword),
                ).toEntity(),
            )
            hostIdMap[host.id] = newId
        }
        archive.hosts.forEach { host ->
            val jump = host.jumpHostId?.let { hostIdMap[it] } ?: return@forEach
            val newId = hostIdMap[host.id] ?: return@forEach
            hostDao.findById(newId)?.let { hostDao.update(it.copy(jumpHostId = jump)) }
        }

        archive.snippets.forEach { snippetDao.upsert(it.copy(id = 0).toEntity()) }
        var importedForwards = 0
        archive.forwards.forEach { rule ->
            val hostId = hostIdMap[rule.hostId]
            if (hostId == null) {
                warnings += "Port forward \"${rule.label}\" has no matching host"
                return@forEach
            }
            forwardDao.upsert(rule.copy(id = 0, hostId = hostId).toEntity())
            importedForwards++
        }

        archive.knownHosts.forEach { knownHostDao.insert(it.copy(id = 0).toEntity()) }

        password?.fill(Char.MIN_VALUE)
        val summary = ImportSummary(
            groups = groupIdMap.size,
            hosts = hostIdMap.size,
            identities = identityIdMap.size,
            keys = keyIdMap.size,
            snippets = archive.snippets.size,
            forwards = importedForwards,
            knownHosts = archive.knownHosts.size,
            warnings = warnings,
        )
        AppLogger.i(TAG, "Imported ${summary.total} records (${warnings.size} warnings)")
        return summary
    }

    /** Quick structural check used before showing the password prompt. */
    fun inspect(text: String): Archive? = runCatching { JSON.decodeFromString<Archive>(text) }.getOrNull()

    fun suggestedFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "nexus-ssh-backup-$stamp.json"
    }

    private companion object {
        const val TAG = "BackupManager"
        const val FORMAT_VERSION = 1
        val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
