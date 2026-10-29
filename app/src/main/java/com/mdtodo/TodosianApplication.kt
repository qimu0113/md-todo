package com.mdtodo

import android.app.Application
import com.mdtodo.data.FileRepository
import com.mdtodo.data.PreferencesManager
import com.mdtodo.data.SafFileRepository
import com.mdtodo.data.focus.FocusPinStore
import com.mdtodo.data.focus.SharedPrefsFocusPinStore
import com.mdtodo.data.settings.AppSettingsRepository
import com.mdtodo.data.settings.SharedPrefsAppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class TodosianApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(applicationContext)
    }

    val fileRepository: FileRepository by lazy {
        SafFileRepository(
            appContext = applicationContext,
            preferencesManager = preferencesManager,
        )
    }

    val appSettingsRepository: AppSettingsRepository by lazy {
        SharedPrefsAppSettingsRepository(applicationContext)
    }

    /**
     * 「手动加入今日聚焦」的名单。存本地 SharedPreferences，
     * 不写进 md —— 详情见 [FocusPinStore] 的注释。
     */
    val focusPinStore: FocusPinStore by lazy {
        SharedPrefsFocusPinStore(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()

        // MD Todo：原项目在这里监听设置变化并同步「到期提醒」的闹钟调度。
        // 已按用户决定移除整个通知提醒模块（notifications/），故此逻辑一并删除。
        // 每日笔记的自动创建改由 DailyNoteWorker（WorkManager）独立负责，
        // 不再依赖 Application 的 onCreate 初始化。
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}
