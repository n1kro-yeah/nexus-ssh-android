package com.nikro.nexusssh.ssh

import com.nikro.nexusssh.core.log.AppLogger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Android ships a stripped-down BouncyCastle under the name "BC" that lacks most of what SSHJ
 * needs (Ed25519, modern KDFs, PKCS#8 parsing). The fix every SSH app on Android has to apply is
 * to drop the platform provider and insert the bundled one at the top of the list.
 *
 * Must run before the first SSHJ call - [com.nikro.nexusssh.NexusApplication] does that on start,
 * and [SshConnection.connect] repeats it defensively because it is idempotent.
 */
object SecurityProviderInstaller {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            runCatching {
                // Remove Android's cut-down copy, then insert the full one we package.
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                val position = Security.insertProviderAt(BouncyCastleProvider(), 1)
                AppLogger.i(TAG, "BouncyCastle installed at position $position")
            }.onFailure { error ->
                AppLogger.e(TAG, "Failed to install BouncyCastle", error)
            }

            // net.i2p EdDSA is used directly by SSHJ, but registering it lets KeyFactory
            // round-trip Ed25519 keys through the standard JCA API as well.
            runCatching {
                val provider = Class.forName("net.i2p.crypto.eddsa.EdDSASecurityProvider")
                    .getDeclaredConstructor()
                    .newInstance() as java.security.Provider
                if (Security.getProvider(provider.name) == null) {
                    Security.addProvider(provider)
                }
            }.onFailure { AppLogger.d(TAG, "EdDSA provider not registered: ${it.message}") }

            installed = true
        }
    }

    /** Names of the registered providers, shown on the debug screen. */
    fun providerSummary(): List<String> =
        Security.getProviders().map { "${it.name} ${it.versionStr}" }

    private const val TAG = "SecurityProvider"
}
