package com.cedervs.worlddiscovery.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cedervs.worlddiscovery.core.auth.AuthRepository
import com.cedervs.worlddiscovery.core.auth.SessionState
import com.cedervs.worlddiscovery.core.location.BackgroundTrackingConsent
import com.cedervs.worlddiscovery.core.location.LocationPermissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RESEND_COOLDOWN_SECONDS = 60

private sealed interface EmailStep {
    data object Hidden : EmailStep
    data object EnterEmail : EmailStep
    data class EnterCode(val email: String) : EmailStep
}

@Composable
fun ProfileScreen(authRepository: AuthRepository, backgroundTrackingConsent: BackgroundTrackingConsent) {
    val sessionState by authRepository.sessionState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }
    var showGoogleSignInError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        authRepository.initialize()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val state = sessionState) {
                SessionState.Unknown -> CircularProgressIndicator()

                SessionState.SignedOut -> {
                    Text(
                        text = stringResource(R.string.profile_signed_out_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        enabled = !isBusy,
                        onClick = {
                            isBusy = true
                            showGoogleSignInError = false
                            coroutineScope.launch {
                                val result = authRepository.signInWithGoogle(context)
                                isBusy = false
                                showGoogleSignInError = result.isFailure
                            }
                        },
                    ) {
                        Text(stringResource(R.string.profile_sign_in_with_google))
                    }
                    if (showGoogleSignInError) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.profile_sign_in_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    EmailSignInFlow(authRepository = authRepository, isParentBusy = isBusy, onBusyChange = { isBusy = it })
                }

                is SessionState.SignedIn -> {
                    Text(
                        text = stringResource(R.string.profile_signed_in_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    state.displayEmail?.let { email ->
                        Spacer(Modifier.height(8.dp))
                        Text(text = email, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        enabled = !isBusy,
                        onClick = {
                            isBusy = true
                            coroutineScope.launch {
                                authRepository.logout()
                                isBusy = false
                            }
                        },
                    ) {
                        Text(stringResource(R.string.profile_sign_out))
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            BackgroundTrackingSection(backgroundTrackingConsent)
        }
    }
}

@Composable
private fun BackgroundTrackingSection(consent: BackgroundTrackingConsent) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isEnabled by consent.isEnabled.collectAsState(initial = false)
    var permissionDenied by remember { mutableStateOf(false) }
    val hasForegroundPermission = LocationPermissions.hasAnyLocationPermission(context)

    val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            permissionDenied = false
            coroutineScope.launch { consent.setEnabled(true) }
        } else {
            permissionDenied = true
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.profile_background_tracking_title))
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = isEnabled,
                enabled = hasForegroundPermission,
                onCheckedChange = { checked ->
                    if (!checked) {
                        coroutineScope.launch { consent.setEnabled(false) }
                    } else if (LocationPermissions.hasBackgroundLocationPermission(context)) {
                        permissionDenied = false
                        coroutineScope.launch { consent.setEnabled(true) }
                    } else {
                        permissionLauncher.launch(LocationPermissions.BACKGROUND_LOCATION_PERMISSION)
                    }
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.profile_background_tracking_description),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!hasForegroundPermission) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.profile_background_tracking_requires_foreground),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (permissionDenied) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.profile_background_tracking_permission_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EmailSignInFlow(
    authRepository: AuthRepository,
    isParentBusy: Boolean,
    onBusyChange: (Boolean) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var step by remember { mutableStateOf<EmailStep>(EmailStep.Hidden) }
    var emailInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var errorMessageRes by remember { mutableStateOf<Int?>(null) }
    var resendAvailableAtMillis by remember { mutableLongStateOf(0L) }
    var secondsUntilResend by remember { mutableStateOf(0) }

    LaunchedEffect(resendAvailableAtMillis) {
        while (true) {
            val remainingMs = resendAvailableAtMillis - System.currentTimeMillis()
            secondsUntilResend = if (remainingMs > 0) (remainingMs / 1000).toInt() + 1 else 0
            if (secondsUntilResend <= 0) break
            delay(1000)
        }
    }

    fun sendCode(email: String) {
        onBusyChange(true)
        errorMessageRes = null
        coroutineScope.launch {
            val result = authRepository.requestEmailCode(email)
            onBusyChange(false)
            if (result.isSuccess) {
                step = EmailStep.EnterCode(email)
                codeInput = ""
                resendAvailableAtMillis = System.currentTimeMillis() + RESEND_COOLDOWN_SECONDS * 1000L
            } else {
                errorMessageRes = errorStringFor(result.exceptionOrNull()?.message)
            }
        }
    }

    when (val currentStep = step) {
        EmailStep.Hidden -> {
            TextButton(enabled = !isParentBusy, onClick = { step = EmailStep.EnterEmail; errorMessageRes = null }) {
                Text(stringResource(R.string.profile_continue_with_email))
            }
        }

        EmailStep.EnterEmail -> {
            OutlinedTextField(
                value = emailInput,
                onValueChange = { emailInput = it },
                label = { Text(stringResource(R.string.profile_email_input_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !isParentBusy && emailInput.isNotBlank(),
                onClick = { sendCode(emailInput.trim()) },
            ) {
                Text(stringResource(R.string.profile_send_code))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { step = EmailStep.Hidden; errorMessageRes = null }) {
                Text(stringResource(R.string.profile_back))
            }
        }

        is EmailStep.EnterCode -> {
            Text(
                text = stringResource(R.string.profile_email_sent_notice, currentStep.email),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = codeInput,
                onValueChange = { if (it.length <= 6) codeInput = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.profile_code_input_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !isParentBusy && codeInput.length == 6,
                onClick = {
                    onBusyChange(true)
                    errorMessageRes = null
                    coroutineScope.launch {
                        val result = authRepository.verifyEmailCode(currentStep.email, codeInput)
                        onBusyChange(false)
                        if (!result.isSuccess) {
                            errorMessageRes = errorStringFor(result.exceptionOrNull()?.message)
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.profile_verify_code))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                enabled = !isParentBusy && secondsUntilResend <= 0,
                onClick = { sendCode(currentStep.email) },
            ) {
                Text(
                    if (secondsUntilResend > 0) {
                        stringResource(R.string.profile_resend_code_countdown, secondsUntilResend)
                    } else {
                        stringResource(R.string.profile_resend_code)
                    },
                )
            }
            TextButton(onClick = { step = EmailStep.EnterEmail; errorMessageRes = null }) {
                Text(stringResource(R.string.profile_use_another_email))
            }
        }
    }

    errorMessageRes?.let { res ->
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(res), color = MaterialTheme.colorScheme.error)
    }
}

private fun errorStringFor(backendDetail: String?): Int = when (backendDetail) {
    "resend_cooldown" -> R.string.profile_email_error_resend_cooldown
    "too_many_requests" -> R.string.profile_email_error_too_many_requests
    "invalid_code" -> R.string.profile_email_error_invalid_code
    "too_many_attempts" -> R.string.profile_email_error_too_many_attempts
    else -> R.string.profile_sign_in_error
}
