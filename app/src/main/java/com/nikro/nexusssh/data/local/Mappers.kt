package com.nikro.nexusssh.data.local

import com.nikro.nexusssh.domain.model.ConnectionHistoryEntry
import com.nikro.nexusssh.domain.model.Host
import com.nikro.nexusssh.domain.model.HostGroup
import com.nikro.nexusssh.domain.model.Identity
import com.nikro.nexusssh.domain.model.KnownHost
import com.nikro.nexusssh.domain.model.PortForwardRule
import com.nikro.nexusssh.domain.model.Snippet
import com.nikro.nexusssh.domain.model.SshKey

// ------------------------------------------------------------------------------------------
// Entity <-> domain mapping. Keeping this explicit (rather than reusing the entity in the UI)
// means the persistence schema can evolve without rippling through the whole app.
// ------------------------------------------------------------------------------------------

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

fun GroupEntity.toDomain(): HostGroup = HostGroup(
    id, name, parentId, color, defaultIdentityId, defaultPort, defaultJumpHostId, agentForwarding, sortOrder,
)

fun HostGroup.toEntity(): GroupEntity = GroupEntity(
    id, name, parentId, color, defaultIdentityId, defaultPort, defaultJumpHostId, agentForwarding, sortOrder,
)

fun IdentityEntity.toDomain(): Identity =
    Identity(id, label, username, sealedPassword, keyId, askPasswordEveryTime)

fun Identity.toEntity(): IdentityEntity =
    IdentityEntity(id, label, username, sealedPassword, keyId, askPasswordEveryTime)

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

fun KnownHostEntity.toDomain(): KnownHost =
    KnownHost(id, hostPattern, port, keyType, publicKeyBase64, fingerprintSha256, addedAt, lastSeenAt, isRevoked)

fun KnownHost.toEntity(): KnownHostEntity =
    KnownHostEntity(id, hostPattern, port, keyType, publicKeyBase64, fingerprintSha256, addedAt, lastSeenAt, isRevoked)

fun SnippetEntity.toDomain(): Snippet =
    Snippet(id, name, script, description, tags, packageName, runInBackground, createdAt)

fun Snippet.toEntity(): SnippetEntity =
    SnippetEntity(id, name, script, description, tags, packageName, runInBackground, createdAt)

fun PortForwardEntity.toDomain(): PortForwardRule =
    PortForwardRule(id, label, type, hostId, bindAddress, localPort, remoteHost, remotePort, autoStart, enabled)

fun PortForwardRule.toEntity(): PortForwardEntity =
    PortForwardEntity(id, label, type, hostId, bindAddress, localPort, remoteHost, remotePort, autoStart, enabled)

fun ConnectionHistoryEntity.toDomain(): ConnectionHistoryEntry =
    ConnectionHistoryEntry(id, hostId, label, address, startedAt, endedAt, bytesIn, bytesOut, succeeded, errorMessage)

fun ConnectionHistoryEntry.toEntity(): ConnectionHistoryEntity =
    ConnectionHistoryEntity(id, hostId, label, address, startedAt, endedAt, bytesIn, bytesOut, succeeded, errorMessage)
