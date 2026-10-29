package com.mdtodo.data

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.mdtodo.data.model.Category
import com.mdtodo.data.model.Todo
import com.mdtodo.data.model.TodoSearchHit
import com.mdtodo.utils.MarkdownParser
import com.mdtodo.utils.TextPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.mdtodo.ui.widget.TodosianWidget
import androidx.glance.appwidget.updateAll
import java.time.LocalDate

interface FileRepository {
    fun getFolderUri(): Uri?

    fun clearFolderUri()

    suspend fun persistFolderUri(uri: Uri): Result<Unit>

    suspend fun getCategories(): Result<List<Category>>

    /** 全库搜索任务：关键字 / `#标签` / 起记日期，口径由搜索词自己决定 */
    suspend fun searchTodos(query: String): Result<List<TodoSearchHit>>

    suspend fun readLines(uri: Uri): Result<List<String>>

    suspend fun getDisplayName(uri: Uri): Result<String>

    suspend fun getFolderDisplayName(folderUri: Uri): Result<String>

    suspend fun writeLines(uri: Uri, lines: List<String>): Result<Unit>
    
    suspend fun updateLines(uri: Uri, transform: (List<String>) -> List<String>?): Result<Unit>

    suspend fun createCategory(folderUri: Uri, name: String): Result<Uri>

    suspend fun renameCategory(categoryUri: Uri, newName: String): Result<Unit>

    suspend fun deleteCategory(categoryUri: Uri): Result<Unit>

    suspend fun moveTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int): Result<Unit>

    suspend fun copyTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int): Result<Unit>

    fun hasPersistedReadWritePermission(uri: Uri): Boolean

    suspend fun countMarkdownFiles(folderUri: Uri): Result<Int>

    fun observeDocumentChanges(documentUri: Uri): Flow<Unit>

    fun observeMarkdownFilesChanges(folderUri: Uri): Flow<Unit>
}

class SafFileRepository(
    private val appContext: Context,
    private val preferencesManager: PreferencesManager,
) : FileRepository {

    private val widgetUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    
    @Volatile
    private var widgetUpdateJob: Job? = null

    /**
     * [ANR 实验开关] 临时关闭小组件刷新。
     *
     * 背景（2026-10-17 排查）：
     * - scheduleWidgetUpdate 由 6 处数据写入路径调用，全部跑在 Dispatchers.IO（后台线程）
     * - Glance 的 updateAllWidgets 会在后台线程操作 RemoteViews/AppWidgetManager，
     *   属于 Android 官方文档所说的「View Threading Violation」
     * - 该违规会触发 Sync Barrier Leak：UI 线程的 MessageQueue 被泄漏的同步屏障永久阻塞，
     *   主线程进入 idle 状态（表现为 nativePollOnce），任何输入都无法处理 → ANR
     * - Android 16 及以下无法从系统侧修复（17 才修），只能靠 App 自己不违规
     *
     * 与本项目症状的吻合点：
     * - 主线程 idle（S 休眠）、RenderThread 却 100%
     * - 「进子界面→返回」时必现（返回需重绘，但屏障已泄漏）
     * - 改 UI 组件代码全部无效（问题在数据层的线程违规）
     *
     * 验证完毕后的处置：要么把刷新切到主线程，要么按「自用单机不需要小组件」删除。
     */
    private val widgetUpdateEnabled = true

    private fun scheduleWidgetUpdate() {
        if (!widgetUpdateEnabled) return
        widgetUpdateJob?.cancel()
        widgetUpdateJob = widgetUpdateScope.launch {
            delay(WIDGET_UPDATE_DEBOUNCE_MS)
            refreshTodosianWidget()
        }
    }


    private fun refreshTodosianWidget() {
        widgetUpdateScope.launch {
            runCatching {
                Log.d(TAG, "Requesting Glance widget updateAll")
                com.mdtodo.ui.widget.WidgetUpdater.updateAllWidgets(appContext)
            }.onFailure { Log.e(TAG, "Widget refresh failed", it) }
        }
    }

    companion object {
        private const val WIDGET_UPDATE_DEBOUNCE_MS = 50L
        private const val TAG = "SafFileRepository"

        /** 递归扫描文件夹的深度上限，防止同步工具生成的无底洞目录拖垮首页刷新 */
        private const val MAX_FOLDER_DEPTH = 3

        /** 一次搜索最多返回的结果条数 */
        private const val SEARCH_RESULT_LIMIT = 100

        /** 任务文本里的 Obsidian 标签 */
        private val TAG_REGEX = Regex("""#[^\s#*]+""")

        /** 搜索词看起来像日期：10-24 / 10/24 / 10.24 */
        private val DATE_QUERY_REGEX = Regex("""^(\d{1,2})[-/.](\d{1,2})$""")
    }

    override fun getFolderUri(): Uri? = preferencesManager.getFolderUri()

    override fun clearFolderUri() {
        preferencesManager.clearFolderUri()
        scheduleWidgetUpdate()
    }

    override suspend fun persistFolderUri(uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                appContext.contentResolver.takePersistableUriPermission(uri, flags)
                preferencesManager.saveFolderUri(uri)
            }.onSuccess {
                scheduleWidgetUpdate()
            }
        }
    }

    override suspend fun getCategories(): Result<List<Category>> {
        val folderUri = getFolderUri() ?: return Result.success(emptyList())
        return withContext(Dispatchers.IO) {
            runCatching {
                val root = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                // 注意别写成 val collected = ...：块的最后一句如果是赋值语句，
                // runCatching 的返回类型会变成 Unit，整个函数编译不过。
                collectMarkdownFiles(root)
                    .map { (file, folderPath) ->
                        val name = file.name ?: ""
                        val displayName = name.removeSuffix(".md")
                        val lines = readLinesInternal(file.uri)
                        val todos = MarkdownParser.parse(lines)
                        val doneCount = todos.count { it.isDone }
                        val today = LocalDate.now().toString()
                        val activeTodos = todos.filter { !it.isDone }
                        val dueTodayCount = activeTodos.count { it.dueDate == today }
                        val overdueCount = activeTodos.count { todo ->
                            todo.dueDate?.let { it < today } == true
                        }
                        Category(
                            displayName = displayName,
                            uri = file.uri,
                            todoCount = todos.size,
                            doneCount = doneCount,
                            dueTodayCount = dueTodayCount,
                            overdueCount = overdueCount,
                            previewLines = activeTodos
                                .take(3)
                                .map { TextPreview.clean(it.text) }
                                .filter { it.isNotBlank() },
                            folderPath = folderPath,
                        )
                    }
                    // MD Todo：不含任何待办框的 md 文件不显示为分类卡片。
                    // 需求原话「读取的文件夹的 md 文件内没有待办框就不要显示出来，也就是说 0/0 不显示」。
                    // 用户用它同步整个笔记库（Obsidian vault），里面大量是纯笔记/日记，
                    // 若都显示成 0/0 卡片，首页会变成垃圾场。
                    //
                    // 注意：只是不生成卡片，文件本身完全不动——绝不能删用户的笔记。
                    .filter { it.todoCount > 0 }
                    .sortedBy { it.displayName.lowercase() }
            }
        }
    }

    override suspend fun searchTodos(query: String): Result<List<TodoSearchHit>> {
        val folderUri = getFolderUri() ?: return Result.success(emptyList())
        // 归一化：中文键盘打出来的 `10－24`（全角减号）、`１０－２４`（全角数字）
        // 必须折成半角才能和任务原文里的 `10-24` 对上。
        // 这不是洁癖——用户日常就是中文输入法，全角符号是常态。
        val keyword = normalizeQuery(query)
        if (keyword.isEmpty()) return Result.success(emptyList())

        return withContext(Dispatchers.IO) {
            runCatching {
                val root = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                val hits = mutableListOf<TodoSearchHit>()
                collectMarkdownFiles(root).forEach { (file, _) ->
                    val categoryName = (file.name ?: "").removeSuffix(".md")
                    val todos = MarkdownParser.parse(readLinesInternal(file.uri))
                    todos.forEach { todo ->
                        if (matchesQuery(todo, keyword)) {
                            hits += TodoSearchHit(
                                categoryName = categoryName,
                                categoryUri = file.uri,
                                todo = todo,
                            )
                        }
                    }
                }
                // 结果上限：一次搜索塞几百条进 LazyColumn 没意义，
                // 也挡住用户在搜索框里乱敲单字时把整个库刷出来。
                hits.take(SEARCH_RESULT_LIMIT)
            }
        }
    }

    /**
     * 一条任务是否命中搜索词。
     *
     * 三种口径（用户要的「标签、时间、关键字」）：
     * - 以 `#` 开头 → 只按标签匹配
     * - 形如 `10-24` / `10/24` → 按起记日期匹配
     * - 其他 → 在整个任务文本里做不区分大小写的子串匹配
     *
     * 之所以让搜索词自己决定口径，而不是再加三个筛选器：
     * 用户在搜索框里打什么，意图其实很明显——打 `#学习` 就是想找标签，
     * 打 `10-24` 就是想找那天记的事。多一层筛选反而要多点几下。
     */
    private fun matchesQuery(todo: Todo, keyword: String): Boolean {
        if (keyword.startsWith("#")) {
            val tag = keyword.removePrefix("#")
            return TAG_REGEX.findAll(todo.text).any { it.value.removePrefix("#") == tag }
        }

        val dateMatch = DATE_QUERY_REGEX.matchEntire(keyword)
        if (dateMatch != null) {
            // 归一成 MM-DD 再比：用户可能打 10/24 或 10.24
            val normalized = dateMatch.groupValues[1].padStart(2, '0') + "-" +
                dateMatch.groupValues[2].padStart(2, '0')
            return todo.text.contains(normalized)
        }

        return todo.text.contains(keyword, ignoreCase = true)
    }

    /** 全角字符折成半角，主要用于搜索词。 */
    private fun normalizeQuery(raw: String): String {
        val trimmed = raw.trim()
        return buildString(trimmed.length) {
            trimmed.forEach { ch ->
                when {
                    // 全角 ASCII 区（！到 ～）整体平移 0xFEE0
                    ch.code in 0xFF01..0xFF5E -> append((ch.code - 0xFEE0).toChar())
                    ch == '　' -> append(' ')
                    else -> append(ch)
                }
            }
        }.trim()
    }

    /** 递归收集 md 文件，附带它所在的相对文件夹路径（根目录为 ""）。 */
    private fun collectMarkdownFiles(root: DocumentFile): List<Pair<DocumentFile, String>> {
        val collected = mutableListOf<Pair<DocumentFile, String>>()

        fun walk(dir: DocumentFile, path: String, depth: Int) {
            if (depth > MAX_FOLDER_DEPTH) return
            dir.listFiles().forEach { item ->
                val name = item.name.orEmpty()
                when {
                    item.isDirectory -> {
                        // 跳过隐藏目录（.obsidian / .trash / .git 之类），
                        // 里面没有用户想看的待办，扫了只是白耗 IO
                        if (!name.startsWith(".")) {
                            val childPath = if (path.isEmpty()) name else "$path/$name"
                            walk(item, childPath, depth + 1)
                        }
                    }

                    item.isFile -> {
                        if (name.endsWith(".md", ignoreCase = true) &&
                            !name.contains("sync-conflict", ignoreCase = true)
                        ) {
                            collected += item to path
                        }
                    }
                }
            }
        }

        walk(root, "", 0)
        return collected
    }

    override suspend fun readLines(uri: Uri): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock { readLinesInternal(uri) }
            }
        }
    }

    override suspend fun getDisplayName(uri: Uri): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = DocumentFile.fromSingleUri(appContext, uri)
                    ?: throw IllegalStateException("Invalid file URI")
                file.name ?: ""
            }
        }
    }

    override suspend fun getFolderDisplayName(folderUri: Uri): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val folder = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")
                folder.name
                    ?: folderUri.lastPathSegment
                    ?: folderUri.toString()
            }
        }
    }

    override suspend fun writeLines(uri: Uri, lines: List<String>): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock { writeLinesInternal(uri, lines) }
            }
        }
    }

    override suspend fun updateLines(uri: Uri, transform: (List<String>) -> List<String>?): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock {
                    val currentLines = readLinesInternal(uri)
                    val newLines = transform(currentLines) ?: return@withLock
                    writeLinesInternal(uri, newLines)
                }
            }
        }
    }

    override suspend fun createCategory(folderUri: Uri, name: String): Result<Uri> {
        val cleaned = name.trim().trim('/')
        if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("Empty name"))

        return withContext(Dispatchers.IO) {
            runCatching {
                val rootFolder = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                // 支持 `学习/高数` 这种带路径的名字：中间各段当文件夹建，
                // 最后一段才是 md 文件名。这样用户在新建对话框里打一个斜杠
                // 就能一步建出子文件夹，不用先去别的地方建目录。
                val segments = cleaned.split('/').filter { it.isNotBlank() }
                require(segments.isNotEmpty()) { "Empty name" }

                var dir: DocumentFile = rootFolder
                segments.dropLast(1).forEach { segment ->
                    val existing = dir.findFile(segment)?.takeIf { it.isDirectory }
                    dir = existing
                        ?: dir.createDirectory(segment)
                        ?: throw IllegalStateException("Unable to create folder: $segment")
                }

                val leaf = segments.last()
                val fileName = if (leaf.endsWith(".md", ignoreCase = true)) leaf else "$leaf.md"
                val created = dir.createFile("text/markdown", fileName)
                    ?: throw IllegalStateException("Unable to create file")

                scheduleWidgetUpdate()
                created.uri
            }
        }
    }

    override suspend fun renameCategory(categoryUri: Uri, newName: String): Result<Unit> {
        val cleaned = newName.trim()
        if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("Empty name"))

        return withContext(Dispatchers.IO) {
            runCatching {
                val fileName = cleaned
                    .removeSuffix(".md")
                    .removeSuffix(".MD")
                    .removeSuffix(".Md")
                    .removeSuffix(".mD")
                    .trim()
                    .let { base -> if (base.endsWith(".md", ignoreCase = true)) base else "$base.md" }

                val renamed = DocumentsContract.renameDocument(appContext.contentResolver, categoryUri, fileName)
                if (renamed == null) throw IllegalStateException("Unable to rename document")
                scheduleWidgetUpdate()
            }
        }
    }

    override suspend fun deleteCategory(categoryUri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = DocumentFile.fromSingleUri(appContext, categoryUri)
                    ?: throw IllegalStateException("Invalid file URI")
                val ok = file.delete()
                if (!ok) throw IllegalStateException("Unable to delete file")
                scheduleWidgetUpdate()
            }
        }
    }

    override suspend fun moveTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val sourceLines = readLinesInternal(sourceUri)
                val targetLines = readLinesInternal(targetUri)
                val updated = MarkdownParser.tryMoveTodoLine(
                    sourceLines = sourceLines,
                    lineIndex = lineIndex,
                    targetLines = targetLines,
                ) ?: throw IllegalStateException("Invalid todo line")

                writeLinesInternal(sourceUri, updated.first)
                writeLinesInternal(targetUri, updated.second)
            }
        }
    }

    override suspend fun copyTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val sourceLines = readLinesInternal(sourceUri)
                val targetLines = readLinesInternal(targetUri)
                val updated = MarkdownParser.tryCopyTodoLine(
                    sourceLines = sourceLines,
                    lineIndex = lineIndex,
                    targetLines = targetLines,
                ) ?: throw IllegalStateException("Invalid todo line")

                writeLinesInternal(targetUri, updated.second)
            }
        }
    }

    override fun hasPersistedReadWritePermission(uri: Uri): Boolean {
        val perms = appContext.contentResolver.persistedUriPermissions
        val perm = perms.firstOrNull { it.uri == uri } ?: return false
        return perm.isReadPermission && perm.isWritePermission
    }

    override suspend fun countMarkdownFiles(folderUri: Uri): Result<Int> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val folder = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                folder.listFiles().count { file ->
                    if (!file.isFile) return@count false
                    val name = file.name.orEmpty()
                    name.endsWith(".md", ignoreCase = true) && !name.contains("sync-conflict", ignoreCase = true)
                }
            }
        }
    }

    override fun observeDocumentChanges(documentUri: Uri): Flow<Unit> {
        return observeUrisChanges(
            uris = listOf(documentUri),
            notifyDescendants = false,
        )
    }

    override fun observeMarkdownFilesChanges(folderUri: Uri): Flow<Unit> {
        val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeDocId)

        return callbackFlow {
            val resolver = appContext.contentResolver

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var updateJob = scope.launch { }
            updateJob.cancel()

            var observedUris: List<Pair<Uri, Boolean>> = emptyList()

            lateinit var observer: ContentObserver

            fun currentMarkdownUris(): List<Pair<Uri, Boolean>> {
                val folder = DocumentFile.fromTreeUri(appContext, folderUri) ?: return emptyList()
                val fileUris = folder.listFiles()
                    .asSequence()
                    .filter { it.isFile }
                    .filter {
                        val name = it.name.orEmpty()
                        name.endsWith(".md", ignoreCase = true) && !name.contains("sync-conflict", ignoreCase = true)
                    }
                    .map { it.uri }
                    .toList()

                val uris = mutableListOf<Pair<Uri, Boolean>>()
                // structural changes
                uris.add(folderUri to true)
                uris.add(childrenUri to true)
                // content changes
                fileUris.forEach { uris.add(it to false) }
                return uris
            }

            fun scheduleUpdate() {
                updateJob.cancel()
                updateJob = scope.launch {
                    delay(200)
                    val next = runCatching { currentMarkdownUris() }.getOrElse { emptyList() }
                    if (next == observedUris) return@launch

                    runCatching { resolver.unregisterContentObserver(observer) }
                    observedUris = next
                    runCatching {
                        observedUris.forEach { (uri, notifyDescendants) ->
                            resolver.registerContentObserver(uri, notifyDescendants, observer)
                        }
                    }.onFailure { t ->
                        close(t)
                    }
                }
            }

            observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                    trySend(Unit)
                    val changed = changedUri?.toString().orEmpty()
                    val shouldUpdate = changedUri == null ||
                        changedUri == folderUri ||
                        changedUri == childrenUri ||
                        (changed.isNotEmpty() && changed.startsWith(childrenUri.toString()))
                    if (shouldUpdate) scheduleUpdate()
                }
            }

            // initial register
            observedUris = listOf(
                folderUri to true,
                childrenUri to true,
            )
            runCatching {
                observedUris.forEach { (uri, notifyDescendants) ->
                    resolver.registerContentObserver(uri, notifyDescendants, observer)
                }
            }.onFailure { t ->
                close(t)
                return@callbackFlow
            }

            scheduleUpdate()

            awaitClose {
                updateJob.cancel()
                scope.cancel()
                runCatching { resolver.unregisterContentObserver(observer) }
            }
        }.conflate()
    }

    private fun observeUrisChanges(
        uris: List<Uri>,
        notifyDescendants: Boolean,
    ): Flow<Unit> {
        return callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }

                override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                    trySend(Unit)
                }
            }

            val resolver = appContext.contentResolver
            runCatching {
                uris.forEach { uri ->
                    resolver.registerContentObserver(uri, notifyDescendants, observer)
                }
            }.onFailure { t ->
                close(t)
                return@callbackFlow
            }

            awaitClose {
                runCatching { resolver.unregisterContentObserver(observer) }
            }
        }.conflate()
    }

    /**
     * M1.5：读取时统一剥离行尾 CR 与 UTF-8 BOM。
     *
     * 为什么在这里治本而不是在解析器里逐处处理：
     * - `bufferedReader().readLines()` 会保留行尾 `\r`，导致 MarkdownParser 的
     *   `(.*)$` 类正则匹配失败（Java 正则的 `.` 不匹配行终止符）
     * - 而在解析器内部处理时，`trimEnd()` 会在写回时把 CR 一并吃掉，
     *   使整份文件从 CRLF 变LF → Syncthing 判为「两台设备都改过」→ 产生冲突副本
     *
     * 在此统一剥离，解析层拿到的永远是干净行，写回也统一输出 `\n`，
     * 语义一致且不会污染其他设备的同步。
     */
    private fun readLinesInternal(uri: Uri): List<String> {
        appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val lines = reader.readLines()
            // BOM 只可能出现在首行开头（U+FEFF）
            if (lines.isNotEmpty() && lines[0].startsWith('﻿')) {
                return lines.mapIndexed { index, line ->
                    if (index == 0) line.removePrefix("﻿") else line
                }
            }
            return lines
        } ?: throw IllegalStateException("Unable to open input stream")
    }

    /**
     * M1.5：写回时显式使用 `\n`，不依赖 `writer.newLine()`。
     *
     * `newLine()` 会写入平台分隔符，在某些 DocumentsProvider 实现上产生 CRLF，
     * 造成「读进来 LF、写出去 CRLF」的反复抖动。
     * 显式 `\n` 是跨平台一致的最小公倍数，Obsidian/VSCode/手机编辑器都能正常识别。
     */
    private fun writeLinesInternal(uri: Uri, lines: List<String>) {
        // 🔴 M1.5 真机验证修正：
        // 原本硬编码 writer.write("\n")，导致勾选一次就把整份 CRLF 文件转成 LF
        // （实测：219B/CRLF=13 → 205B/CRLF=0）。
        // 后果：Syncthing 会判定「两台设备都改过整个文件」→ 生成冲突副本，
        //      用户会看到重复文件，且笔记本里的原始笔记被污染。
        //
        // 正确做法：写回前探测原文件用的是什么换行符，原样还原。
        val newline = detectNewline(uri)
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            runCatching { (out as? java.io.FileOutputStream)?.channel?.truncate(0) }
            out.bufferedWriter().use { writer ->
                lines.forEachIndexed { index, line ->
                    if (index > 0) writer.write(newline)
                    writer.write(line)
                }
            }
        } ?: throw IllegalStateException("Unable to open output stream")

        try {
            appContext.contentResolver.notifyChange(uri, null)
        } catch (e: Exception) {
            // Ignore
        }
        scheduleWidgetUpdate()
    }

    /**
     * 探测文件当前使用的换行符，写回时原样还原。
     *
     * 为什么必须做（真机实测教训）：
     * 用户在 Windows 上用 Obsidian / VSCode 写的 .md 是 CRLF，
     * Syncthing 同步到手机后App 若写回 LF，整个文件会被判定为「内容全变」，
     * Syncthing 认为两台设备都改过 → 生成 `xxx sync-conflict.md` 副本。
     * 用户会看到重复文件，且原始笔记的换行符被改坏。
     *
     * 实现要点：
     * - 必须读**原始字节**，不能用 readLines()（它已经剥掉了换行信息）
     * - 只扫描前若干字节即可判断，不需要整文件读入（大文件友好）
     * - 文件为空/读取失败时默认 `\n`（新建文件用 LF 是安全的）
     * - 混合换行时按出现次数多的那个为准
     */
    private fun detectNewline(uri: Uri): String {
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(8192)
                val read = input.read(buf)
                if (read <= 0) return@runCatching "\n"

                var crlf = 0
                var lfOnly = 0
                for (i in 0 until read) {
                    if (buf[i] == '\n'.code.toByte()) {
                        if (i > 0 && buf[i - 1] == '\r'.code.toByte()) crlf++ else lfOnly++
                    }
                }
                // CRLF 明显占优才判定为 CRLF；否则用 LF（含全 LF 与新建空文件）
                if (crlf > lfOnly) "\r\n" else "\n"
            } ?: "\n"
        }.getOrDefault("\n")
    }
}
