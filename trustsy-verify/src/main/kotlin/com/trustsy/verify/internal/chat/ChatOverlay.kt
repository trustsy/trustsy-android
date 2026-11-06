package com.trustsy.verify.internal.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trustsy.verify.R
import com.trustsy.verify.internal.net.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI

/** Without a live socket the thread is re-read this often (contract: chat). */
private const val POLL_MS = 20_000L

/**
 * The session chat, drawn over whatever step is showing. Invisible until the
 * reviewing team has written; then a button with the unseen count, opening a
 * sheet with the thread and a reply box. Never in the way of verifying: a chat
 * that cannot load simply does not appear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxScope.ChatOverlay(client: ChatClient, origin: String) {
    val scope = rememberCoroutineScope()
    var thread by remember { mutableStateOf<ChatThread?>(null) }
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    suspend fun load(markRead: Boolean) {
        runCatching { client.thread(markRead) }.onSuccess { thread = it }
    }

    // The doorbell: a `chat-message` on the case's own channel re-reads the thread.
    DisposableEffect(client) {
        var socket: PusherSocket? = null
        val job = scope.launch {
            val descriptor = runCatching { client.realtime() }.getOrNull() ?: return@launch
            val url = PusherFrames.url(descriptor, URI(origin).host ?: return@launch) ?: return@launch
            socket = PusherSocket(
                scope = scope,
                url = url,
                channel = descriptor.channel,
                authorize = { id -> client.authorize(id, descriptor.channel) },
                onChat = { scope.launch { load(open) } },
            ).also { it.open() }
        }
        onDispose {
            job.cancel()
            socket?.close()
        }
    }
    LaunchedEffect(client, open) {
        load(open)
        while (true) {
            delay(POLL_MS)
            load(open)
        }
    }

    val current = thread ?: return
    if (current.messages.isEmpty()) return
    val unseen = current.unseen
    val label = if (unseen > 0) stringResource(R.string.trustsy_chat_open_unread, unseen) else stringResource(R.string.trustsy_chat_open)

    FloatingActionButton(
        onClick = { open = true },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(16.dp)
            .semantics { contentDescription = label },
    ) {
        BadgedBox(badge = { if (unseen > 0) Badge { Text(unseen.toString()) } }) {
            Text("💬")
        }
    }

    if (!open) return
    ModalBottomSheet(onDismissRequest = { open = false }) {
        val list = rememberLazyListState()
        LaunchedEffect(current.messages.size) {
            if (current.messages.isNotEmpty()) list.animateScrollToItem(current.messages.size - 1)
        }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.trustsy_chat_title), style = MaterialTheme.typography.titleLarge)
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(current.messages, key = { it.id }) { message -> Bubble(message) }
            }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (current.canReply) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.length <= 2000) draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.trustsy_chat_placeholder)) },
                        maxLines = 4,
                    )
                    Button(
                        enabled = !busy && draft.isNotBlank(),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                runCatching { client.send(draft.trim()) }
                                    .onSuccess {
                                        thread = it
                                        draft = ""
                                    }
                                    .onFailure {
                                        error = if ((it as? ApiException)?.code == ChatClient.RATE_LIMITED) {
                                            R.string.trustsy_chat_slow_down
                                        } else {
                                            R.string.trustsy_chat_failed
                                        }
                                    }
                                busy = false
                            }
                        },
                    ) { Text(stringResource(R.string.trustsy_chat_send)) }
                }
            }
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage) {
    val mine = !message.fromTeam
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Text(
            stringResource(if (mine) R.string.trustsy_chat_you else R.string.trustsy_chat_team),
            style = MaterialTheme.typography.labelSmall,
        )
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                message.body,
                color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (mine && message.readAt != null) {
            Text(stringResource(R.string.trustsy_chat_seen), style = MaterialTheme.typography.labelSmall)
        }
    }
}
