package com.nikro.nexusssh.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nikro.nexusssh.domain.model.BackspaceMode
import com.nikro.nexusssh.domain.model.ConnectionHistoryEntry
import com.nikro.nexusssh.domain.model.ForwardType
import com.nikro.nexusssh.domain.model.Host
import com.nikro.nexusssh.domain.model.HostGroup
import com.nikro.nexusssh.domain.model.Identity
import com.nikro.nexusssh.domain.model.KnownHost
import com.nikro.nexusssh.domain.model.PortForwardRule
import com.nikro.nexusssh.domain.model.Protocol
import com.nikro.nexusssh.domain.model.Snippet
import com.nikro.nexusssh.domain.model.SshKey
import com.nikro.nexusssh.domain.model.SshKeyType

// =============================================================================================
// Entities
//
// Referential integrity is handled in the repositories instead of with SQLite foreign keys:
// deleting a key or a group must *null out* the reference rather than cascade-delete the host,
// and Room's ON DELETE SET NULL would silently rewrite rows behind the repository's back.
// =============================================================================================

@Entity(tableName = "groups", indices = [Index("parentId")])
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val color: Int? = null,
    val defaultIdentityId: Long? = null,
    val defaultPort: Int? = null,
    val defaultJumpHostId: Long? = null,
    val agentForwarding: Boolean = false,
    val sortOrder: Int = 0,
)

@Entity(tableName = "identities", indices = [Index("keyId")])
data class IdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val username: String,
    val sealedPassword: String? = null,
    val keyId: Long? = null,
    val askPasswordEveryTime: Boolean = false,
)

@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val type: SshKeyType,
    val sealedPrivateKey: String,
    val publicKeyLine: String,
    val fingerprintSha256: String,
    val fingerprintMd5: String,
    val comment: String = "",
    val isPassphraseProtected: Boolean = false,
    val sealedPassphrase: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "generated",
    val bits: Int = 0,
)

@Entity(
    tableName = "hosts",
    indices = [Index("groupId"), Index("identityId"), Index("keyId"), Index("jumpHostId"), Index("lastConnectedAt")],
)
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val protocol: Protocol = Protocol.SSH,
    val groupId: Long? = null,
    val identityId: Long? = null,
    val username: String? = null,
    val sealedPassword: String? = null,
    val keyId: Long? = null,
    val jumpHostId: Long? = null,
    val agentForwarding: Boolean = false,
    val x11Forwarding: Boolean = false,
    val compression: Boolean = false,
    val keepAliveSeconds: Int = 30,
    val connectTimeoutMs: Int = 15_000,
    val charset: String = "UTF-8",
    val terminalType: String = "xterm-256color",
    val themeName: String? = null,
    val fontSizeSp: Int? = null,
    val backspaceMode: BackspaceMode = BackspaceMode.DELETE,
    val startupSnippetId: Long? = null,
    val environment: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val color: Int? = null,
    val notes: String = "",
    val isFavorite: Boolean = false,
    val strictHostKeyChecking: Boolean = true,
    val lastConnectedAt: Long? = null,
    val connectCount: Int = 0,
    val moshEnabled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "known_hosts",
    indices = [Index(value = ["hostPattern", "port", "keyType"], unique = true)],
)
data class KnownHostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostPattern: String,
    val port: Int,
    val keyType: String,
    val publicKeyBase64: String,
    val fingerprintSha256: String,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val isRevoked: Boolean = false,
)

@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val script: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val packageName: String? = null,
    val runInBackground: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "port_forwards", indices = [Index("hostId")])
data class PortForwardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val type: ForwardType,
    val hostId: Long,
    val bindAddress: String = "127.0.0.1",
    val localPort: Int = 8080,
    val remoteHost: String = "localhost",
    val remotePort: Int = 80,
    val autoStart: Boolean = false,
    val enabled: Boolean = true,
)

@Entity(tableName = "history", indices = [Index("hostId"), Index("startedAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long?,
    val label: String,
    val address: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val succeeded: Boolean = true,
    val errorMessage: String? = null,
)

/** Persistent log of SFTP transfers so the queue survives process death. */
@Entity(tableName = "transfers", indices = [Index("hostId"), Index("queuedAt")])
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val hostLabel: String,
    @ColumnInfo(name = "isUpload") val upload: Boolean,
    val localPath: String,
    val remotePath: String,
    val fileName: String,
    val totalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val state: String = "queued", // queued | running | paused | done | failed | cancelled
    val errorMessage: String? = null,
    val queuedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
)

// =============================================================================================
// Mappers
// =============================================================================================

fun GroupEntity.toDomain(): HostGroup = HostGroup(
    id = id,
    name = name,
    parentId = parentId,
    color = color,
    defaultIdentityId = defaultIdentityId,
    defaultPort = defaultPort,
    defaultJumpHostId = defaultJumpHostId,
    agentForwarding = agentForwarding,
    sortOrder = sortOrder,
)

fun HostGroup.toEntity(): GroupEntity = GroupEntity(
    id = id,
    name = name,
    parentId = parentId,
    color = color,
    defaultIdentityId = defaultIdentityId,
    defaultPort = defaultPort,
    defaultJumpHostId = defaultJumpHostId,
    agentForwarding = agentForwarding,
    sortOrder = sortOrder,
)

fun IdentityEntity.toDomain(): Identity = Identity(
    id = id,
    label = label,
    username = username,
    sealedPassword = sealedPassword,
    keyId = keyId,
    askPasswordEveryTime = askPasswordEveryTime,
)

fun Identity.toEntity(): IdentityEntity = IdentityEntity(
    id = id,
    label = label,
    username = username,
    sealedPassword = sealedPassword,
    keyId = keyId,
    askPasswordEveryTime = askPasswordEveryTime,
)

fun SshKeyEntity.toDomain(): SshKey = SshKey(
    id = id,
    label = label,
    type = type,
    sealedPrivateKey = sealedPrivateKey,
    publicKeyLine = publicKeyLine,
    fingerprintSha256 = fingerprintSha256,
    fingerprintMd5 = fingerprintMd5,
    comment = comment,
    isPassphraseProtected = isPassphraseProtected,
    sealedPassphrase = sealedPassphrase,
    createdAt = createdAt,
    source = source,
    bits = bits,
)

fun SshKey.toEntity(): SshKeyEntity = SshKeyEntity(
    id = id,
    label = label,
    type = type,
    sealedPrivateKey = sealedPrivateKey,
    publicKeyLine = publicKeyLine,
    fingerprintSha256 = fingerprintSha256,
    fingerprintMd5 = fingerprintMd5,
    comment = comment,
    isPassphraseProtected = isPassphraseProtected,
    sealedPassphrase = sealedPassphrase,
    createdAt = createdAt,
    source = source,
    bits = bits,
)

fun HostEntity.toDomain(): Host = Host(
    id = id,
    label = label,
    hostname = hostname,
    port = port,
    protocol = protocol,
    groupId = groupId,
    identityId = identityId,
    username = username,
    sealedPassword = sealedPassword,
    keyId = keyId,
    jumpHostId = jumpHostId,
    agentForwarding = agentForwarding,
    x11Forwarding = x11Forwarding,
    compression = compression,
    keepAliveSeconds = keepAliveSeconds,
    connectTimeoutMs = connectTimeoutMs,
    charset = charset,
    terminalType = terminalType,
    themeName = themeName,
    fontSizeSp = fontSizeSp,
    backspaceMode = backspaceMode,
    startupSnippetId = startupSnippetId,
    environment = environment,
    tags = tags,
    color = color,
    notes = notes,
    isFavorite = isFavorite,
    strictHostKeyChecking = strictHostKeyChecking,
    lastConnectedAt = lastConnectedAt,
    connectCount = connectCount,
    moshEnabled = moshEnabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Host.toEntity(): HostEntity = HostEntity(
    id = id,
    label = label,
    hostname = hostname,
    port = port,
    protocol = protocol,
    groupId = groupId,
    identityId = identityId,
    username = username,
    sealedPassword = sealedPassword,
    keyId = keyId,
    jumpHostId = jumpHostId,
    agentForwarding = agentForwarding,
    x11Forwarding = x11Forwarding,
    compression = compression,
    keepAliveSeconds = keepAliveSeconds,
    connectTimeoutMs = connectTimeoutMs,
    charset = charset,
    terminalType = terminalType,
    themeName = themeName,
    fontSizeSp = fontSizeSp,
    backspaceMode = backspaceMode,
    startupSnippetId = startupSnippetId,
    environment = environment,
    tags = tags,
    color = color,
    notes = notes,
    isFavorite = isFavorite,
    strictHostKeyChecking = strictHostKeyChecking,
    lastConnectedAt = lastConnectedAt,
    connectCount = connectCount,
    moshEnabled = moshEnabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun KnownHostEntity.toDomain(): KnownHost = KnownHost(
    id = id,
    hostPattern = hostPattern,
    port = port,
    keyType = keyType,
    publicKeyBase64 = publicKeyBase64,
    fingerprintSha256 = fingerprintSha256,
    addedAt = addedAt,
    lastSeenAt = lastSeenAt,
    isRevoked = isRevoked,
)

fun KnownHost.toEntity(): KnownHostEntity = KnownHostEntity(
    id = id,
    hostPattern = hostPattern,
    port = port,
    keyType = keyType,
    publicKeyBase64 = publicKeyBase64,
    fingerprintSha256 = fingerprintSha256,
    addedAt = addedAt,
    lastSeenAt = lastSeenAt,
    isRevoked = isRevoked,
)

fun SnippetEntity.toDomain(): Snippet = Snippet(
    id = id,
    name = name,
    script = script,
    description = description,
    tags = tags,
    packageName = packageName,
    runInBackground = runInBackground,
    createdAt = createdAt,
)

fun Snippet.toEntity(): SnippetEntity = SnippetEntity(
    id = id,
    name = name,
    script = script,
    description = description,
    tags = tags,
    packageName = packageName,
    runInBackground = runInBackground,
    createdAt = createdAt,
)

fun PortForwardEntity.toDomain(): PortForwardRule = PortForwardRule(
    id = id,
    label = label,
    type = type,
    hostId = hostId,
    bindAddress = bindAddress,
    localPort = localPort,
    remoteHost = remoteHost,
    remotePort = remotePort,
    autoStart = autoStart,
    enabled = enabled,
)

fun PortForwardRule.toEntity(): PortForwardEntity = PortForwardEntity(
    id = id,
    label = label,
    type = type,
    hostId = hostId,
    bindAddress = bindAddress,
    localPort = localPort,
    remoteHost = remoteHost,
    remotePort = remotePort,
    autoStart = autoStart,
    enabled = enabled,
)

fun HistoryEntity.toDomain(): ConnectionHistoryEntry = ConnectionHistoryEntry(
    id = id,
    hostId = hostId,
    label = label,
    address = address,
    startedAt = startedAt,
    endedAt = endedAt,
    bytesIn = bytesIn,
    bytesOut = bytesOut,
    succeeded = succeeded,
    errorMessage = errorMessage,
)

fun ConnectionHistoryEntry.toEntity(): HistoryEntity = HistoryEntity(
    id = id,
    hostId = hostId,
    label = label,
    address = address,
    startedAt = startedAt,
    endedAt = endedAt,
    bytesIn = bytesIn,
    bytesOut = bytesOut,
    succeeded = succeeded,
    errorMessage = errorMessage,
)
