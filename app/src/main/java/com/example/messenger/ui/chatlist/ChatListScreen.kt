package com.example.messenger.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.messenger.data.model.ChatDto
import com.example.messenger.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onOpenChat: (chatId: String, title: String, chatType: String) -> Unit,
    onLoggedOut: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var showDmDialog by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = White,
        topBar = {
            Column(modifier = Modifier.background(SidebarBg)) {
                TopAppBar(
                    title = { Text("ЧАТЫ", style = MaterialTheme.typography.titleMedium, color = InkBlack) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SidebarBg),
                    actions = {
                        IconButton(onClick = { showDmDialog = true }) {
                            Icon(Icons.Default.Person, contentDescription = "Личное сообщение", tint = InkBlack)
                        }
                        IconButton(onClick = { showGroupDialog = true }) {
                            Icon(Icons.Default.Group, contentDescription = "Новая группа", tint = InkBlack)
                        }
                        IconButton(onClick = { showProfileDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Профиль", tint = InkBlack)
                        }
                    }
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = { Text("SEARCH", fontSize = 11.sp, color = MutedText) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = White,
                        unfocusedContainerColor = White,
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
                    color = InkBlack
                )
                state.error != null -> Text(
                    state.error!!,
                    modifier = Modifier.align(Alignment.Center),
                    color = ErrorRed
                )
                state.filteredChats.isEmpty() -> Text(
                    "Ничего не найдено",
                    modifier = Modifier.align(Alignment.Center),
                    color = MutedText
                )
                else -> LazyColumn {
                    items(state.filteredChats, key = { it.chatId }) { chat ->
                        ChatRow(chat = chat, onClick = { onOpenChat(chat.chatId, chat.title, chat.chatType) })
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
                    onOpenChat(chatId, title, "dm")
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
                    onOpenChat(chatId, createdTitle, "group")
                }
            }
        )
    }

    if (showProfileDialog) {
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text("Личный профиль", style = MaterialTheme.typography.titleMedium) },
            text = { Text(state.myEmail, fontWeight = FontWeight.Medium) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.logout()
                        showProfileDialog = false
                        onLoggedOut()
                    }
                }) { Text("Выйти из аккаунта", color = InkBlack) }
            },
            dismissButton = {
                TextButton(onClick = { showProfileDialog = false }) { Text("Закрыть") }
            }
        )
    }
}

@Composable
private fun ChatRow(chat: ChatDto, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(White)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(chat.title, fontSize = 14.sp, color = InkBlack)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            when (chat.chatType) {
                "dm" -> "личный чат"
                "group" -> "группа"
                else -> "публичный чат"
            },
            fontSize = 11.sp,
            color = MutedText
        )
    }
    HorizontalDivider(color = BorderSoft, thickness = 1.dp)
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
                    Text(error, color = ErrorRed, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) {
                Text(confirmLabel, color = InkBlack)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
