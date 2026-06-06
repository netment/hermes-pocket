package com.netment.hermespocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netment.hermespocket.data.MessageRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onJumpToSession: (Long) -> Unit,
    searchFn: suspend (String) -> List<MessageRepository.SearchResultItem>,
    sessionScoped: Boolean = false  // true=搜当前会话, false=全局
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MessageRepository.SearchResultItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { q ->
                            query = q
                            if (q.length >= 1) {
                                scope.launch {
                                    isSearching = true
                                    results = searchFn(q)
                                    isSearching = false
                                }
                            } else {
                                results = emptyList()
                            }
                        },
                        placeholder = { Text(if (sessionScoped) "搜索当前会话..." else "搜索所有会话...", color = Color(0xFF64748B)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF1E293B),
                            unfocusedContainerColor = Color(0xFF1E293B),
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Color(0xFF93C5FD))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A2E), titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0F0F1A)
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                query.isBlank() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Search, "搜索", tint = Color(0xFF334155), modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(if (sessionScoped) "输入关键词搜索当前会话" else "输入关键词搜索所有会话", color = Color(0xFF64748B), fontSize = 14.sp)
                        }
                    }
                }
                isSearching -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF2563EB), modifier = Modifier.size(32.dp))
                    }
                }
                results.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有找到 \"$query\"", color = Color(0xFF64748B), fontSize = 14.sp)
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(results) { result ->
                            SearchResultCard(
                                result = result,
                                query = query,
                                onClick = { onJumpToSession(result.sessionId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: MessageRepository.SearchResultItem,
    query: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E293B),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    result.sessionName,
                    color = Color(0xFF93C5FD),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    formatTimestamp(result.messageItem.let {
                        when (it) {
                            is MessageItem.ChatMsg -> it.msg.timestamp
                            is MessageItem.ApprovalItem -> it.timestamp
                            is MessageItem.ClarifyItem -> it.timestamp
                            is MessageItem.FileItem -> it.timestamp
                            is MessageItem.StepItem -> it.timestamp
                            is MessageItem.SuggestionItem -> it.timestamp
                            is MessageItem.ErrorItem -> it.timestamp
                            is MessageItem.ThinkingItem -> it.timestamp
                        }
                    }),
                    color = Color(0xFF64748B),
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                result.matchText,
                color = Color(0xFFE2E8F0),
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatTimestamp(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        else -> "${diff / 86_400_000}天前"
    }
}
