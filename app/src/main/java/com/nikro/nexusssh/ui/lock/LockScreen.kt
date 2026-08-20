package com.nikro.nexusssh.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * App lock.
 *
 * Uses the platform prompt so the device PIN, pattern or password works as the fallback: rolling a
 * custom passcode here would add a second, weaker secret to protect. If the device has no secure
 * lock configured at all, the screen says so and lets the user through rather than trapping them.
 */
@Composable
fun LockScreen(
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var status by remember { mutableStateOf(canAuthenticate(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempted by remember { mutableStateOf(false) }

    fun authenticate() {
        if (activity == null) {
            onUnlocked()
            return
        }
        attempted = true
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    error = null
                    onUnlocked()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    error = message.toString()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Nexus SSH")
            .setSubtitle("Your keys and saved passwords are locked")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(info)
    }

    LaunchedEffect(Unit) {
        status = canAuthenticate(context)
        when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> authenticate()
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            -> onUnlocked()

            else -> Unit
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = "Locked",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = error ?: "Authenticate to reach your hosts and keys.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = { authenticate() },
            modifier = Modifier.padding(top = 24.dp),
        ) { Text(if (attempted) "Try again" else "Unlock") }
    }
}

private fun canAuthenticate(context: Context): Int =
    BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
    )
