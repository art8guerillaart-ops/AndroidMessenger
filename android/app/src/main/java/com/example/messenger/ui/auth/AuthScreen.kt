package com.example.messenger.ui.auth

import android.graphics.BlurMaskFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.messenger.R
import com.example.messenger.ui.theme.ButtonRed
import com.example.messenger.ui.theme.DisplayFontFamily
import com.example.messenger.ui.theme.White

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
                // Код разбит на 4 отдельных ячейки по макету (node-id=169-4), но
                // источником правды по-прежнему остаётся state.code: String в
                // AuthViewModel — ячейки лишь синхронизируют с ним свои локальные
                // значения, отдельная модель под них не заводится.
                val digits = remember { List(4) { mutableStateOf("") } }
                val focusRequesters = remember { List(4) { FocusRequester() } }

                fun pushCode() {
                    viewModel.onCodeChange(digits.joinToString("") { it.value })
                }

                LaunchedEffect(Unit) {
                    focusRequesters.first().requestFocus()
                }

                // Тот же модификатор ширины передан и ряду ячеек, и кнопке "GO" —
                // гарантирует совпадение левого и правого края, как в макете.
                val codeRowWidthModifier = Modifier.fillMaxWidth()

                Row(
                    modifier = codeRowWidthModifier,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    digits.forEachIndexed { index, digitState ->
                        OtpDigitField(
                            value = digitState.value,
                            onValueChange = { newDigit ->
                                digitState.value = newDigit
                                pushCode()
                                if (newDigit.isNotEmpty() && index < digits.lastIndex) {
                                    focusRequesters[index + 1].requestFocus()
                                }
                            },
                            onBackspaceOnEmpty = {
                                if (index > 0) {
                                    focusRequesters[index - 1].requestFocus()
                                    digits[index - 1].value = ""
                                    pushCode()
                                }
                            },
                            focusRequester = focusRequesters[index],
                            modifier = Modifier.size(width = 42.dp, height = 46.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = viewModel::verifyCode,
                    enabled = !state.loading,
                    // #9C2525 из макета — именованный токен ButtonRed в Color.kt,
                    // а не хардкод внутри Composable.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ButtonRed,
                        contentColor = White
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = codeRowWidthModifier.height(38.dp)
                ) {
                    Text(text = "GO", style = MaterialTheme.typography.labelSmall, color = White)
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

// Одна ячейка кода на экране верификации (node-id=169-4): один символ,
// лёгкая drop shadow снаружи + inner shadow внутри ("утопленное" поле).
@Composable
private fun OtpDigitField(
    value: String,
    onValueChange: (String) -> Unit,
    onBackspaceOnEmpty: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(6.dp)

    BasicTextField(
        value = value,
        onValueChange = { raw -> onValueChange(raw.filter { it.isDigit() }.takeLast(1)) },
        modifier = modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && value.isEmpty()) {
                    onBackspaceOnEmpty()
                    true
                } else {
                    false
                }
            }
            .shadow(elevation = 2.dp, shape = shape)
            .innerShadow(shape = shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        interactionSource = interactionSource
    ) { innerTextField ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            innerTextField()
        }
    }
}

// Compose не даёт inner shadow "из коробки" (Modifier.shadow всегда снаружи) —
// стандартный приём: рисуем блик той же формы через saveLayer + SRC_OUT,
// смещённый и размытый, поверх уже отрисованного контента.
private fun Modifier.innerShadow(
    shape: Shape,
    color: Color = Color.Black.copy(alpha = 0.25f),
    blur: Dp = 3.dp,
    offsetX: Dp = 0.dp,
    offsetY: Dp = 1.dp,
    spread: Dp = 0.dp
): Modifier = drawWithContent {
    drawContent()
    drawIntoCanvas { canvas ->
        val shadowSize = Size(size.width + spread.toPx(), size.height + spread.toPx())
        val shadowOutline = shape.createOutline(shadowSize, layoutDirection, this)
        val paint = Paint().apply { this.color = color }

        canvas.saveLayer(Rect(Offset.Zero, size), paint)
        canvas.drawOutline(shadowOutline, paint)

        paint.asFrameworkPaint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OUT)
            if (blur.toPx() > 0f) {
                maskFilter = BlurMaskFilter(blur.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
        }

        canvas.translate(offsetX.toPx(), offsetY.toPx())
        canvas.drawOutline(shadowOutline, paint)
        canvas.restore()
    }
}
