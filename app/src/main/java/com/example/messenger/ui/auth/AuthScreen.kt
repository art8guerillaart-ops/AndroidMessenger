package com.example.messenger.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.messenger.ui.theme.*

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
            .background(BgPage),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth(0.85f),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(4.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (state.step == AuthStep.EMAIL) "WELCOME" else "VERIFICATION",
                    style = MaterialTheme.typography.headlineSmall,
                    color = InkBlack
                )

                Spacer(modifier = Modifier.height(28.dp))

                if (state.step == AuthStep.EMAIL) {
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = viewModel::onEmailChange,
                        placeholder = { Text("name@mail.com", fontSize = 13.sp) },
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
                        colors = ButtonDefaults.buttonColors(containerColor = InkBlack, contentColor = White),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().height(38.dp)
                    ) {
                        Text(
                            text = "GET THE CODE",
                            style = MaterialTheme.typography.labelSmall,
                            color = White
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
                        colors = ButtonDefaults.buttonColors(containerColor = InkBlack, contentColor = White),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().height(38.dp)
                    ) {
                        Text(text = "GO", style = MaterialTheme.typography.labelSmall, color = White)
                    }
                }

                if (state.error != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = state.error!!, color = ErrorRed, fontSize = 11.sp)
                }
                if (state.statusHint != null && state.error == null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = state.statusHint!!, color = MutedText, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = White,
    unfocusedContainerColor = White,
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    focusedTextColor = InkBlack,
    unfocusedTextColor = InkBlack
)
