package com.mdtodo.ui.home

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdtodo.R
import com.mdtodo.data.FileRepository
import com.mdtodo.data.model.Category
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview

data class HomeUiState(
    val isLoading: Boolean = false,
    val categories: List<Category> = emptyList(),
) {
    fun remainingFor(mode: com.mdtodo.data.settings.DailyFocusMode): Int {
        return when (mode) {
            com.mdtodo.data.settings.DailyFocusMode.TODAY -> categories.sumOf { it.dueTodayCount }
            com.mdtodo.data.settings.DailyFocusMode.OVERDUE -> categories.sumOf { it.overdueCount }
            com.mdtodo.data.settings.DailyFocusMode.TODAY_AND_OVERDUE ->
                categories.sumOf { it.dueTodayCount + it.overdueCount }
        }
    }
}

class HomeViewModel(
    private val fileRepository: FileRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    private val _dirtyVersion = MutableStateFlow(0L)

    private var observeJob: Job? = null
    private var observedFolderUri: Uri? = null

    init {
        refresh()
    }

    private fun ensureFolderObserver(folderUri: Uri) {
        if (observedFolderUri == folderUri && observeJob?.isActive == true) return

        observeJob?.cancel()
        observedFolderUri = folderUri

        @OptIn(FlowPreview::class)
        observeJob = viewModelScope.launch {
            fileRepository.observeMarkdownFilesChanges(folderUri)
                .debounce(350)
                .catch {
                    // Thanks god app still works without live updates.
                }
                .collectLatest {
                    _dirtyVersion.value = System.currentTimeMillis()
                }
        }
    }

    fun refreshIfDirty() {
        val version = _dirtyVersion.value
        if (version <= 0L) return
        _dirtyVersion.value = 0L
        refresh(showLoading = false)
    }

    fun refresh(showLoading: Boolean = true) {
        viewModelScope.launch {
            val folderUri = fileRepository.getFolderUri()
            if (folderUri == null) {
                _events.emit(Event.RequireOnboarding)
                _uiState.value = HomeUiState(isLoading = false)
                return@launch
            }

            ensureFolderObserver(folderUri)

            val shouldShowLoading = showLoading || _uiState.value.categories.isEmpty()
            if (shouldShowLoading) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }
            val result = fileRepository.getCategories()
            if (result.isFailure) {
                // T6 修复（2026-10-27，基线文档标红）：原实现一次读取失败就
                // clearFolderUri() + 强制重走 Onboarding。拔卡、Syncthing 同步中、
                // 文件夹临时不可用都会触发——用户的绑定配置被抹掉，下次要重新选文件夹。
                //
                // 现在按原因分流，**两种情况都不清 URI**：
                //   权限没了   → 提示重新授权（用户动作：去系统设置授权）
                //   权限还在但读不到 → 文件夹被删/改名（用户动作：检查文件是否存在）
                val permissionLost = !fileRepository.hasPersistedReadWritePermission(folderUri)
                _events.emit(
                    Event.ShowMessage(
                        if (permissionLost) R.string.error_permission_lost
                        else R.string.error_folder_unavailable
                    )
                )
                // 保留已有分类数据，只关掉 loading——界面不会突然变空
                _uiState.value = _uiState.value.copy(isLoading = false)
                return@launch
            }

            _uiState.value = HomeUiState(
                isLoading = false,
                categories = result.getOrThrow(),
            )
        }
    }

    fun createCategory(name: String) {
        viewModelScope.launch {
            val folderUri = fileRepository.getFolderUri() ?: run {
                _events.emit(Event.RequireOnboarding)
                return@launch
            }

            val created = fileRepository.createCategory(folderUri, name)
            if (created.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
                return@launch
            }
            refresh()
        }
    }

    fun renameCategory(categoryUri: Uri, newName: String) {
        viewModelScope.launch {
            val result = fileRepository.renameCategory(categoryUri, newName)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
                return@launch
            }
            refresh()
        }
    }

    fun deleteCategory(categoryUri: Uri) {
        viewModelScope.launch {
            val result = fileRepository.deleteCategory(categoryUri)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
                return@launch
            }
            refresh()
        }
    }

    sealed interface Event {
        data class ShowMessage(@param:StringRes val messageResId: Int) : Event
        data object RequireOnboarding : Event
    }
}
