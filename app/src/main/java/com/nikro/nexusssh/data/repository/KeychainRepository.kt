package com.nikro.nexusssh.data.repository

import com.nikro.nexusssh.core.crypto.CryptoVault
import com.nikro.nexusssh.core.crypto.SshKeyCodec
import com.nikro.nexusssh.core.crypto.SshKeyGenerator
import com.nikro.nexusssh.core.crypto.SshKeyImporter
import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.data.local.SshKeyDao
import com.nikro.nexusssh.data.local.toDomain
import com.nikro.nexusssh.data.local.toEntity
import com.nikro.nexusssh.domain.model.SshKey
import com.nikro.nexusssh.domain.model.SshKeyType
import com.nikro.nexusssh.ssh.PrivateKeyMaterial
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The keychain: private keys, sealed by [CryptoVault] before they ever touch the database.
 *
 * Nothing outside this class handles a plaintext key except the SSH layer, and only for the
 * duration of a handshake: [materialFor] hands out a short-lived [PrivateKeyMaterial] that the
 * connection wipes when it is done.
 */
@Singleton
class KeychainRepository @Inject constructor(
    private val dao: SshKeyDao,
    private val vault: CryptoVault,
    private val generator: SshKeyGenerator,
    private val importer: SshKeyImporter,
) {

    sealed interface ImportOutcome {
        data class Saved(val key: SshKey) : ImportOutcome
        data class NeedsPassphrase(val wrongPassphrase: Boolean, val format: String) : ImportOutcome
        data class Error(val message: String) : ImportOutcome
    }

    fun observeKeys(): Flow<List<SshKey>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeKey(id: Long): Flow<SshKey?> = dao.observeById(id).map { it?.toDomain() }

    suspend fun key(id: Long): SshKey? = dao.findById(id)?.toDomain()

    suspend fun keys(ids: List<Long>): List<SshKey> = dao.findByIds(ids).map { it.toDomain() }

    suspend fun allKeys(): List<SshKey> = dao.getAll().map { it.toDomain() }

    suspend fun usageCount(id: Long): Int = dao.countUsages(id)

    // ---------------------------------------------------------------------------------------
    // Creating keys
    // ---------------------------------------------------------------------------------------

    suspend fun generate(
        label: String,
        type: SshKeyType = SshKeyType.ED25519,
        comment: String = SshKeyGenerator.defaultComment(),
        passphrase: CharArray? = null,
    ): SshKey {
        val generated = generator.generate(type, comment)
        val key = SshKey(
            label = label.ifBlank { "${type.displayName} key" },
            type = generated.type,
            sealedPrivateKey = vault.seal(generated.privateKeyPem)
                ?: error("Could not seal the private key"),
            publicKeyLine = generated.publicKeyLine,
            fingerprintSha256 = generated.fingerprintSha256,
            fingerprintMd5 = generated.fingerprintMd5,
            comment = generated.comment,
            isPassphraseProtected = passphrase != null && passphrase.isNotEmpty(),
            sealedPassphrase = passphrase?.takeIf { it.isNotEmpty() }
                ?.let { vault.seal(String(it)) },
            source = "generated",
            bits = generated.bits,
        )
        val id = dao.upsert(key.toEntity())
        passphrase?.fill(Char.MIN_VALUE)
        return key.copy(id = id)
    }

    /** Imports a private key from pasted text or a picked file. */
    suspend fun import(
        label: String,
        pem: String,
        passphrase: CharArray? = null,
        /** Keep the passphrase in the vault so the key can be used without prompting. */
        rememberPassphrase: Boolean = true,
    ): ImportOutcome {
        return when (val parsed = importer.parseAsync(pem, passphrase)) {
            is SshKeyImporter.ParseResult.NeedsPassphrase ->
                ImportOutcome.NeedsPassphrase(parsed.wrongPassphrase, parsed.sourceFormat)

            is SshKeyImporter.ParseResult.Failure -> ImportOutcome.Error(parsed.message)

            is SshKeyImporter.ParseResult.Success -> {
                val key = SshKey(
                    label = label.ifBlank {
                        parsed.comment.ifBlank { "${parsed.type.displayName} key" }
                    },
                    type = parsed.type,
                    sealedPrivateKey = vault.seal(parsed.normalizedPem)
                        ?: return ImportOutcome.Error("Could not seal the private key"),
                    publicKeyLine = parsed.publicKeyLine,
                    fingerprintSha256 = parsed.fingerprintSha256,
                    fingerprintMd5 = parsed.fingerprintMd5,
                    comment = parsed.comment,
                    // The normalised copy is unencrypted-but-sealed; remember only an original
                    // passphrase that the user has explicitly asked to retain.
                    isPassphraseProtected = false,
                    sealedPassphrase = if (rememberPassphrase && passphrase != null && passphrase.isNotEmpty()) {
                        vault.seal(String(passphrase))
                    } else {
                        null
                    },
                    source = "imported",
                    bits = parsed.bits,
                )
                val id = dao.upsert(key.toEntity())
                passphrase?.fill(Char.MIN_VALUE)
                AppLogger.i(TAG, "Imported ${parsed.sourceFormat} key ${parsed.fingerprintSha256}")
                ImportOutcome.Saved(key.copy(id = id))
            }
        }
    }

    suspend fun rename(id: Long, label: String) {
        val existing = dao.findById(id)?.toDomain() ?: return
        dao.upsert(existing.copy(label = label).toEntity())
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    // ---------------------------------------------------------------------------------------
    // Using keys
    // ---------------------------------------------------------------------------------------

    /** Decrypted PEM. Callers must not persist the result. */
    suspend fun revealPrivateKey(id: Long): String? =
        dao.findById(id)?.let { vault.openToString(it.sealedPrivateKey) }

    suspend fun revealPassphrase(id: Long): CharArray? =
        dao.findById(id)?.sealedPassphrase?.let { vault.openToString(it)?.toCharArray() }

    /** Everything the SSH layer needs for public key authentication with one key. */
    suspend fun materialFor(keyId: Long): PrivateKeyMaterial? {
        val entity = dao.findById(keyId) ?: return null
        val pem = vault.openToString(entity.sealedPrivateKey) ?: return null
        return PrivateKeyMaterial(
            keyId = entity.id,
            label = entity.label,
            pem = pem,
            passphrase = entity.sealedPassphrase?.let { vault.openToString(it)?.toCharArray() },
        )
    }

    suspend fun materialFor(keyIds: List<Long>): List<PrivateKeyMaterial> =
        keyIds.mapNotNull { materialFor(it) }

    /** The public key line, ready to paste into `authorized_keys`. */
    suspend fun publicKeyLine(id: Long): String? = dao.findById(id)?.publicKeyLine

    /** Exports a portable, password-protected envelope for transfer to another device. */
    suspend fun exportPortable(id: Long, passphrase: CharArray): String? {
        val pem = revealPrivateKey(id) ?: return null
        return vault.sealPortable(pem.toByteArray(Charsets.UTF_8), passphrase)
            .also { passphrase.fill(Char.MIN_VALUE) }
    }

    /** Exports normal PEM. The caller must warn before plaintext leaves the device. */
    suspend fun exportPlainPem(id: Long): String? = revealPrivateKey(id)

    /** Details for the key screen, computed from the stored public key line. */
    suspend fun details(id: Long): KeyDetails? {
        val key = dao.findById(id)?.toDomain() ?: return null
        val info = runCatching { SshKeyCodec.parsePublicKeyLine(key.publicKeyLine) }.getOrNull()
        return KeyDetails(
            key = key,
            algorithm = info?.algorithm ?: key.type.opensshName,
            bits = if (key.bits > 0) key.bits else info?.bits ?: 0,
            randomArt = info?.let { SshKeyCodec.randomArt(it.algorithm, it.blob) } ?: "",
            usages = dao.countUsages(id),
        )
    }

    data class KeyDetails(
        val key: SshKey,
        val algorithm: String,
        val bits: Int,
        val randomArt: String,
        val usages: Int,
    )

    private companion object {
        const val TAG = "Keychain"
    }
}
