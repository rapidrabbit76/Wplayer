package dev.wplayer.app.ui.common

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The device's own authentication: fingerprint, face or the screen lock's
 * PIN, pattern or password, in the system's prompt. Weak (class 2)
 * biometrics count, since many devices only rate face unlock that way;
 * this guards what is shown, not encrypted data.
 */
class DeviceAuthentication(private val context: Context) {
    /** Whether the device has a screen lock to authenticate with. */
    val isAvailable: Boolean
        get() = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    fun authenticate(title: String, onSuccess: () -> Unit) {
        val builder = BiometricPrompt.Builder(context).setTitle(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
    }
}

@Composable
fun rememberDeviceAuthentication(): DeviceAuthentication {
    val context = LocalContext.current
    return remember(context) { DeviceAuthentication(context) }
}
