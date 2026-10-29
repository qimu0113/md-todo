package com.mdtodo

import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mdtodo.data.FileRepository
import com.mdtodo.data.PreferencesManager
import com.mdtodo.data.focus.FocusPinStore
import com.mdtodo.data.settings.AppSettingsRepository
import com.mdtodo.ui.addtask.AddTaskScreen
import com.mdtodo.ui.category.CategoryScreen
import com.mdtodo.ui.dailyfocus.DailyFocusScreen
import com.mdtodo.ui.home.HomeScreen
import com.mdtodo.ui.onboarding.OnboardingScreen
import com.mdtodo.ui.search.SearchScreen
import com.mdtodo.ui.settings.SettingsScreen
import com.mdtodo.ui.transition.ContainerOriginStore
import com.mdtodo.ui.transition.ContainerTransformPage
import kotlinx.coroutines.flow.StateFlow

@Composable
fun TodosianApp(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    preferencesManager: PreferencesManager,
    focusPinStore: FocusPinStore,
    addTaskSignal: StateFlow<Long>,
    initialAddTaskTick: Long,
) {
    val navController = rememberNavController()
    val addTaskTick by addTaskSignal.collectAsStateWithLifecycle(initialValue = initialAddTaskTick)
    val startDestination = remember(fileRepository, addTaskTick) {
        when {
            fileRepository.getFolderUri() == null -> Routes.Onboarding
            addTaskTick > 0L -> Routes.AddTask
            else -> Routes.Home
        }
    }

    LaunchedEffect(addTaskTick) {
        if (addTaskTick > 0L &&
            fileRepository.getFolderUri() != null &&
            navController.currentDestination?.route != Routes.AddTask
        ) {
            navController.navigate(Routes.AddTask) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        // MD Todo：二级界面转场（2026-10-28 第六版 —— 便签式水平视差）
        //
        // 前五版的共同毛病：只有「一个页面在动」。
        //   v1 全 None（ANR 排查残留）→ 硬切
        //   v2 两页等速横移 + ease-in-out → 像 PPT 切页，割裂
        //   v3 fade-through 缩放淡入淡出 → 太平，没有层级
        //   v4 predictive back 跟手 → 与 ColorOS hook 冲突，已废弃
        //   v5 垂直层叠 → 便签根本不是纵向推进
        //
        // 便签的关键不是「滑」，是**视差**：新页整屏推进（位移 1.0），
        // 旧页只退三分之一（位移 0.28）。两页速度不同，眼睛才读得出
        // 「新页在前、旧页在后」。之前几版让旧页整屏滑出，所以怎么调都像切页。
        //
        // 曲线用 spring 不用 tween：ColorOS 的转场是阻尼弹簧，
        // 收尾有一小段减速拖尾；换成线性/ease 立刻显得「僵」。
        // dampingRatio 0.9 允许极轻微回弹，对应便签停下时那一下「顿」。
        enterTransition = {
            slideInHorizontally(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
                initialOffsetX = { it },
            )
        },
        exitTransition = {
            // 进分类页时首页必须一直留在屏幕上：分类页是「从列表项长出来」，
            // 背景得能看见首页变暗。用 None 的话 NavHost 会立刻把首页从组合里摘掉，
            // 转场背景就变成一片空白的底色。
            //
            // 这里给一个 targetAlpha = 1f 的 fadeOut：视觉上什么都不做，
            // 但让它占住 360ms——比分类页自己 320ms 的展开动画略长，
            // 保证首页在整段转场期间都还在画。
            if (targetState.destination.route?.startsWith("category/") == true) {
                fadeOut(
                    animationSpec = tween(durationMillis = 340),
                    targetAlpha = 0.99f,
                )
            } else {
                slideOutHorizontally(
                    animationSpec = spring(dampingRatio = 1f, stiffness = 420f),
                    targetOffsetX = { -(it * FAR_PAGE_RETREAT).toInt() },
                )
            }
        },
        popEnterTransition = {
            if (initialState.destination.route?.startsWith("category/") == true) {
                EnterTransition.None
            } else {
                slideInHorizontally(
                    animationSpec = spring(dampingRatio = 1f, stiffness = 420f),
                    initialOffsetX = { -(it * FAR_PAGE_RETREAT).toInt() },
                )
            }
        },
        popExitTransition = {
            slideOutHorizontally(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
                targetOffsetX = { it },
            )
        },
    ) {
        composable(Routes.Onboarding) {
            OnboardingScreen(
                fileRepository = fileRepository,
                onFinished = {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.Home) { backStackEntry ->
            val refreshSignal = backStackEntry.savedStateHandle
                .getStateFlow(KEY_REFRESH_HOME, 0L)
                .collectAsStateWithLifecycle()
                .value

            HomeScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                preferencesManager = preferencesManager,
                focusPinStore = focusPinStore,
                onOpenSearch = {
                    navController.navigate(Routes.Search)
                },
                onOpenAddTask = {
                    navController.navigate(Routes.AddTask) {
                        launchSingleTop = true
                    }
                },
                refreshSignal = refreshSignal,
                onRequireOnboarding = {
                    navController.navigate(Routes.Onboarding) {
                        popUpTo(Routes.Home) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.Search) {
            SearchScreen(
                fileRepository = fileRepository,
                onOpenCategory = { uri ->
                    navController.navigate(Routes.category(uri))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.Settings) {
            SettingsScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                preferencesManager = preferencesManager,
                onBack = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        KEY_REFRESH_HOME,
                        System.currentTimeMillis(),
                    )
                    navController.popBackStack()
                },
                onRequireOnboarding = {
                    navController.navigate(Routes.Onboarding) {
                        popUpTo(Routes.Home) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.Category,
            arguments = listOf(
                navArgument(Routes.ARG_CATEGORY_URI) {
                    type = NavType.StringType
                },
                navArgument(Routes.ARG_CATEGORY_ADD) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
            // 分类页的视觉主体由 ContainerTransformPage 自己画，但这里不能真的用
            // EnterTransition.None —— AnimatedContent 的转场总时长是 max(enter, exit)，
            // enter 为 0 会让整段过渡瞬间结束，背景里的首页立刻被摘掉，
            // 「卡片从列表项长出来、背板变暗」就变成了「卡片长在一片空白上」。
            // 所以给它一个 initialAlpha = 0.99 的 fadeIn：肉眼看不出来，
            // 但占住 340ms，首页才能在整段动画里留在屏幕上。
            enterTransition = { fadeIn(animationSpec = tween(340), initialAlpha = 0.99f) },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString(Routes.ARG_CATEGORY_URI).orEmpty()
            val uri = encoded.toUri()
            val autoOpenAddTodo = backStackEntry.arguments?.getBoolean(Routes.ARG_CATEGORY_ADD) ?: false
            // 只消费一次：进入时记住起始矩形，退出时用同一个缩回去。
            // 用 backStackEntry 做 key，保证同一个分类页实例始终对应同一次点击。
            val origin = remember(backStackEntry) { ContainerOriginStore.consume() }
            ContainerTransformPage(
                origin = origin,
                onExitFinished = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        KEY_REFRESH_HOME,
                        System.currentTimeMillis(),
                    )
                    navController.popBackStack()
                },
            ) { requestBack ->
                CategoryScreen(
                    fileRepository = fileRepository,
                    appSettingsRepository = appSettingsRepository,
                    categoryUri = uri,
                    autoOpenAddTodo = autoOpenAddTodo,
                    onBack = requestBack,
                )
            }
        }

        composable(Routes.AddTask) {
            AddTaskScreen(
                fileRepository = fileRepository,
                onSelectCategory = { uri ->
                    // 选完分类就把「选分类」这一页从栈里摘掉：
                    // 否则从分类页返回会先落回一个已经用过的选择页，
                    // 用户得再点一次返回才回到首页。
                    navController.navigate(Routes.category(uri, addTodo = true)) {
                        popUpTo(Routes.AddTask) { inclusive = true }
                    }
                },
                onBack = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        KEY_REFRESH_HOME,
                        System.currentTimeMillis(),
                    )
                    navController.popBackStack()
                },
            )
        }

        composable(
            route = Routes.DailyFocus,
            arguments = listOf(
                navArgument(Routes.ARG_PICK) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { backStackEntry ->
            val pick = backStackEntry.arguments?.getBoolean(Routes.ARG_PICK) ?: false
            DailyFocusScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                focusPinStore = focusPinStore,
                openPickerOnStart = pick,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private const val KEY_REFRESH_HOME = "refresh_home"

/** 退出/归位时旧页只退这么多，剩下的位移差就是视差。 */
private const val FAR_PAGE_RETREAT = 0.28f

object Routes {
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Settings = "settings"
    const val AddTask = "add_task"
    const val Search = "search"

    const val ARG_PICK = "pick"
    const val DailyFocus = "daily_focus?$ARG_PICK={$ARG_PICK}"

    const val ARG_CATEGORY_URI = "categoryUri"
    const val ARG_CATEGORY_ADD = "add"
    const val Category = "category/{$ARG_CATEGORY_URI}?$ARG_CATEGORY_ADD={$ARG_CATEGORY_ADD}"

    fun dailyFocus(pick: Boolean = false): String = "daily_focus?$ARG_PICK=$pick"

    fun category(uri: Uri, addTodo: Boolean = false): String =
        "category/${Uri.encode(uri.toString())}?$ARG_CATEGORY_ADD=$addTodo"
}

