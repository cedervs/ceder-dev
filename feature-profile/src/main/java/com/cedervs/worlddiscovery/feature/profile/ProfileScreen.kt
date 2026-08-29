package com.cedervs.worlddiscovery.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cedervs.worlddiscovery.core.auth.AuthRepository
import com.cedervs.worlddiscovery.core.auth.SessionState
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(authRepository: AuthRepository) {
    val sessionState by authRepository.sessionState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }
    var showSignInError by remember { mutableStateOf(false) }

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
                            showSignInError = false
                            coroutineScope.launch {
                                val result = authRepository.signInWithGoogle(context)
                                isBusy = false
                                showSignInError = result.isFailure
                            }
                        },
                    ) {
                        Text(stringResource(R.string.profile_sign_in_with_google))
                    }
                    if (showSignInError) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.profile_sign_in_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
        }
    }
}
