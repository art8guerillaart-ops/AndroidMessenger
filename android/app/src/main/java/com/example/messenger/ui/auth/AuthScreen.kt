package com.example.messenger.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.messenger.R

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onLoggedIn: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    if (state.loggedIn) {
        onLoggedIn()
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth(0.85f)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_horse_logo),
                contentDescription = null,
                modifier = Modifier.size(120.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (state.step == AuthStep.EMAIL) "HORSE" else "VERIFICATION",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(28.dp))

            if (state.step == AuthStep.EMAIL) {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::onEmailChange,
                    placeholder = { Text("name@mail.com", fontSize = 13.sp) },
                    leadingIcon = {
                        Text(
                            text = "@",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = fieldColors(),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = viewModel::sendCode,
                    enabled = !state.loading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().height(38.dp)
                ) {
                    Text(
                        text = "GET THE CODE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            } else {
                OutlinedTextField(
                    value = state.code,
                    onValueChange = viewModel::onCodeChange,
                    placeholder = { Text("0000", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = fieldColors(),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = viewModel::verifyCode,
                    enabled = !state.loading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().height(38.dp)
                ) {
                    Text(text = "GO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    onClick = viewModel::sendCode,
                    enabled = !state.loading,
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = 2.dp
                ) {
                    Text(
                        text = "ОТПРАВИТЬ КОД ПОВТОРНО",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (state.loading) 0.4f else 1f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (state.error != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = state.error!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
            if (state.statusHint != null && state.error == null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = state.statusHint!!, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }

        if (state.step == AuthStep.CODE) {
            IconButton(
                onClick = viewModel::goBackToEmail,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
)
