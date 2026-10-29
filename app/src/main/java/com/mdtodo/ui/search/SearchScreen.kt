package com.mdtodo.ui.search

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mdtodo.R
import com.mdtodo.data.FileRepository
import com.mdtodo.data.model.TodoSearchHit
import com.mdtodo.utils.parseTodoLayout
import com.mdtodo.utils.rememberMarkdownText
import kotlinx.coroutines.delay

/**
 * 全库搜索。
 *
 * 匹配口径由搜索词自己决定（用户要求「可以搜索标签，时间，关键字」）：
 * - 打 `#学习` → 搜标签
 * - 打 `10-24` / `10/24` → 搜那一天的起记日期
 * - 打其他任何文字 → 全文子串匹配
 *
 * 不额外加三个筛选器是有意的：搜索框里打什么，意图本来就很清楚，
 * 多一层筛选等于每次搜索多点两下。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    fileRepository: FileRepository,
    onBack: () -> Unit,
    onOpenCategory: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TodoSearchHit>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // 进来就聚焦，省一次点击
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            hasSearched = false
            return@LaunchedEffect
        }
        // 防抖：每敲一个字就读一遍全库太浪费，等 250ms 没有新输入再查
        delay(250)
        fileRepository.searchTodos(query).onSuccess {
            results = it
            hasSearched = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                },
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(text = stringResource(R.string.search_hint)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        when {
            query.isBlank() -> {
                SearchHint(
                    text = stringResource(R.string.search_idle),
                    modifier = Modifier.padding(padding),
                )
            }

            hasSearched && results.isEmpty() -> {
                SearchHint(
                    text = stringResource(R.string.search_empty),
                    modifier = Modifier.padding(padding),
                )
            }

            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                ) {
                    items(items = results, key = { "${it.categoryUri}#${it.todo.lineIndex}" }) { hit ->
                        SearchResultRow(
                            hit = hit,
                            onClick = { onOpenCategory(hit.categoryUri) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchResultRow(hit: TodoSearchHit, onClick: () -> Unit) {
    val layout = remember(hit.todo.text) { parseTodoLayout(hit.todo.text) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = rememberMarkdownText(layout.title),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (layout.body.isNotBlank()) {
            Text(
                text = rememberMarkdownText(layout.body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        // 来源：哪个分类 + 哪一天记的。搜索结果的上下文比什么都重要，
        // 否则用户看到一条任务不知道它属于哪儿。
        Text(
            text = buildString {
                append(hit.categoryName)
                layout.date?.let { append(" · ").append(it) }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
