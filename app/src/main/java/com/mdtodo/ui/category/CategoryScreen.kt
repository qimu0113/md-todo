package com.mdtodo.ui.category

import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextAlign
import com.mdtodo.ui.components.FullScreenEditorDialog
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mdtodo.R
import com.mdtodo.data.FileRepository
import com.mdtodo.data.model.Todo
import com.mdtodo.data.model.priorityRank
import com.mdtodo.data.settings.AppSettingsRepository
import com.mdtodo.data.settings.NewTodoFilePosition
import com.mdtodo.data.settings.TodoGrouping
import com.mdtodo.data.settings.TodoSort
import com.mdtodo.ui.components.TasksMetaEditor
import com.mdtodo.ui.components.TodoRow
import com.mdtodo.ui.components.TodoSheetMode
import com.mdtodo.ui.components.TodosianDimens
import com.mdtodo.ui.components.TodosianEmptyState
import com.mdtodo.ui.components.TodosianLoadingIndicator
import com.mdtodo.ui.components.TodosianSectionHeader
import com.mdtodo.ui.components.todosianFabShape
import com.mdtodo.utils.MarkdownParser
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CategoryScreen(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    categoryUri: Uri,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    autoOpenAddTodo: Boolean = false,
) {
    val viewModel: CategoryViewModel = viewModel(
        factory = CategoryViewModelFactory(
            fileRepository = fileRepository,
            categoryUri = categoryUri,
        ),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val settings by appSettingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = com.mdtodo.data.settings.AppSettings(),
    )

    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is CategoryViewModel.Event.ShowMessage -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageResId))
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    val moveSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var sheetMode by remember { mutableStateOf<TodoSheetMode?>(null) }
    var sheetText by remember { mutableStateOf("") }
    var sheetMeta by remember { mutableStateOf(MarkdownParser.TasksMeta()) }
    var sheetParentTodo by remember { mutableStateOf<Todo?>(null) }
    var deleteTodoTarget by remember { mutableStateOf<Todo?>(null) }
    var deleteTodoSubtaskCount by remember { mutableStateOf(0) }
    var moveTodoTarget by remember { mutableStateOf<Todo?>(null) }
    var showCopyOption by remember { mutableStateOf(false) }

    var autoOpenedAddTodo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoOpenAddTodo && !autoOpenedAddTodo) {
            autoOpenedAddTodo = true
            sheetMode = TodoSheetMode.Add
            sheetText = ""
            sheetMeta = if (settings.enableTasksPluginSupport) {
                MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
            } else {
                MarkdownParser.TasksMeta()
            }
        }
    }

    if (sheetMode != null) {
        FullScreenEditorDialog(
            onDismissRequest = {
                sheetMode = null
                sheetText = ""
                sheetMeta = MarkdownParser.TasksMeta()
                sheetParentTodo = null
            },
        ) {
            val scrollState = rememberScrollState()
            val todoTextBringIntoView = remember { BringIntoViewRequester() }
            val recurrenceBringIntoView = remember { BringIntoViewRequester() }
            val titleRes = when (sheetMode) {
                TodoSheetMode.Add -> R.string.category_add_todo_title
                is TodoSheetMode.Edit -> R.string.category_edit_todo_title
                TodoSheetMode.AddSubtask -> R.string.category_add_subtask_title
                null -> R.string.category_add_todo_title
            }

            val hintRes = when (sheetMode) {
                TodoSheetMode.Add -> R.string.category_add_todo_hint
                is TodoSheetMode.Edit -> R.string.category_edit_todo_hint
                TodoSheetMode.AddSubtask -> R.string.category_add_subtask_hint
                null -> R.string.category_add_todo_hint
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(bottom = 16.dp)
                    .verticalScroll(scrollState),
            ) {
                // 顶栏：返回箭头 + 标题。全屏之后没有拖拽条可以下拉关闭，
                // 必须给一个看得见的退出入口，否则只能靠系统返回手势。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        onClick = {
                            sheetMode = null
                            sheetText = ""
                            sheetMeta = MarkdownParser.TasksMeta()
                            sheetParentTodo = null
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = sheetText,
                    onValueChange = { sheetText = it },
                    singleLine = true,
                    label = { Text(text = stringResource(hintRes)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(todoTextBringIntoView)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                scope.launch { todoTextBringIntoView.bringIntoView() }
                            }
                        },
                )

                if (settings.enableTasksPluginSupport) {
                    Spacer(modifier = Modifier.height(12.dp))
                    TasksMetaEditor(
                        mode = sheetMode,
                        meta = sheetMeta,
                        onMetaChange = { sheetMeta = it },
                        useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                        modifier = Modifier
                            .bringIntoViewRequester(recurrenceBringIntoView)
                            .onFocusEvent { focusState ->
                                if (focusState.isFocused) {
                                    scope.launch { recurrenceBringIntoView.bringIntoView() }
                                }
                            },
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                sheetMode = null
                                sheetText = ""
                                sheetMeta = MarkdownParser.TasksMeta()
                                sheetParentTodo = null
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            when (val mode = sheetMode) {
                                TodoSheetMode.Add -> viewModel.addTodo(
                                    text = sheetText,
                                    meta = if (settings.enableTasksPluginSupport) sheetMeta else null,
                                    enableTasksPluginSupport = settings.enableTasksPluginSupport,
                                    addAtStart = settings.newTodoFilePosition == NewTodoFilePosition.TOP,
                                )

                                is TodoSheetMode.Edit -> viewModel.editTodo(
                                    todo = mode.todo,
                                    newText = sheetText,
                                    meta = if (settings.enableTasksPluginSupport) sheetMeta else null,
                                    enableTasksPluginSupport = settings.enableTasksPluginSupport,
                                )

                                TodoSheetMode.AddSubtask -> {
                                    val parent = sheetParentTodo
                                    if (parent != null) {
                                        viewModel.addSubTodo(
                                            parent = parent,
                                            text = sheetText,
                                            meta = if (settings.enableTasksPluginSupport) sheetMeta else null,
                                            enableTasksPluginSupport = settings.enableTasksPluginSupport,
                                        )
                                    }
                                }

                                null -> Unit
                            }
                            scope.launch {
                                sheetMode = null
                                sheetText = ""
                                sheetMeta = MarkdownParser.TasksMeta()
                                sheetParentTodo = null
                            }
                        },
                        enabled = sheetText.trim().isNotEmpty(),
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }

    if (deleteTodoTarget != null) {
        // T2：文案由真实子任务数驱动，不再用「有/无」二值（原实现说不出「几个」）
        val deleteBody = if (deleteTodoSubtaskCount > 0) {
            stringResource(R.string.category_delete_todo_body_with_subtask_count, deleteTodoSubtaskCount)
        } else {
            stringResource(R.string.category_delete_todo_body)
        }
        AlertDialog(
            onDismissRequest = { deleteTodoTarget = null },
            title = { Text(text = stringResource(R.string.category_delete_todo_title)) },
            text = { Text(text = deleteBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deleteTodoTarget
                        if (target != null) {
                            viewModel.deleteTodo(target)
                        }
                        deleteTodoTarget = null
                        deleteTodoSubtaskCount = 0
                    },
                ) {
                    Text(text = stringResource(R.string.category_delete_todo_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleteTodoTarget = null
                    deleteTodoSubtaskCount = 0
                }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (moveTodoTarget != null) {
        LaunchedEffect(moveTodoTarget) {
            viewModel.loadMoveTargets()
        }
        ModalBottomSheet(
            onDismissRequest = {
                moveTodoTarget = null
                showCopyOption = false
            },
            sheetState = moveSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.category_move_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.category_move_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))

                val targets = uiState.moveTargets
                when {
                    targets == null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TodosianLoadingIndicator()
                        }
                    }

                    targets.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.category_move_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        targets.forEach { target ->
                            ListItem(
                                headlineContent = { Text(text = target.title) },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = null,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    val todo = moveTodoTarget
                                    if (todo != null) {
                                        viewModel.moveTodo(todo, target.uri)
                                    }
                                    moveTodoTarget = null
                                    showCopyOption = false
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = { showCopyOption = !showCopyOption }) {
                            Text(
                                text = if (showCopyOption) {
                                    stringResource(R.string.category_copy_hide)
                                } else {
                                    stringResource(R.string.category_copy_show)
                                },
                            )
                        }

                        if (showCopyOption) {
                            Spacer(modifier = Modifier.height(8.dp))
                            targets.forEach { target ->
                                ListItem(
                                    headlineContent = { Text(text = target.title) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Outlined.ContentCopy,
                                            contentDescription = null,
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        val todo = moveTodoTarget
                                        if (todo != null) {
                                            viewModel.copyTodo(todo, target.uri)
                                        }
                                        moveTodoTarget = null
                                        showCopyOption = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    sheetMode = TodoSheetMode.Add
                    sheetText = ""
                    sheetMeta = if (settings.enableTasksPluginSupport) {
                        MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                    } else {
                        MarkdownParser.TasksMeta()
                    }
                },
                shape = todosianFabShape(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_add_todo),
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                TodosianLoadingIndicator()
            }
            return@Scaffold
        }

        // T5：读取失败时显示持久错误态（不是「暂无待办」）。
        // 优先于空态判断——否则飞行模式/权限失效会被渲染成「这个文件没有待办」。
        val loadErrorRes = uiState.loadError
        if (loadErrorRes != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.category_load_failed_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(loadErrorRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { viewModel.refreshFromDisk(showLoading = true) }) {
                    Text(text = stringResource(R.string.action_retry))
                }
            }
            return@Scaffold
        }

        val anyTodos = uiState.activeTodos.isNotEmpty() || uiState.completedTodos.isNotEmpty()
        if (!anyTodos) {
            TodosianEmptyState(
                icon = Icons.Outlined.Checklist,
                title = stringResource(R.string.category_empty_title),
                subtitle = stringResource(R.string.category_empty_subtitle),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }

        val sortedActiveTodos = remember(uiState.activeTodos, settings.todoSort) {
            sortTodos(uiState.activeTodos, settings.todoSort)
        }
        val sortedCompletedTodos = remember(uiState.completedTodos, settings.todoSort) {
            sortTodos(uiState.completedTodos, settings.todoSort)
        }
        val sortedAllTodos = remember(uiState.activeTodos, uiState.completedTodos, settings.todoSort) {
            sortTodos(uiState.activeTodos + uiState.completedTodos, settings.todoSort)
        }

        // MD Todo：父子树。存的是**被收起**的节点 id（T4：语义反转，默认全展开），
        // 用 rememberSaveable 持久化，转屏/进程重建后保持。
        var collapsedIds by rememberSaveable(
            stateSaver = listSaver(
                save = { it.toList() },
                restore = { it.toSet() },
            ),
        ) { mutableStateOf<Set<String>>(emptySet()) }
        val collapsedSet = collapsedIds

        // flattenTodoTree 是纯函数（内部无 remember/composable 调用），
        // 外面这层 remember 只是为了在文件未变化时跳过重算——树扁平化本身很轻，
        // 省略它避免 Composable 上下文嵌套的编译限制。
        // T7 修复：allTodos 传全量（含已完成），否则「父[x]+子[ ]」的未完成子节点
        // 在 completedRoots 的展开遍历里查不到，会被 flattenTodoTree 的 ?: return 丢弃。
        val flatActiveTree = flattenTodoTree(uiState.activeRoots, uiState.allTodos, collapsedSet)
        val flatCompletedTree = flattenTodoTree(uiState.completedRoots, uiState.allTodos, collapsedSet)
        // 有父子层级的文件才走树渲染；全单层的文件保持原TodoRow 行为
        val hasTree = uiState.activeRoots.any { it.hasChildren } ||
            uiState.completedRoots.any { it.hasChildren }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = TodosianDimens.ScreenHorizontalPadding),
        ) {
            if (settings.todoGrouping == TodoGrouping.FILE_ORDER) {
                items(
                    items = sortedAllTodos,
                    key = { it.lineIndex },
                ) { todo ->
                    TodoRow(
                        todo = todo,
                        enableTasksPluginSupport = settings.enableTasksPluginSupport,
                        useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                        showSubtaskButton = settings.enableSubtasks,
                        onToggle = { viewModel.toggleTodo(todo, settings.enableTasksPluginSupport) },
                        onEdit = {
                            sheetMode = TodoSheetMode.Edit(todo)
                            sheetText = todo.text
                            sheetMeta = MarkdownParser.TasksMeta(
                                dueDate = todo.dueDate,
                                startDate = todo.startDate,
                                scheduledDate = todo.scheduledDate,
                                completionDate = todo.completionDate,
                                createdDate = todo.createdDate,
                                priority = todo.priority,
                                recurrence = todo.recurrence,
                            )
                        },
                        onAddSubtask = {
                            sheetMode = TodoSheetMode.AddSubtask
                            sheetParentTodo = todo
                            sheetText = ""
                            sheetMeta = if (settings.enableTasksPluginSupport) {
                                MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                            } else {
                                MarkdownParser.TasksMeta()
                            }
                        },
                        onRequestDelete = {
                            deleteTodoTarget = todo
                            deleteTodoSubtaskCount = MarkdownParser.countSubtasks(uiState.lines, todo.lineIndex)
                        },
                        onRequestMove = { moveTodoTarget = todo },
                        modifier = Modifier,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item { Spacer(modifier = Modifier.height(96.dp)) }
                return@LazyColumn
            }

            if (sortedActiveTodos.isNotEmpty()) {
                item { TodosianSectionHeader(text = stringResource(R.string.category_active)) }

                if (hasTree) {
                    // MD Todo：父子树渲染。子任务缩进 + 可折叠，子任务全完成时父任务自动打勾。
                    items(
                        items = flatActiveTree,
                        key = { it.nodeId },
                    ) { md ->
                        MdTreeRow(
                            item = md.md,
                            enableTasksPluginSupport = settings.enableTasksPluginSupport,
                            useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                            onToggle = {
                                viewModel.toggleTodo(md.md.todo, settings.enableTasksPluginSupport)
                            },
                            onClick = {
                                sheetMode = TodoSheetMode.Edit(md.md.todo)
                                sheetText = md.md.text
                                sheetMeta = MarkdownParser.TasksMeta(
                                    dueDate = md.md.todo.dueDate,
                                    startDate = md.md.todo.startDate,
                                    scheduledDate = md.md.todo.scheduledDate,
                                    completionDate = md.md.todo.completionDate,
                                    createdDate = md.md.todo.createdDate,
                                    priority = md.md.todo.priority,
                                    recurrence = md.md.todo.recurrence,
                                )
                            },
                            onToggleExpand = {
                                collapsedIds = if (collapsedIds.contains(md.nodeId)) {
                                    collapsedIds - md.nodeId
                                } else {
                                    collapsedIds + md.nodeId
                                }
                            },
                            // T1：补齐三个写入口，原来树行只有勾选/点击/展开
                            onRequestDelete = {
                                deleteTodoTarget = md.md.todo
                                deleteTodoSubtaskCount =
                                    MarkdownParser.countSubtasks(uiState.lines, md.md.todo.lineIndex)
                            },
                            onRequestMove = { moveTodoTarget = md.md.todo },
                            onAddSubtask = if (settings.enableSubtasks) {
                                {
                                    sheetMode = TodoSheetMode.Add
                                    sheetText = ""
                                    sheetParentTodo = md.md.todo
                                    sheetMeta = if (settings.enableTasksPluginSupport) {
                                        MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                                    } else {
                                        MarkdownParser.TasksMeta()
                                    }
                                }
                            } else {
                                null
                            },
                            modifier = Modifier,
                        )
                    }
                } else {
                items(
                    items = sortedActiveTodos,
                    key = { it.lineIndex },
                ) { todo ->
                    TodoRow(
                        todo = todo,
                        enableTasksPluginSupport = settings.enableTasksPluginSupport,
                        useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                        showSubtaskButton = settings.enableSubtasks,
                        onToggle = { viewModel.toggleTodo(todo, settings.enableTasksPluginSupport) },
                        onEdit = {
                            sheetMode = TodoSheetMode.Edit(todo)
                            sheetText = todo.text
                            sheetMeta = MarkdownParser.TasksMeta(
                                dueDate = todo.dueDate,
                                startDate = todo.startDate,
                                scheduledDate = todo.scheduledDate,
                                completionDate = todo.completionDate,
                                createdDate = todo.createdDate,
                                priority = todo.priority,
                                recurrence = todo.recurrence,
                            )
                        },
                        onAddSubtask = {
                            sheetMode = TodoSheetMode.AddSubtask
                            sheetParentTodo = todo
                            sheetText = ""
                            sheetMeta = if (settings.enableTasksPluginSupport) {
                                MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                            } else {
                                MarkdownParser.TasksMeta()
                            }
                        },
                        onRequestDelete = {
                            deleteTodoTarget = todo
                            deleteTodoSubtaskCount = MarkdownParser.countSubtasks(uiState.lines, todo.lineIndex)
                        },
                        onRequestMove = { moveTodoTarget = todo },
                        modifier = Modifier,
                    )
                }
                }
            }

            if (sortedCompletedTodos.isNotEmpty()) {
                item { TodosianSectionHeader(text = stringResource(R.string.category_completed)) }

                if (hasTree) {
                    // MD Todo：已完成区同样按树渲染，父任务与其子任务保持在同一区块，
                    // 避免出现「父在完成区、子在进行区」的割裂感。
                    items(
                        items = flatCompletedTree,
                        key = { it.nodeId },
                    ) { md ->
                        MdTreeRow(
                            item = md.md,
                            enableTasksPluginSupport = settings.enableTasksPluginSupport,
                            useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                            onToggle = {
                                viewModel.toggleTodo(md.md.todo, settings.enableTasksPluginSupport)
                            },
                            onClick = {
                                sheetMode = TodoSheetMode.Edit(md.md.todo)
                                sheetText = md.md.text
                                sheetMeta = MarkdownParser.TasksMeta(
                                    dueDate = md.md.todo.dueDate,
                                    startDate = md.md.todo.startDate,
                                    scheduledDate = md.md.todo.scheduledDate,
                                    completionDate = md.md.todo.completionDate,
                                    createdDate = md.md.todo.createdDate,
                                    priority = md.md.todo.priority,
                                    recurrence = md.md.todo.recurrence,
                                )
                            },
                            onToggleExpand = {
                                collapsedIds = if (collapsedIds.contains(md.nodeId)) {
                                    collapsedIds - md.nodeId
                                } else {
                                    collapsedIds + md.nodeId
                                }
                            },
                            // T1：补齐三个写入口，原来树行只有勾选/点击/展开
                            onRequestDelete = {
                                deleteTodoTarget = md.md.todo
                                deleteTodoSubtaskCount =
                                    MarkdownParser.countSubtasks(uiState.lines, md.md.todo.lineIndex)
                            },
                            onRequestMove = { moveTodoTarget = md.md.todo },
                            onAddSubtask = if (settings.enableSubtasks) {
                                {
                                    sheetMode = TodoSheetMode.Add
                                    sheetText = ""
                                    sheetParentTodo = md.md.todo
                                    sheetMeta = if (settings.enableTasksPluginSupport) {
                                        MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                                    } else {
                                        MarkdownParser.TasksMeta()
                                    }
                                }
                            } else {
                                null
                            },
                            modifier = Modifier,
                        )
                    }
                } else {
                items(
                    items = sortedCompletedTodos,
                    key = { it.lineIndex },
                ) { todo ->
                    TodoRow(
                        todo = todo,
                        enableTasksPluginSupport = settings.enableTasksPluginSupport,
                        useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                        showSubtaskButton = settings.enableSubtasks,
                        onToggle = { viewModel.toggleTodo(todo, settings.enableTasksPluginSupport) },
                        onEdit = {
                            sheetMode = TodoSheetMode.Edit(todo)
                            sheetText = todo.text
                            sheetMeta = MarkdownParser.TasksMeta(
                                dueDate = todo.dueDate,
                                startDate = todo.startDate,
                                scheduledDate = todo.scheduledDate,
                                completionDate = todo.completionDate,
                                createdDate = todo.createdDate,
                                priority = todo.priority,
                                recurrence = todo.recurrence,
                            )
                        },
                        onAddSubtask = {
                            sheetMode = TodoSheetMode.AddSubtask
                            sheetParentTodo = todo
                            sheetText = ""
                            sheetMeta = if (settings.enableTasksPluginSupport) {
                                MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                            } else {
                                MarkdownParser.TasksMeta()
                            }
                        },
                        onRequestDelete = {
                            deleteTodoTarget = todo
                            deleteTodoSubtaskCount = MarkdownParser.countSubtasks(uiState.lines, todo.lineIndex)
                        },
                        onRequestMove = { moveTodoTarget = todo },
                        modifier = Modifier,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                }
            }

            item { Spacer(modifier = Modifier.height(96.dp)) }
        }
    }
}

private fun sortTodos(todos: List<Todo>, sort: TodoSort): List<Todo> {
    return when (sort) {
        TodoSort.FILE_ORDER -> todos.sortedBy { it.lineIndex }
        TodoSort.PRIORITY_HIGH_TO_LOW -> sortTodosByPriorityKeepingSubtasks(todos)
        TodoSort.CREATED_DATE_NEWEST_FIRST ->
            todos.sortedWith(
                compareByDescending<Todo> { it.createdDate != null }
                    .thenByDescending { it.createdDate ?: "" }
                    .thenBy { it.lineIndex },
            )
            
        TodoSort.DUE_DATE_EARLIEST_FIRST ->
            todos.sortedWith(
                compareBy<Todo> { it.dueDate == null }
                    .thenBy { it.dueDate ?: "" }
                    .thenBy { it.lineIndex },
            )
    }
}

private data class TodoGroup(
    val parent: Todo,
    val children: List<Todo>,
)

private fun sortTodosByPriorityKeepingSubtasks(todos: List<Todo>): List<Todo> {
    if (todos.isEmpty()) return todos

    val ordered = todos.sortedBy { it.lineIndex }
    val groups = ArrayList<TodoGroup>()

    var currentParent: Todo? = null
    var currentChildren = ArrayList<Todo>()

    fun flushGroup() {
        val parent = currentParent
        if (parent != null) {
            groups.add(TodoGroup(parent = parent, children = currentChildren.toList()))
        }
    }

    for (todo in ordered) {
        if (todo.indentLevel == 0 || currentParent == null) {
            flushGroup()
            currentParent = todo
            currentChildren = ArrayList()
        } else {
            currentChildren.add(todo)
        }
    }

    flushGroup()

    val sortedGroups = groups.sortedWith(
        compareByDescending<TodoGroup> { it.parent.priority.priorityRank() }
            .thenBy { it.parent.lineIndex },
    )

    val result = ArrayList<Todo>(ordered.size)
    for (group in sortedGroups) {
        result.add(group.parent)
        result.addAll(group.children)
    }

    return result
}

private class CategoryViewModelFactory(
    private val fileRepository: FileRepository,
    private val categoryUri: Uri,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CategoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CategoryViewModel(fileRepository, categoryUri) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
