package com.example.messenger.ui.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.messenger.data.model.ChatDto

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onOpenChat: (chatId: String, title: String, chatType: String, peerEmail: String?) -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var showDmDialog by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var chatPendingDeletion by remember { mutableStateOf<ChatDto?>(null) }

    // Экран пересоздаётся при каждом возврате сюда (popBackStack из чата/настроек),
    // а ViewModel и её кэш списка — нет, поэтому обновляем список на каждый вход:
    // иначе после удаления чата через ChatScreen тут остался бы "призрак".
    LaunchedEffect(Unit) {
        viewModel.loadChats()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                TopAppBar(
                    title = {
                        Text(
                            "ЧАТЫ",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    actions = {
                        IconButton(onClick = { showDmDialog = true }) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = "Личное сообщение",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { showGroupDialog = true }) {
                            Icon(
                                Icons.Default.Group,
                                contentDescription = "Новая группа",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Настройки",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = { Text("SEARCH", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
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
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(chat.title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            when (chat.chatType) {
                "dm" -> "личный чат"
                "group" -> "группа"
                else -> "публичный чат"
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
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
