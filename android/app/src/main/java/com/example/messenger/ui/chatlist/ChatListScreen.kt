package com.example.messenger.ui.chatlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.messenger.R
import com.example.messenger.data.model.ChatDto
import com.example.messenger.ui.theme.ButtonRed
import com.example.messenger.ui.theme.DisplayFontFamily
import com.example.messenger.ui.theme.InkBlack

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onOpenChat: (chatId: String, title: String, chatType: String, peerEmail: String?) -> Unit,
    onOpenSettings: () -> Unit,
    onSessionExpired: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var showDmDialog by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var chatPendingDeletion by remember { mutableStateOf<ChatDto?>(null) }
    var showQuickActions by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    // Компактная ширина поля поиска = фактическая ширина вордмарка "HORSE",
    // измеренная через onGloballyPositioned; до первого замера — разумный дефолт.
    var horseTextWidth by remember { mutableStateOf(110.dp) }
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current

    // Экран пересоздаётся при каждом возврате сюда (popBackStack из чата/настроек),
    // а ViewModel и её кэш списка — нет, поэтому обновляем список на каждый вход:
    // иначе после удаления чата через ChatScreen тут остался бы "призрак".
    LaunchedEffect(Unit) {
        viewModel.loadChats()
    }

    LaunchedEffect(state.sessionExpired) {
        if (state.sessionExpired) onSessionExpired()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Обычный tap по любому другому элементу в Compose сам по себе НЕ снимает
            // фокус с текстового поля — нужно явно перехватывать тапы по фону/пустой
            // области и сбрасывать фокус, иначе схлопывание поля поиска не сработает.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
    ) {
        Image(
            painter = painterResource(R.drawable.auth_bg_lowpoly),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column(
                    modifier = Modifier.statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "HORSE",
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                horseTextWidth = with(density) { coordinates.size.width.toDp() }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val dotInteractionSource = remember { MutableInteractionSource() }
                        val dotPressed by dotInteractionSource.collectIsPressedAsState()
                        val dotScale by animateFloatAsState(
                            targetValue = if (dotPressed) 0.85f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "dotScale"
                        )
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .scale(dotScale)
                                .clip(CircleShape)
                                .background(ButtonRed)
                                .clickable(
                                    interactionSource = dotInteractionSource,
                                    indication = null
                                ) {
                                    focusManager.clearFocus()
                                    showQuickActions = !showQuickActions
                                }
                        )
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        // Компактная ширина — под вордмарк "HORSE" (см. horseTextWidth выше);
                        // развёрнутая — вся доступная ширина строки (с уже применёнными
                        // боковыми отступами 16dp). Фокус или непустой запрос — разворачивают,
                        // потеря фокуса при пустом запросе — схлопывают обратно.
                        val compactWidth = horseTextWidth.coerceAtLeast(100.dp)
                        val targetWidth = if (searchExpanded) maxWidth else compactWidth
                        val animatedSearchWidth by animateDpAsState(
                            targetValue = targetWidth,
                            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                            label = "searchWidth"
                        )

                        // Figma-инспектор: 331x28 (базовый макет 436x800) — обычный
                        // OutlinedTextField не сжимается ниже ~56dp без обрезания текста
                        // (фиксированные внутренние contentPadding), поэтому здесь тот же
                        // приём, что и в AuthScreen.kt: BasicTextField + DecorationBox
                        // с компактными отступами.
                        val searchInteractionSource = remember { MutableInteractionSource() }
                        BasicTextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            modifier = Modifier
                                .width(animatedSearchWidth)
                                .height(28.dp)
                                .onFocusChanged { focusState ->
                                    searchExpanded = focusState.isFocused || state.query.isNotEmpty()
                                },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            interactionSource = searchInteractionSource
                        ) { innerTextField ->
                            OutlinedTextFieldDefaults.DecorationBox(
                                value = state.query,
                                innerTextField = innerTextField,
                                enabled = true,
                                singleLine = true,
                                visualTransformation = VisualTransformation.None,
                                interactionSource = searchInteractionSource,
                                placeholder = {
                                    if (searchExpanded) {
                                        Text("SEARCH", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        // Узкий компактный вид — плейсхолдер-текст не влезает,
                                        // вместо него центрированная иконка лупы.
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.Search,
                                                contentDescription = "Поиск",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                ),
                                contentPadding = OutlinedTextFieldDefaults.contentPadding(top = 0.dp, bottom = 0.dp),
                                container = {
                                    OutlinedTextFieldDefaults.ContainerBox(
                                        enabled = true,
                                        isError = false,
                                        interactionSource = searchInteractionSource,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                            focusedBorderColor = Color.Transparent,
                                            unfocusedBorderColor = Color.Transparent
                                        ),
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when {
                    state.loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    state.error != null -> Text(
                        state.error!!,
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.error
                    )
                    state.filteredChats.isEmpty() -> Text(
                        "Ничего не найдено",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> LazyColumn {
                        items(state.filteredChats, key = { it.chatId }) { chat ->
                            ChatRow(
                                chat = chat,
                                onClick = { onOpenChat(chat.chatId, chat.title, chat.chatType, chat.peerEmail) },
                                onLongClick = { if (chat.chatType == "dm") chatPendingDeletion = chat }
                            )
                        }
                    }
                }
            }
        }

        QuickActionsPanel(
            visible = showQuickActions,
            onDismiss = { showQuickActions = false }
        )
    }

    if (showDmDialog) {
        SimpleInputDialog(
            title = "Личное сообщение",
            placeholder = "email собеседника",
            error = state.dmError,
            confirmLabel = "Написать",
            onDismiss = { showDmDialog = false },
            onConfirm = { email ->
                viewModel.startDm(email) { chatId, title ->
                    showDmDialog = false
                    // На этом этапе title = именно email собеседника (см. POST /api/dm) —
                    // безопасно использовать его же как peerEmail для Signal-сессии.
                    onOpenChat(chatId, title, "dm", title)
                }
            }
        )
    }

    if (showGroupDialog) {
        SimpleInputDialog(
            title = "Новая группа",
            placeholder = "название группы",
            error = state.groupError,
            confirmLabel = "Создать",
            onDismiss = { showGroupDialog = false },
            onConfirm = { title ->
                viewModel.createGroup(title) { chatId, createdTitle ->
                    showGroupDialog = false
                    onOpenChat(chatId, createdTitle, "group", null)
                }
            }
        )
    }

    val chatToDelete = chatPendingDeletion
    if (chatToDelete != null) {
        DeleteDmChatDialog(
            onDismiss = { chatPendingDeletion = null },
            onConfirm = {
                viewModel.deleteChat(chatToDelete.chatId)
                chatPendingDeletion = null
            }
        )
    }
}

// Пункты меню (иконки/действия) добавятся отдельным запросом — сейчас это
// просто пустая карточка-подложка, чтобы зафиксировать позицию и анимацию появления.
@Composable
fun QuickActionsPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(animationSpec = tween(durationMillis = 280)) { fullHeight -> -fullHeight } +
                fadeIn(animationSpec = tween(durationMillis = 280)),
            exit = slideOutVertically(animationSpec = tween(durationMillis = 220)) { fullHeight -> -fullHeight } +
                fadeOut(animationSpec = tween(durationMillis = 220)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 56.dp, end = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(240.dp)
                    .height(360.dp)
                    .shadow(elevation = 12.dp, shape = RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFFFFFFFF), Color(0xFFF0F0F0)),
                            start = Offset.Zero,
                            end = Offset.Infinite
                        )
                    )
            )
        }
    }
}

@Composable
private fun DeleteDmChatDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить переписку?", style = MaterialTheme.typography.titleMedium) },
        text = { Text("Чат будет удалён безвозвратно у вас и у собеседника.", fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(chat: ChatDto, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(chat.title, fontSize = 20.sp, color = InkBlack)
    }
}

@Composable
private fun SimpleInputDialog(
    title: String,
    placeholder: String,
    error: String?,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(placeholder) },
                    singleLine = true
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.onSurface)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
