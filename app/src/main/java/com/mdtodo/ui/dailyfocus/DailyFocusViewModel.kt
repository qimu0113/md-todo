package com.mdtodo.ui.dailyfocus

import android.net.Uri
import androidx.core.net.toUri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdtodo.R
import com.mdtodo.data.FileRepository
import com.mdtodo.data.focus.FocusPinStore
import com.mdtodo.data.model.Todo
import com.mdtodo.data.model.priorityRank
import com.mdtodo.data.settings.DailyFocusMode
import com.mdtodo.utils.MarkdownParser
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class DailyFocusTask(
    val todo: Todo,
    val categoryUri: Uri,
    val categoryName: String,
)

data class DailyFocusUiState(
    val isLoading: Boolean = true,
    /** 聚焦列表：命中当前模式，或被手动 pin。pin 的排在最前面。 */
    val tasks: List<DailyFocusTask> = emptyList(),
    /** 全部未完成任务（不受模式与 pin 影响）——手动添加聚焦时的候选池。 */
    val allOpenTasks: List<DailyFocusTask> = emptyList(),
    /** 当前被手动 pin 的 todo id。UI 用它画选中态。 */
    val pinnedIds: Set<String> = emptySet(),

    internal val lineCache: Map<String, List<String>> = emptyMap(),
    internal val categoryNames: Map<String, String> = emptyMap(),
)

class DailyFocusViewModel(
    private val fileRepository: FileRepository,
    private val focusPinStore: FocusPinStore,
    val mode: DailyFocusMode,
    val enableTasksPluginSupport: Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyFocusUiState(isLoading = true))
    val uiState: StateFlow<DailyFocusUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    private val inFlightWrites = AtomicInteger(0)
    private val pendingRefreshFromObserver = AtomicBoolean(false)

    private var pinnedIds: Set<String> = emptySet()

    init {
        observeExternalChanges()
        observePins()
        refreshFromDisk(showLoading = true)
    }

    /**
     * 手动 pin 变化时只重筛内存里的行，不重读文件——
     * pin 是纯本地视图状态，和磁盘内容无关，没必要触发一次全量 IO。
     */
    private fun observePins() {
        viewModelScope.launch {
            focusPinStore.pinnedIds.collect { ids ->
                if (ids == pinnedIds) return@collect
                pinnedIds = ids
                val current = _uiState.value
                if (current.lineCache.isEmpty()) {
                    _uiState.value = current.copy(pinnedIds = ids)
                } else {
                    _uiState.value = current.copy(
                        pinnedIds = ids,
                        tasks = sortTasks(
                            collectTasks(current.lineCache, current.categoryNames, onlyFocus = true),
                            ids,
                        ),
                        allOpenTasks = sortTasks(
                            collectTasks(current.lineCache, current.categoryNames, onlyFocus = false),
                            ids,
                        ),
                    )
                }
            }
        }
    }

    fun togglePin(task: DailyFocusTask) {
        focusPinStore.toggle(task.todo.id)
    }

    private fun observeExternalChanges() {
        val folderUri = fileRepository.getFolderUri() ?: return
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            fileRepository.observeMarkdownFilesChanges(folderUri)
                .debounce(350)
                .catch { /* best-effort */ }
                .collectLatest {
                    if (inFlightWrites.get() > 0) {
                        pendingRefreshFromObserver.set(true)
                        return@collectLatest
                    }
                    refreshFromDisk(showLoading = false)
                }
        }
    }

    fun refreshFromDisk(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }

            val categoriesResult = fileRepository.getCategories()
            if (categoriesResult.isFailure) {
                _uiState.value = DailyFocusUiState(isLoading = false)
                return@launch
            }

            val categories = categoriesResult.getOrThrow()
            val lineCache = mutableMapOf<String, List<String>>()
            val categoryNames = mutableMapOf<String, String>()

            for (category in categories) {
                val linesResult = fileRepository.readLines(category.uri)
                if (linesResult.isFailure) continue
                val uriKey = category.uri.toString()
                lineCache[uriKey] = linesResult.getOrThrow()
                categoryNames[uriKey] = category.displayName
            }

            _uiState.value = DailyFocusUiState(
                isLoading = false,
                tasks = sortTasks(collectTasks(lineCache, categoryNames, onlyFocus = true), pinnedIds),
                allOpenTasks = sortTasks(collectTasks(lineCache, categoryNames, onlyFocus = false), pinnedIds),
                pinnedIds = pinnedIds,
                lineCache = lineCache,
                categoryNames = categoryNames,
            )
        }
    }

    /**
     * 从行缓存里挑任务。
     *
     * @param onlyFocus true = 只挑「命中当前模式 或 被手动 pin」的；
     *                  false = 全部未完成，给手动添加聚焦的候选列表用。
     */
    private fun collectTasks(
        lineCache: Map<String, List<String>>,
        categoryNames: Map<String, String>,
        onlyFocus: Boolean,
    ): List<DailyFocusTask> {
        val today = LocalDate.now().toString()
        val out = mutableListOf<DailyFocusTask>()
        for ((uriString, lines) in lineCache) {
            val name = categoryNames[uriString] ?: continue
            val uri = uriString.toUri()
            for (todo in MarkdownParser.parse(lines)) {
                if (todo.isDone) continue
                if (onlyFocus) {
                    val pinned = todo.id in pinnedIds
                    // 手动 pin 优先于模式：哪怕今天是 OVERDUE 模式、
                    // 这条任务明天才到期，只要用户点了「加入聚焦」就该出现。
                    if (!pinned && !matchesFocusMode(todo, mode, today)) continue
                }
                out.add(
                    DailyFocusTask(
                        todo = todo,
                        categoryUri = uri,
                        categoryName = name,
                    ),
                )
            }
        }
        return out
    }

    fun toggleTodo(task: DailyFocusTask) {
        viewModelScope.launch {
            val previousLines = currentLinesFor(task)
            val newLines = MarkdownParser.tryToggleLine(
                lines = previousLines,
                lineIndex = task.todo.lineIndex,
                enableTasksPlugin = enableTasksPluginSupport,
            )
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(task.categoryUri, newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(task.categoryUri, newLines)
                if (write.isFailure) {
                    if (currentLinesFor(task) == newLines) {
                        applyLines(task.categoryUri, previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun editTodo(
        task: DailyFocusTask,
        newText: String,
        meta: MarkdownParser.TasksMeta?,
    ) {
        viewModelScope.launch {
            val previousLines = currentLinesFor(task)
            val newLines = if (enableTasksPluginSupport) {
                MarkdownParser.editTodo(
                    lines = previousLines,
                    lineIndex = task.todo.lineIndex,
                    newText = newText,
                    meta = meta,
                    enableTasksPlugin = true,
                )
            } else {
                val updated = MarkdownParser.tryEditTodoText(previousLines, task.todo.lineIndex, newText)
                if (updated == null) {
                    _events.emit(Event.ShowMessage(R.string.error_read_failed))
                    refreshFromDisk(showLoading = false)
                    return@launch
                }
                updated
            }

            applyLines(task.categoryUri, newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(task.categoryUri, newLines)
                if (write.isFailure) {
                    if (currentLinesFor(task) == newLines) {
                        applyLines(task.categoryUri, previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun deleteTodo(task: DailyFocusTask) {
        viewModelScope.launch {
            val previousLines = currentLinesFor(task)
            val newLines = MarkdownParser.tryDeleteTodoWithSubtasks(previousLines, task.todo.lineIndex)
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(task.categoryUri, newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(task.categoryUri, newLines)
                if (write.isFailure) {
                    if (currentLinesFor(task) == newLines) {
                        applyLines(task.categoryUri, previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    sealed interface Event {
        data class ShowMessage(@param:StringRes val messageResId: Int) : Event
    }

    private fun currentLinesFor(task: DailyFocusTask): List<String> =
        _uiState.value.lineCache[task.categoryUri.toString()] ?: emptyList()

    // Replicates the logic from refreshFromDisk but only for a single file, used for optimistic updates after mutations.
    private fun applyLines(categoryUri: Uri, newLines: List<String>) {
        val current = _uiState.value
        val uriKey = categoryUri.toString()

        // Rebuild the cache with the updated file's lines.
        val newCache = current.lineCache.toMutableMap()
        newCache[uriKey] = newLines

        _uiState.value = current.copy(
            tasks = sortTasks(collectTasks(newCache, current.categoryNames, onlyFocus = true), pinnedIds),
            allOpenTasks = sortTasks(collectTasks(newCache, current.categoryNames, onlyFocus = false), pinnedIds),
            lineCache = newCache,
        )
    }

    private fun onWriteFinishedMaybeRefresh() {
        val remaining = inFlightWrites.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0 && pendingRefreshFromObserver.getAndSet(false)) {
            refreshFromDisk(showLoading = false)
        }
    }
}

private fun matchesFocusMode(todo: Todo, mode: DailyFocusMode, today: String): Boolean {
    return when (mode) {
        DailyFocusMode.TODAY -> todo.dueDate == today
        DailyFocusMode.OVERDUE -> todo.dueDate?.let { it < today } == true
        DailyFocusMode.TODAY_AND_OVERDUE -> {
            val due = todo.dueDate ?: return false
            due <= today
        }
    }
}

// Sort: 手动 pin 的最前 -> due date earliest -> highest priority -> category name -> line index.
private fun sortTasks(
    tasks: List<DailyFocusTask>,
    pinnedIds: Set<String>,
): List<DailyFocusTask> {
    return tasks.sortedWith(
        compareBy<DailyFocusTask> { if (it.todo.id in pinnedIds) 0 else 1 }
            .thenBy { it.todo.dueDate == null }
            .thenBy { it.todo.dueDate ?: "" }
            .thenByDescending { it.todo.priority.priorityRank() }
            .thenBy { it.categoryName.lowercase() }
            .thenBy { it.todo.lineIndex },
    )
}
