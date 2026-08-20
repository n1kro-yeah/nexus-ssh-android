package com.nikro.nexusssh.ssh

import com.nikro.nexusssh.core.log.AppLogger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Android's built-in provider named BC is deliberately reduced. Replace it with the packaged
 * Bouncy Castle provider before the first SSHJ operation so modern key formats remain available.
 */
object SecurityProviderInstaller {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            runCatching {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                val position = Security.insertProviderAt(BouncyCastleProvider(), 1)
                AppLogger.i(TAG, "BouncyCastle installed at position $position")
            }.onFailure { error ->
                AppLogger.e(TAG, "Failed to install BouncyCastle", error)
            }

            runCatching {
                val provider = Class.forName("net.i2p.crypto.eddsa.EdDSASecurityProvider")
                    .getDeclaredConstructor()
                    .newInstance() as java.security.Provider
                if (Security.getProvider(provider.name) == null) Security.addProvider(provider)
            }.onFailure { AppLogger.d(TAG, "EdDSA provider not registered: ${it.message}") }

            installed = true
        }
    }

    /** Names of registered providers, shown on the debug screen. */
    fun providerSummary(): List<String> =
        Security.getProviders().map { "${it.name} ${it.version}" }

    private const val TAG = "SecurityProvider"
}
