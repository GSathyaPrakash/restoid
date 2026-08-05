package io.github.hddq.restoid.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hddq.restoid.R
import io.github.hddq.restoid.data.BackupMode
import io.github.hddq.restoid.ui.screens.settings.components.AppUnlockOnStartRow
import io.github.hddq.restoid.ui.screens.settings.components.PerAppModeRow
import io.github.hddq.restoid.ui.settings.SettingsViewModel

@Composable
fun OptionsSettings(viewModel: SettingsViewModel) {
    val requireAppUnlock by viewModel.requireAppUnlock.collectAsStateWithLifecycle()
    val backupMode by viewModel.backupMode.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column {
        Text(
            text = stringResource(R.string.options_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column {
                AppUnlockOnStartRow(
                    enabled = requireAppUnlock,
                    onCheckedChange = { required ->
                        if (required) {
                            val activity = context as? FragmentActivity
                            if (activity != null) {
                                val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                val canAuthenticate = BiometricManager.from(activity).canAuthenticate(authenticators)

                                if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                                    val promptInfo = BiometricPrompt.PromptInfo.Builder()
                                        .setTitle(activity.getString(R.string.app_unlock_prompt_title))
                                        .setSubtitle(activity.getString(R.string.app_unlock_prompt_subtitle))
                                        .setAllowedAuthenticators(authenticators)
                                        .build()

                                    val biometricPrompt = BiometricPrompt(
                                        activity,
                                        ContextCompat.getMainExecutor(activity),
                                        object : BiometricPrompt.AuthenticationCallback() {
                                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                                super.onAuthenticationSucceeded(result)
                                                viewModel.onRequireAppUnlockChanged(true)
                                            }
                                        }
                                    )
                                    biometricPrompt.authenticate(promptInfo)
                                } else {
                                    viewModel.onRequireAppUnlockChanged(true)
                                }
                            } else {
                                viewModel.onRequireAppUnlockChanged(true)
                            }
                        } else {
                            viewModel.onRequireAppUnlockChanged(false)
                        }
                    }
                )
                PerAppModeRow(
                    enabled = backupMode == BackupMode.PER_APP,
                    onCheckedChange = { enabled ->
                        viewModel.onBackupModeChanged(
                            if (enabled) BackupMode.PER_APP else BackupMode.SINGLE
                        )
                    }
                )
            }
        }
    }
}
