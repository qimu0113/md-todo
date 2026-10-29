package com.mdtodo.ui.category

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdtodo.R
import com.mdtodo.data.FileRepository
import com.mdtodo.data.model.Todo
import com.mdtodo.data.model.TodoNode
import com.mdtodo.data.parser.AutoCheck
import com.mdtodo.data.parser.MarkdownTreeParser
import com.mdtodo.utils.MarkdownParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class CategoryUiState(
    val isLoading: Boolean = false,
    val title: String = "",
    val activeTodos: List<Todo> = emptyList(),
    val completedTodos: List<Todo> = emptyList(),
    val lines: List<String> = emptyList(),
    val moveTargets: List<MoveTarget>? = null,
    val isLoadingMoveTargets: Boolean = false,
    // MD Todo：父子树。
    // 保留上面的扁平列表是因为移动/删除/编辑等逻辑都依赖 lineIndex，
    // 直接改成树会牵连一大片。树结构单独提供，仅供列表渲染使用。
    val activeRoots: List<TodoNode> = emptyList(),
    val completedRoots: List<TodoNode> = emptyList(),
    /**
     * 全量 todo（未按完成态分区），仅供 flattenTodoTree 查节点用。
     *
     * 为什么不直接用 activeTodos + completedTodos：
     * 「父 [x] + 子 [ ]」这种文件里，父在 completedRoots，子（未完成）在 activeTodos。
     * flatten 展开 completedRoots 时若只在 completedTodos 里找，子节点查不到 →
     * 被 `?: return` 丢弃 → **子任务从界面上完全消失**（文件里还在）。
     * 换成全量列表即可保证任何根节点下的子节点都能解析到。
     */
    val allTodos: List<Todo> = emptyList(),
    /**
     * 读取失败时非 null，值是错误说明的 string res id。
     *
     * T5：原实现读失败只发一条瞬时 Snackbar，界面落到「暂无待办」——
     * 飞行模式/权限失效时用户会以为任务全丢了。现在保留旧数据 + 持久错误态。
     */
    val loadError: Int? = null,
    // 记录刚刚由「自动打勾」触发勾选/取消的父任务行号，供 UI 做瞬时动画反馈
    val autoToggledLineIndices: Set<Int> = emptySet(),
)

data class MoveTarget(
    val title: String,
    val uri: Uri,
)

class CategoryViewModel(
    private val fileRepository: FileRepository,
    private val categoryUri: Uri,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryUiState(isLoading = true))
    val uiState: StateFlow<CategoryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    private val inFlightWrites = AtomicInteger(0)
    private val pendingRefreshFromObserver = AtomicBoolean(false)

    init {
        observeExternalChanges()
        refreshFromDisk(showLoading = true)
    }

    private fun observeExternalChanges() {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            fileRepository.observeDocumentChanges(categoryUri)
                .debounce(250)
                .catch {
                    // TODO: Pull to refresh.
                }
                .collectLatest {
                    if (inFlightWrites.get() > 0) {
                        pendingRefreshFromObserver.set(true)
                        return@collectLatest
                    }
                    refreshFromDisk(showLoading = false)
                }
        }
    }

    fun refreshFromDisk(showLoading: Boolean = false) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }

            val nameResult = fileRepository.getDisplayName(categoryUri)
            val title = nameResult.getOrNull()?.removeSuffix(".md").orEmpty()

            val linesResult = fileRepository.readLines(categoryUri)
            if (linesResult.isFailure) {
                // T5：区分「权限失效」与「文件读不到」，并保留旧数据不清空
                val permissionLost = !fileRepository.hasPersistedReadWritePermission(categoryUri)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    title = title,
                    loadError = if (permissionLost) {
                        R.string.error_permission_lost
                    } else {
                        R.string.error_folder_unavailable
                    },
                )
                return@launch
            }

            val lines = linesResult.getOrThrow()
            if (!showLoading && lines == _uiState.value.lines) return@launch

            val todos = MarkdownParser.parse(lines)
            val (completed, active) = todos.partition { it.isDone }

            // MD Todo：与 applyLines 保持同一套树产出逻辑，避免两条路径状态不一致
            val roots = MarkdownTreeParser.parseTree(lines)
            val (completedRoots, activeRoots) = roots
                .filter { it.parentId == null }
                .partition { it.isDone }

            _uiState.value = CategoryUiState(
                isLoading = false,
                title = title,
                activeTodos = active.sortedBy { it.lineIndex },
                completedTodos = completed.sortedBy { it.lineIndex },
                activeRoots = activeRoots.sortedBy { it.lineIndex },
                completedRoots = completedRoots.sortedBy { it.lineIndex },
                allTodos = todos.sortedBy { it.lineIndex },
                lines = lines,
                moveTargets = _uiState.value.moveTargets,
            )
        }
    }

    fun loadMoveTargets() {
        val state = _uiState.value
        if (state.moveTargets != null || state.isLoadingMoveTargets) return

        _uiState.value = state.copy(isLoadingMoveTargets = true)
        viewModelScope.launch {
            val targets = fileRepository.getCategories()
                .getOrElse { emptyList() }
                .filterNot { it.uri == categoryUri }
                .map { MoveTarget(title = it.displayName, uri = it.uri) }
                .sortedBy { it.title.lowercase() }

            _uiState.value = _uiState.value.copy(
                moveTargets = targets,
                isLoadingMoveTargets = false,
            )
        }
    }

    fun toggleTodo(todo: Todo, enableTasksPluginSupport: Boolean) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines

            // MD Todo：改用 AutoCheck.applyAutoCheckAfterToggle —— 它在勾选后
            // 自动跑一遍状态传播（子任务全完成 → 父任务自动勾上；反之取消）。
            // 原实现只用 MarkdownParser.tryToggleLine，父任务的勾永远不会联动。
            val newLines = AutoCheck.applyAutoCheckAfterToggle(
                lines = previousLines,
                lineIndex = todo.lineIndex,
                enableTasksPlugin = enableTasksPluginSupport,
            )
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            // 记录被自动联动的行号，供 UI 做瞬时动画反馈
            val autoToggled = if (newLines != previousLines) {
                previousLines.indices.filter { newLines[it] != previousLines[it] }
                    .filter { it != todo.lineIndex }.toSet()
            } else emptySet()

            applyLines(newLines, autoToggledLineIndices = autoToggled)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun addTodo(
        text: String,
        meta: MarkdownParser.TasksMeta?,
        enableTasksPluginSupport: Boolean,
        addAtStart: Boolean = false,
    ) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = MarkdownParser.addTodo(
                lines = previousLines,
                text = text,
                meta = meta,
                enableTasksPlugin = enableTasksPluginSupport,
                addAtStart = addAtStart,
            )
            if (newLines == previousLines) return@launch

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun addSubTodo(
        parent: Todo,
        text: String,
        meta: MarkdownParser.TasksMeta?,
        enableTasksPluginSupport: Boolean,
    ) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = MarkdownParser.addSubTodo(
                lines = previousLines,
                parentLineIndex = parent.lineIndex,
                text = text,
                meta = meta,
                enableTasksPlugin = enableTasksPluginSupport,
            )
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun editTodo(
        todo: Todo,
        newText: String,
        meta: MarkdownParser.TasksMeta?,
        enableTasksPluginSupport: Boolean,
    ) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = if (enableTasksPluginSupport) {
                MarkdownParser.editTodo(
                    lines = previousLines,
                    lineIndex = todo.lineIndex,
                    newText = newText,
                    meta = meta,
                    enableTasksPlugin = true,
                )
            } else {
                val updated = MarkdownParser.tryEditTodoText(previousLines, todo.lineIndex, newText)
                if (updated == null) {
                    _events.emit(Event.ShowMessage(R.string.error_read_failed))
                    refreshFromDisk(showLoading = false)
                    return@launch
                }
                updated
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun deleteTodo(todo: Todo) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = MarkdownParser.tryDeleteTodoWithSubtasks(previousLines, todo.lineIndex)
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun moveTodo(todo: Todo, targetUri: Uri) {
        viewModelScope.launch {
            if (todo.indentLevel > 0) {
                _events.emit(Event.ShowMessage(R.string.category_move_subtask_disabled))
                return@launch
            }
            val previousLines = _uiState.value.lines
            val newLines = MarkdownParser.tryDeleteTodoWithSubtasks(previousLines, todo.lineIndex)
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val result = fileRepository.moveTodoLine(categoryUri, targetUri, todo.lineIndex)
                if (result.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                } else {
                    _events.emit(Event.ShowMessage(R.string.category_move_success))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun copyTodo(todo: Todo, targetUri: Uri) {
        viewModelScope.launch {
            inFlightWrites.incrementAndGet()
            try {
                val result = fileRepository.copyTodoLine(categoryUri, targetUri, todo.lineIndex)
                if (result.isFailure) {
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                } else {
                    _events.emit(Event.ShowMessage(R.string.category_copy_success))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    private fun onWriteFinishedMaybeRefresh() {
        val remaining = inFlightWrites.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0 && pendingRefreshFromObserver.getAndSet(false)) {
            refreshFromDisk(showLoading = false)
        }
    }

    private fun applyLines(
        lines: List<String>,
        autoToggledLineIndices: Set<Int> = emptySet(),
    ) {
        val todos = MarkdownParser.parse(lines)
        val (completed, active) = todos.partition { it.isDone }

        // MD Todo：同时产出父子树，供 MdTodoRow 渲染层级与折叠。
        // 顶层（parentId == null）分别归入「进行中」与「已完成」两棵树。
        val roots = MarkdownTreeParser.parseTree(lines)
        val (completedRoots, activeRoots) = roots
            .filter { it.parentId == null }
            .partition { it.isDone }

        _uiState.value = _uiState.value.copy(
            activeTodos = active.sortedBy { it.lineIndex },
            completedTodos = completed.sortedBy { it.lineIndex },
            activeRoots = activeRoots.sortedBy { it.lineIndex },
            completedRoots = completedRoots.sortedBy { it.lineIndex },
            allTodos = todos.sortedBy { it.lineIndex },
            lines = lines,
            moveTargets = _uiState.value.moveTargets,
            loadError = null,
            autoToggledLineIndices = autoToggledLineIndices,
        )
    }

    sealed interface Event {
        data class ShowMessage(@param:StringRes val messageResId: Int) : Event
    }
}
