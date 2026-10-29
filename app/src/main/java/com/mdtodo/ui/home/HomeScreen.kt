package com.mdtodo.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
// 别名：lazy.grid.items 与本文件的 LazyRow 都要用 items，不别名会解析错重载
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.mdtodo.BuildConfig
import com.mdtodo.R
import com.mdtodo.data.FileRepository
import com.mdtodo.data.PreferencesManager
import com.mdtodo.data.settings.AppSettingsRepository
import com.mdtodo.data.settings.CategoryLayoutMode
import com.mdtodo.data.settings.CategorySort
import com.mdtodo.data.focus.FocusPinStore
import com.mdtodo.data.settings.DailyFocusMode
import com.mdtodo.data.model.Category
import com.mdtodo.ui.components.TodosianDimens
import com.mdtodo.ui.components.TodosianEmptyState
import com.mdtodo.ui.components.TodosianLoadingIndicator
import com.mdtodo.ui.components.TodosianLinearProgress
import com.mdtodo.ui.components.todosianFabShape
import com.mdtodo.ui.category.CategoryScreen
import com.mdtodo.ui.dailyfocus.DailyFocusScreen
import com.mdtodo.ui.settings.SettingsScreen
import com.mdtodo.ui.transition.ContainerOriginStore
import com.mdtodo.ui.transition.ContainerTransformPage
import com.mdtodo.ui.transition.TransformOrigin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.annotation.StringRes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    preferencesManager: PreferencesManager,
    focusPinStore: FocusPinStore,
    onOpenSearch: () -> Unit,
    onOpenAddTask: () -> Unit,
    refreshSignal: Long,
    onRequireOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(fileRepository),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // MD Todo：原项目在此监听版本变化并弹出「更新日志」BottomSheet。
    // 已按用户决定移除更新日志功能，故这段首次启动检查与相关状态一并删除。

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshIfDirty()
        }
    }

    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is HomeViewModel.Event.ShowMessage -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageResId))
                }
                HomeViewModel.Event.RequireOnboarding -> onRequireOnboarding()
            }
        }
    }

    val settings by appSettingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = com.mdtodo.data.settings.AppSettings(),
    )

    LaunchedEffect(refreshSignal) {
        // 从分类页/设置页回来时的刷新必须静默：
        // 这条路径是在「分类页的收回动画还在播」的时候触发的，
        // 一旦进 loading 态，动画背景里的首页会当场变成一片空白，
        // 看起来像闪了一下白。已有数据就继续显示，只换内容。
        if (refreshSignal > 0L) viewModel.refresh(showLoading = false)
    }

    var showNewCategorySheet by remember { mutableStateOf(false) }

    // 分类页不走 NavHost，改成首页上的一层覆盖（2026-10-28）。
    // 原因：NavHost 在转场结束后会把首页从组合里摘掉，
    // 于是「卡片缩回列表项」的后半段背景是一片空白——
    // 便签的列表页在整段转场里都在下面透出来，这是它观感的关键。
    // 只有让首页一直挂载才能还原。
    var overlay by remember { mutableStateOf<HomeOverlay?>(null) }
    var overlayOrigin by remember { mutableStateOf<TransformOrigin?>(null) }
    // 从「今日聚焦」卡片的三点进来时，落地页要自动展开手动添加选择器
    var dailyFocusPickerOnStart by remember { mutableStateOf(false) }
    fun openOverlay(target: HomeOverlay) {
        overlayOrigin = ContainerOriginStore.consume()
        overlay = target
    }
    val density = LocalDensity.current

    // 已存在的文件夹路径，给「新建分类」对话框做快捷前缀用
    val existingFolders = remember(uiState.categories) {
        uiState.categories.map { it.folderPath }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }
    var newCategoryName by remember { mutableStateOf("") }

    var renameTarget by remember { mutableStateOf<Category?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Category?>(null) }

    val scope = rememberCoroutineScope()
    val newCategorySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val renameCategorySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    if (showNewCategorySheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showNewCategorySheet = false
                newCategoryName = ""
            },
            sheetState = newCategorySheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_new_category_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.home_new_category_hint)) },
                    supportingText = {
                        Text(text = stringResource(R.string.home_new_category_folder_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // 已有文件夹快捷前缀：点一下就把 "学习/" 填进输入框，
                // 用户只需要再打文件名，不用记着文件夹叫什么
                if (existingFolders.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        lazyRowItems(items = existingFolders, key = { it }) { folder ->
                            AssistChip(
                                onClick = { newCategoryName = "$folder/" },
                                label = { Text(text = folder) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                newCategorySheetState.hide()
                                showNewCategorySheet = false
                                newCategoryName = ""
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            viewModel.createCategory(newCategoryName)
                            scope.launch {
                                newCategorySheetState.hide()
                                showNewCategorySheet = false
                                newCategoryName = ""
                            }
                        },
                        enabled = newCategoryName.trim().isNotEmpty(),
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }

    if (renameTarget != null) {
        val validation = remember(renameValue) { validateCategoryNameForRename(renameValue) }
        val errorText = validation.errorMessageResId?.let { stringResource(it) }
        ModalBottomSheet(
            onDismissRequest = {
                renameTarget = null
                renameValue = ""
            },
            sheetState = renameCategorySheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_rename_category_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.home_rename_category_hint)) },
                    isError = errorText != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorText != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
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
                                renameCategorySheetState.hide()
                                renameTarget = null
                                renameValue = ""
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            val target = renameTarget
                            val normalized = validation.normalizedFileName
                            if (target != null && normalized != null) {
                                viewModel.renameCategory(target.uri, normalized)
                            }
                            scope.launch {
                                renameCategorySheetState.hide()
                                renameTarget = null
                                renameValue = ""
                            }
                        },
                        enabled = validation.normalizedFileName != null,
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(text = stringResource(R.string.home_delete_category_title)) },
            text = {
                Text(
                    text = stringResource(R.string.home_delete_category_body),
                    textAlign = TextAlign.Start,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deleteTarget
                        if (target != null) viewModel.deleteCategory(target.uri)
                        deleteTarget = null
                    },
                ) {
                    Text(text = stringResource(R.string.home_delete_category_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // 分类选择栏当前选中的文件夹（null = 全部，不分文件夹）。
    //
    // 用「扁平路径」而不是逐层下钻：文件夹在 chip 上直接写全路径
    // （`学习` / `学习/高数`），一屏就能看到有哪些分类，
    // 不用一层层点进去再退回来。用户明确说过担心分类「太乱」，
    // 扁平列表比层级导航更不容易迷路。
    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.cd_search),
                        )
                    }
                    // MD Todo：列表 / 宫格切换（移植自 OPPO 便签）。
                    //
                    // 图标显示的是「点下去会变成什么」，和便签的行为一致：
                    // 当前是宫格 → 显示列表图标；当前是列表 → 显示宫格图标。
                    // 选择存在 SharedPreferences 里，重启 App 后保持。
                    IconButton(
                        onClick = {
                            val next = when (settings.categoryLayout) {
                                CategoryLayoutMode.GRID -> CategoryLayoutMode.LIST
                                CategoryLayoutMode.LIST -> CategoryLayoutMode.GRID
                            }
                            appSettingsRepository.setCategoryLayout(next)
                        },
                    ) {
                        Icon(
                            imageVector = when (settings.categoryLayout) {
                                CategoryLayoutMode.GRID -> Icons.AutoMirrored.Filled.ViewList
                                CategoryLayoutMode.LIST -> Icons.Filled.GridView
                            },
                            contentDescription = stringResource(
                                when (settings.categoryLayout) {
                                    CategoryLayoutMode.GRID -> R.string.cd_switch_to_list
                                    CategoryLayoutMode.LIST -> R.string.cd_switch_to_grid
                                },
                            ),
                        )
                    }
                    // 设置页同样走容器变换：从这颗齿轮的位置长出来。
                    // 图标本身很小，展开到全屏的缩放比很大，
                    // 所以内容用 alpha 提前淡入，前段看到的是底色块而不是糊成一片的字。
                    var settingsBounds by remember { mutableStateOf(Rect.Zero) }
                    IconButton(
                        onClick = {
                            ContainerOriginStore.remember(
                                bounds = settingsBounds,
                                cornerRadiusPx = with(density) { 22.dp.toPx() },
                            )
                            openOverlay(HomeOverlay.Settings)
                        },
                        modifier = Modifier.onGloballyPositioned {
                            settingsBounds = it.boundsInWindow()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.cd_open_settings),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            // MD Todo：这里的语义 2026-10-28 修正过一次。
            //
            // 原来的行为是「点 + 新建分类」，但在这个 App 里用户 99% 的操作是
            // 加待办、一个月建不了两次分类——语义是反的。
            // 现在 + = 新建待办，直接进分类选择页（Routes.AddTask），
            // 选完分类自动打开那个分类的新建待办输入框。
            //
            // 「新建分类」挪到分类筛选栏末尾的 + chip：分类相关的操作
            // 集中在分类栏，不和「加待办」抢同一个按钮。
            FloatingActionButton(
                onClick = onOpenAddTask,
                shape = todosianFabShape(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.fab_new_todo),
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        val categories = remember(uiState.categories, settings.categorySort) {
            when (settings.categorySort) {
                CategorySort.A_Z -> uiState.categories.sortedBy { it.displayName.lowercase() }
                CategorySort.MOST_REMAINING -> uiState.categories.sortedByDescending { it.todoCount - it.doneCount }
            }
        }

        val contentState = remember(uiState.isLoading, categories.isEmpty()) {
            when {
                uiState.isLoading -> HomeContentState.LOADING
                categories.isEmpty() -> HomeContentState.EMPTY
                else -> HomeContentState.DATA
            }
        }

        AnimatedContent(
            targetState = contentState,
            transitionSpec = {
                fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(160))
            },
            label = "home-content",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = TodosianDimens.ScreenHorizontalPadding),
        ) { state ->
            when (state) {
                HomeContentState.LOADING -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        TodosianLoadingIndicator()
                    }
                }

                HomeContentState.EMPTY -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = settings.showDailyFocus,
                            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                            exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
                        ) {
                            Column {
                                DailyFocusBanner(
                                    remaining = uiState.remainingFor(settings.dailyFocusMode),
                                    mode = settings.dailyFocusMode,
                                    onClick = { openOverlay(HomeOverlay.DailyFocus) },
                                    onAddFocusTasks = {
                                        dailyFocusPickerOnStart = true
                                        openOverlay(HomeOverlay.DailyFocus)
                                    },
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                        EmptyHomeState(
                            onCreateCategory = { showNewCategorySheet = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                HomeContentState.DATA -> {
                    // chip 上列出所有出现过的文件夹（含嵌套的完整路径）
                    val folderChips = remember(categories) {
                        categories.map { it.folderPath }
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .sorted()
                    }
                    // 选中某个文件夹时，显示它自己 + 它下面所有子层的文件；
                    // 「全部」则不做任何过滤。
                    val visibleCategories = remember(categories, selectedFolder) {
                        val folder = selectedFolder
                        if (folder == null) {
                            categories
                        } else {
                            categories.filter {
                                it.folderPath == folder || it.folderPath.startsWith("$folder/")
                            }
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = settings.showDailyFocus,
                            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                            exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
                        ) {
                            Column {
                                DailyFocusBanner(
                                    remaining = uiState.remainingFor(settings.dailyFocusMode),
                                    mode = settings.dailyFocusMode,
                                    onClick = { openOverlay(HomeOverlay.DailyFocus) },
                                    onAddFocusTasks = {
                                        dailyFocusPickerOnStart = true
                                        openOverlay(HomeOverlay.DailyFocus)
                                    },
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }

                        // 分类选择栏：固定在「今日聚焦」下面（用户指定位置）。
                        // 放在 AnimatedContent 外面——切换列表/宫格时它不该跟着淡出淡入。
                        CategoryFilterBar(
                            folders = folderChips,
                            selectedFolder = selectedFolder,
                            onSelect = { selectedFolder = it },
                            onCreateCategory = { showNewCategorySheet = true },
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // MD Todo：列表 / 宫格双模式（移植自 OPPO 便签）。
                        //
                        // 切换动画刻意做成**纯交叉淡化**：便签实测下来，
                        // 切换过程中旧卡片留在原位逐渐变透明、新内容再淡入，
                        // 既没有位移也没有缩放。所以这里只动 opacity——
                        // 不加 slide（会暗示方向）、不加 scale（会暗示层级）。
                        // 顶部标题与每日聚焦卡片不在 AnimatedContent 内，保持不动。
                        AnimatedContent(
                            targetState = settings.categoryLayout,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(240)) togetherWith
                                    fadeOut(animationSpec = tween(180))
                            },
                            label = "category-layout",
                            modifier = Modifier.fillMaxSize(),
                        ) { layout ->
                            when (layout) {
                                CategoryLayoutMode.GRID -> CategoryGrid(
                                    categories = visibleCategories,
                                    onOpen = { category -> openOverlay(HomeOverlay.Category(category)) },
                                    onRename = {
                                        renameTarget = it
                                        renameValue = it.displayName
                                    },
                                    onDelete = { deleteTarget = it },
                                )

                                CategoryLayoutMode.LIST -> CategoryList(
                                    categories = visibleCategories,
                                    onOpen = { category -> openOverlay(HomeOverlay.Category(category)) },
                                    onRename = {
                                        renameTarget = it
                                        renameValue = it.displayName
                                    },
                                    onDelete = { deleteTarget = it },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

        // 二级页面覆盖层：叠在首页之上，首页始终可见（转场背景靠它）
        val open = overlay
        if (open != null) {
            ContainerTransformPage(
                origin = overlayOrigin,
                onExitFinished = {
                    overlay = null
                    overlayOrigin = null
                    dailyFocusPickerOnStart = false
                    // 回来时静默刷新：不进 loading 态，否则收回动画的最后一帧
                    // 会撞上首页变空白
                    viewModel.refresh(showLoading = false)
                },
            ) { requestBack ->
                when (open) {
                    is HomeOverlay.Category -> CategoryScreen(
                        fileRepository = fileRepository,
                        appSettingsRepository = appSettingsRepository,
                        categoryUri = open.category.uri,
                        onBack = requestBack,
                    )

                    HomeOverlay.DailyFocus -> DailyFocusScreen(
                        fileRepository = fileRepository,
                        appSettingsRepository = appSettingsRepository,
                        focusPinStore = focusPinStore,
                        openPickerOnStart = dailyFocusPickerOnStart,
                        onBack = requestBack,
                    )

                    HomeOverlay.Settings -> SettingsScreen(
                        fileRepository = fileRepository,
                        appSettingsRepository = appSettingsRepository,
                        preferencesManager = preferencesManager,
                        onBack = requestBack,
                        onRequireOnboarding = {
                            overlay = null
                            overlayOrigin = null
                            onRequireOnboarding()
                        },
                    )
                }
            }
        }
    }
}

/** 首页之上可以打开的二级页面。全部走同一条容器变换路径。 */
private sealed interface HomeOverlay {
    // 全限定名必须写全：这里在 HomeOverlay 内部，裸写 Category 会解析成嵌套类自己
    data class Category(val category: com.mdtodo.data.model.Category) : HomeOverlay
    data object DailyFocus : HomeOverlay
    data object Settings : HomeOverlay
}

private enum class HomeContentState {
    LOADING,
    EMPTY,
    DATA,
}

private data class RenameCategoryValidation(
    val normalizedFileName: String?,
    @param:StringRes val errorMessageResId: Int?,
)

private fun validateCategoryNameForRename(input: String): RenameCategoryValidation {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return RenameCategoryValidation(
            normalizedFileName = null,
            errorMessageResId = R.string.home_rename_category_error_empty,
        )
    }

    if (trimmed.contains("sync-conflict", ignoreCase = true)) {
        return RenameCategoryValidation(
            normalizedFileName = null,
            errorMessageResId = R.string.home_rename_category_error_sync_conflict,
        )
    }

    val invalidChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    if (trimmed.any { it in invalidChars }) {
        return RenameCategoryValidation(
            normalizedFileName = null,
            errorMessageResId = R.string.home_rename_category_error_invalid_chars,
        )
    }

    val base = trimmed.removeSuffix(".md").removeSuffix(".MD").removeSuffix(".Md").removeSuffix(".mD").trim()
    if (base.isEmpty()) {
        return RenameCategoryValidation(
            normalizedFileName = null,
            errorMessageResId = R.string.home_rename_category_error_empty,
        )
    }

    val normalized = "$base.md"
    return RenameCategoryValidation(
        normalizedFileName = normalized,
        errorMessageResId = null,
    )
}

@Composable
private fun DailyFocusBanner(
    remaining: Int,
    mode: DailyFocusMode,
    onClick: () -> Unit,
    onAddFocusTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // 聚焦页的容器变换从这个横幅长出来，所以要先量好它在哪
    val density = LocalDensity.current
    var bannerBounds by remember { mutableStateOf(Rect.Zero) }
    Card(
        onClick = {
            ContainerOriginStore.remember(
                bounds = bannerBounds,
                cornerRadiusPx = with(density) { 28.dp.toPx() },
            )
            onClick()
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { bannerBounds = it.boundsInWindow() },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.weight(1f))
                // 「任务预览」的溢出菜单：手动添加聚焦任务是唯一项。
                // 放在卡片右上角而不是单独放个按钮——这个操作频率低，
                // 常驻按钮会抢掉「点卡片进聚焦页」的主操作面积。
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.cd_overflow_menu),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.focus_pin_add_action)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onAddFocusTasks()
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_daily_focus_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = pluralStringResource(
                    when (mode) {
                        DailyFocusMode.TODAY -> R.plurals.home_daily_focus_subtitle_today
                        DailyFocusMode.OVERDUE -> R.plurals.home_daily_focus_subtitle_overdue
                        DailyFocusMode.TODAY_AND_OVERDUE -> R.plurals.home_daily_focus_subtitle_today_overdue
                    },
                    remaining,
                    remaining,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun CategoryCard(
    category: Category,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (category.todoCount == 0) 0f else category.doneCount.toFloat() / category.todoCount.toFloat()
    var menuExpanded by remember(category.uri) { mutableStateOf(false) }
    // 转场要用「这张卡片现在在哪、多大、多圆」作为容器变换的起点。
    // 必须在点击前就量好——点击之后再量已经没有意义（页面已经开始退场）。
    val density = LocalDensity.current
    var itemBounds by remember(category.uri) { mutableStateOf(Rect.Zero) }
    Card(
        onClick = {
            ContainerOriginStore.remember(
                bounds = itemBounds,
                cornerRadiusPx = with(density) { 20.dp.toPx() },
            )
            onClick()
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.extraLarge,
        // 固定高度：所有卡片一样高，网格才齐
        modifier = modifier
            .fillMaxWidth()
            .height(136.dp)
            .onGloballyPositioned { itemBounds = it.boundsInWindow() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = category.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    // 固定两行高：标题一行的卡片和两行的卡片高度一致，
                    // 网格才不会参差（这是改回等高网格后必须配合的一点）
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 6.dp),
                )
                CategoryOverflowMenu(
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                    onRename = onRename,
                    onDelete = onDelete,
                )
            }
            // 把计数和进度条顶到卡片底部：这样标题一行的卡片和两行的卡片，
            // 计数会落在同一水平线上，网格看起来才整齐。
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${category.doneCount}/${category.todoCount}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            // 卡片里不放待办内容摘要（用户要求：标题下只留进度条）。
            // 想看具体任务点进分类页——首页卡片只回答「这个列表做了多少」。
            TodosianLinearProgress(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
            )
        }
    }
}

/**
 * 分类卡片/行的溢出菜单（重命名 / 删除）。
 * 抽出来是因为宫格卡片和列表行都要用，两处各写一遍容易改漏。
 */
@Composable
private fun CategoryOverflowMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    iconSize: Dp = 20.dp,
    buttonSize: Dp = 32.dp,
) {
    Box {
        IconButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.size(buttonSize),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.cd_overflow_menu),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.home_category_action_rename)) },
                onClick = {
                    onExpandedChange(false)
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.home_category_action_delete)) },
                onClick = {
                    onExpandedChange(false)
                    onDelete()
                },
            )
        }
    }
}

/**
 * 分类选择栏（分类依据 = 文件夹层级）。
 *
 * 移植自便签顶部那排胶囊（全部笔记 / 速记 / 我的收藏 / 默认笔记本）。
 * 区别：这里的文件夹可以嵌套，chip 上直接写完整相对路径
 * （`学习` / `学习/高数`），一屏看全，不做逐层下钻——
 * 用户明确担心分类「太乱」，扁平列表比层级导航更不容易迷路。
 *
 * 选中某个文件夹 = 显示它自己和它所有子层里的文件；
 * 选中「全部」= 不过滤。
 */
@Composable
private fun CategoryFilterBar(
    folders: List<String>,
    selectedFolder: String?,
    onSelect: (String?) -> Unit,
    onCreateCategory: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "__all__") {
            FilterChip(
                selected = selectedFolder == null,
                onClick = { onSelect(null) },
                label = { Text(text = stringResource(R.string.home_folder_all)) },
            )
        }
        lazyRowItems(items = folders, key = { it }) { folder ->
            FilterChip(
                selected = selectedFolder == folder,
                onClick = { onSelect(folder) },
                label = { Text(text = folder) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        // 新建分类的入口跟着分类栏走。
        // 之前它在右下角的 FAB 上，和「加待办」抢同一个按钮；
        // 挪到这里之后「分类相关的操作都在分类栏」这条规则才成立。
        item(key = "__new_category__") {
            AssistChip(
                onClick = onCreateCategory,
                label = { Text(text = stringResource(R.string.fab_new_category)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

/**
 * 宫格模式：2 列等高网格。
 *
 * 曾经用过瀑布流（StaggeredGrid），是为了让带预览内容的卡片高低错落。
 * 现在卡片里只放进度条、不放待办摘要，高度天然一致，瀑布流只会让
 * 两列互相错位，所以退回等高网格——整齐才是这里想要的。
 */
@Composable
private fun CategoryGrid(
    categories: List<Category>,
    onOpen: (Category) -> Unit,
    onRename: (Category) -> Unit,
    onDelete: (Category) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            items = categories,
            key = { it.uri.toString() },
        ) { category ->
            CategoryCard(
                category = category,
                onClick = { onOpen(category) },
                onRename = { onRename(category) },
                onDelete = { onDelete(category) },
            )
        }
    }
}

/**
 * 列表模式：一整块圆角容器，里面平铺行 + 缩进分隔线。
 *
 * 关键点（照抄便签的观感）：**不是每行一张卡片**，而是所有行共享一块底色，
 * 只有整块的四个角是圆的，行与行之间用一条从左侧缩进开始的细线分隔。
 * 所以首行加顶部圆角、末行加底部圆角、中间行是直角。
 */
@Composable
private fun CategoryList(
    categories: List<Category>,
    onOpen: (Category) -> Unit,
    onRename: (Category) -> Unit,
    onDelete: (Category) -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainer
    LazyColumn(
        contentPadding = PaddingValues(bottom = 96.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(
            items = categories,
            key = { _, category -> category.uri.toString() },
        ) { index, category ->
            val shape = when {
                categories.size == 1 -> RoundedCornerShape(20.dp)
                index == 0 -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                index == categories.lastIndex -> RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
                else -> RectangleShape
            }
            CategoryListRow(
                category = category,
                onClick = { onOpen(category) },
                onRename = { onRename(category) },
                onDelete = { onDelete(category) },
                containerColor = containerColor,
                shape = shape,
                showDivider = index != categories.lastIndex,
            )
        }
    }
}

/** 列表模式的单行：标题 + 右侧计数 + 下一行预览。 */
@Composable
private fun CategoryListRow(
    category: Category,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    containerColor: Color,
    shape: Shape,
    showDivider: Boolean,
) {
    var menuExpanded by remember(category.uri) { mutableStateOf(false) }
    val progress = if (category.todoCount == 0) {
        0f
    } else {
        category.doneCount.toFloat() / category.todoCount.toFloat()
    }
    // 列表行的容器变换起点同宫格卡片，圆角用列表容器的 20dp
    val density = LocalDensity.current
    var itemBounds by remember(category.uri) { mutableStateOf(Rect.Zero) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 没有预览的行（全部完成、或文件里没有未完成任务）会矮一截，
            // 在连续的分隔线列表里看着像断了。给个最小高度让所有行等高。
            .defaultMinSize(minHeight = 72.dp)
            .clip(shape)
            .background(containerColor)
            .onGloballyPositioned { itemBounds = it.boundsInWindow() }
            .clickable {
                ContainerOriginStore.remember(
                    bounds = itemBounds,
                    cornerRadiusPx = with(density) { 20.dp.toPx() },
                )
                onClick()
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = 16.dp,
                end = 4.dp,
                top = 14.dp,
                bottom = 0.dp,
            ),
        ) {
            Text(
                text = category.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${category.doneCount}/${category.todoCount}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CategoryOverflowMenu(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                onRename = onRename,
                onDelete = onDelete,
                iconSize = 18.dp,
                buttonSize = 36.dp,
            )
        }
        // 列表行同样不放待办预览，用一条细进度条代替
        // （便签的列表行也只有「标题 + 一行信息」，不塞正文摘要）
        Spacer(modifier = Modifier.height(8.dp))
        TodosianLinearProgress(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp),
        )
        Spacer(modifier = Modifier.height(14.dp))
        // 分隔线：从左侧缩进开始、右侧留白结束，不顶到容器边缘（便签就是这样）
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun EmptyHomeState(
    onCreateCategory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TodosianEmptyState(
        icon = Icons.Outlined.CreateNewFolder,
        title = stringResource(R.string.home_empty_title),
        subtitle = stringResource(R.string.home_empty_subtitle),
        actionLabel = stringResource(R.string.home_create_category),
        onAction = onCreateCategory,
        modifier = modifier,
    )
}

private class HomeViewModelFactory(
    private val fileRepository: FileRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(fileRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
