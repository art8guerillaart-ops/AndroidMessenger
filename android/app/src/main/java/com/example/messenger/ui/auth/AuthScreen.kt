package com.example.messenger.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.messenger.R
import com.example.messenger.ui.theme.DisplayFontFamily

@OptIn(ExperimentalMaterial3Api::class)
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

    val horseTitleBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color(0xFF666666),
            0.8221f to Color(0xFF000000)
        ),
        start = Offset.Zero,
        end = Offset(Float.POSITIVE_INFINITY, 0f)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.auth_bg_lowpoly),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth(0.85f)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.horse_3d_logo),
                contentDescription = null,
                modifier = Modifier.size(130.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (state.step == AuthStep.EMAIL) {
                Text(
                    text = "HORSE",
                    style = TextStyle(
                        brush = horseTitleBrush,
                        fontFamily = DisplayFontFamily,
                        fontSize = 26.sp,
                        lineHeight = 37.sp
                    )
                )
            } else {
                Text(
                    text = "VERIFICATION",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Общий модификатор ширины и высоты для поля ввода и кнопки под ним —
            // по макету (node-id=167-3) оба элемента строго одного размера.
            val fieldWidthModifier = Modifier.fillMaxWidth().height(38.dp)

            if (state.step == AuthStep.EMAIL) {
                // Стандартный OutlinedTextField не сжимается ниже ~56dp без обрезания
                // текста (фиксированные внутренние отступы Material3), а по макету поле
                // должно быть строго высотой с кнопку (38dp) — поэтому здесь используется
                // низкоуровневый BasicTextField + DecorationBox с компактными contentPadding.
                val emailFieldInteractionSource = remember { MutableInteractionSource() }
                BasicTextField(
                    value = state.email,
                    onValueChange = viewModel::onEmailChange,
                    modifier = fieldWidthModifier,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    interactionSource = emailFieldInteractionSource
                ) { innerTextField ->
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = state.email,
                        innerTextField = innerTextField,
                        enabled = true,
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        interactionSource = emailFieldInteractionSource,
                        placeholder = { Text("name@mail.com", fontSize = 13.sp) },
                        leadingIcon = {
                            Text(
                                text = "@",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp
                            )
                        },
                        colors = fieldColors(),
                        contentPadding = OutlinedTextFieldDefaults.contentPadding(top = 0.dp, bottom = 0.dp),
                        container = {
                            OutlinedTextFieldDefaults.ContainerBox(
                                enabled = true,
                                isError = false,
                                interactionSource = emailFieldInteractionSource,
                                colors = fieldColors(),
                                shape = RoundedCornerShape(6.dp)
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = viewModel::sendCode,
                    enabled = !state.loading,
                    // Figma-макет (node-id=167-3) красит именно эту кнопку акцентным
                    // красным, а не основным цветом темы — переиспользуем уже
                    // объявленный error-токен (ErrorRed), а не заводим новый hex.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = fieldWidthModifier
                ) {
                    Text(
                        text = "GET THE CODE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError
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
