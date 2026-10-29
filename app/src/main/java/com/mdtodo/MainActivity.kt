package com.mdtodo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mdtodo.ui.theme.TodosianTheme
import com.mdtodo.data.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val addTaskSignal = MutableStateFlow(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MD Todo：v4.3 回退「预测性返回手势」实验。
        //
        // v3 时为改善退出动画，在 Manifest 声明 android:enableOnBackInvokedCallback="true"
        // 并用反射调用 Activity.enableOnBackInvokedCallback()。真机验证（OnePlus Ace 6 Ultra /
        // ColorOS / Android 16）发现该组合导致严重问题：
        //   - ColorOS 的 OplusPredictiveBackController 检测到声明后 hook 回调，
        //     但反射调用实际失败（ColorOS 未暴露该方法），hook 与真实状态不一致
        //   - 返回手势处理进入死循环，主线程 99% CPU 空转 → ANR
        //   - 表现为「从子界面返回主界面时界面卡死」
        //
        // 结论：国产 ROM 对 predictive back 的自有 hook 不可控，不自个儿开。
        // 退出动画回归系统默认路径（ComponentActivity 的 OnBackPressedDispatcher 已足够）。
        enableEdgeToEdge()

        handleIntent(intent)

        val preferencesManager = (application as TodosianApplication).preferencesManager
        val fileRepository = (application as TodosianApplication).fileRepository
        val appSettingsRepository = (application as TodosianApplication).appSettingsRepository
        val focusPinStore = (application as TodosianApplication).focusPinStore
        val initialAddTaskTick = addTaskSignal.value
        setContent {
            val settings = appSettingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = com.mdtodo.data.settings.AppSettings(),
            ).value

            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            TodosianTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColorEnabled,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TodosianApp(
                        fileRepository = fileRepository,
                        appSettingsRepository = appSettingsRepository,
                        preferencesManager = preferencesManager,
                        focusPinStore = focusPinStore,
                        addTaskSignal = addTaskSignal,
                        initialAddTaskTick = initialAddTaskTick,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_ADD_TASK, false) == true) {
            addTaskSignal.value = addTaskSignal.value + 1L
            intent.removeExtra(EXTRA_OPEN_ADD_TASK)
        }
    }


    companion object {
        const val EXTRA_OPEN_ADD_TASK = "open_add_task"
    }
}
