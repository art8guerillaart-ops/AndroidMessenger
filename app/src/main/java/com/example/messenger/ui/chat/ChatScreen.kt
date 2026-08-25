package com.example.messenger.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.messenger.data.api.RetrofitClient
import com.example.messenger.data.model.MessageDto
import com.example.messenger.data.model.ParticipantDto
import com.example.messenger.data.model.displayName
import com.example.messenger.data.model.senderDisplayName
import com.example.messenger.ui.theme.*
import java.io.File
import java.io.FileOutputStream

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onLeftGroup: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showParticipants by remember { mutableStateOf(false) }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    LaunchedEffect(state.leftGroup) {
        if (state.leftGroup) onLeftGroup()
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val (file, name, mime) = copyUriToCache(context, uri)
            if (file != null) viewModel.uploadAndSend(file, mime, name)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (state.onlineCount > 0) "в сети: ${state.onlineCount}" else "офлайн",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleMute) {
                        Icon(
                            if (state.isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                            contentDescription = if (state.isMuted) "Включить уведомления" else "Отключить уведомления",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (state.chatType == "group") {
                        IconButton(onClick = {
                            showParticipants = true
                            viewModel.loadParticipants()
                        }) {
                            Icon(
                                Icons.Default.Group,
                                contentDescription = "Участники",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            ChatInputBar(
                value = state.draft,
                uploading = state.uploading,
                onValueChange = viewModel::onDraftChange,
                onSend = viewModel::send,
                onAttach = { filePicker.launch("*/*") }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurface)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        MessageBubble(msg = msg, isOwn = msg.sender == state.myEmail)
                    }
                }
            }

            if (state.connectionError != null) {
                Text(
                    state.connectionError!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(6.dp)
                )
            }
        }
    }

    if (showParticipants) {
        ParticipantsDialog(
            myEmail = state.myEmail,
            participants = state.participants,
            iAmAdmin = state.iAmAdmin,
            error = state.participantError,
            onAdd = viewModel::addParticipant,
            onRemove = viewModel::removeParticipant,
            onDismiss = { showParticipants = false }
        )
    }
}

@Composable
private fun MessageBubble(msg: MessageDto, isOwn: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .background(
                    color = if (isOwn) InkBlack else PanelBg,
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!isOwn) {
                Text(
                    msg.senderDisplayName(),
                    fontSize = 9.sp,
                    color = MutedText,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            val ext = msg.fileUrl?.substringAfterLast('.', "")?.lowercase()
            if (msg.fileUrl != null && ext in IMAGE_EXTENSIONS) {
                AsyncImage(
                    model = RetrofitClient.baseUrl.trimEnd('/') + msg.fileUrl,
                    contentDescription = msg.text,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else if (msg.fileUrl != null) {
                Text(
                    text = "\uD83D\uDCCE ${msg.text.orEmpty()}",
                    fontSize = 13.sp,
                    color = if (isOwn) White else InkBlack
                )
            } else {
                Text(
                    text = msg.text.orEmpty(),
                    fontSize = 14.sp,
                    color = if (isOwn) White else InkBlack
                )
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    uploading: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onAttach, enabled = !uploading) {
            if (uploading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Прикрепить файл",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("Написать сообщение...", fontSize = 13.sp) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            singleLine = true
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onSend) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ParticipantsDialog(
    myEmail: String,
    participants: List<ParticipantDto>,
    iAmAdmin: Boolean,
    error: String?,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newEmail by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Участники группы", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                participants.forEach { p ->
                    val canRemove = iAmAdmin || p.email == myEmail
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            p.displayName() + if (p.isAdmin) " (админ)" else "",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (canRemove) {
                            TextButton(onClick = { onRemove(p.email) }) {
                                Text(
                                    if (p.email == myEmail) "Покинуть" else "Удалить",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                if (iAmAdmin) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newEmail,
                            onValueChange = { newEmail = it },
                            placeholder = { Text("email нового участника", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        TextButton(onClick = {
                            if (newEmail.isNotBlank()) {
                                onAdd(newEmail.trim())
                                newEmail = ""
                            }
                        }) { Text("+", color = MaterialTheme.colorScheme.onSurface) }
                    }
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = MaterialTheme.colorScheme.onSurface) }
        }
    )
}

/** Копирует выбранный content:// Uri во временный файл в кеше приложения — нужен реальный File для multipart. */
private fun copyUriToCache(context: Context, uri: Uri): Triple<File?, String, String?> {
    val resolver = context.contentResolver
    var displayName = "file"
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) displayName = cursor.getString(nameIndex)
    }
    val mime = resolver.getType(uri)
    return try {
        val outFile = File(context.cacheDir, "${System.currentTimeMillis()}_$displayName")
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        Triple(outFile, displayName, mime)
    } catch (e: Exception) {
        Triple(null, displayName, mime)
    }
}
