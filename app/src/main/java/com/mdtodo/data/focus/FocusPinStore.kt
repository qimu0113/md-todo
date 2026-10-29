package com.mdtodo.data.focus

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 「手动加入今日聚焦」的本地名单。
 *
 * ## 为什么存在本地而不是写进 md
 * 用户明确：聚焦只是这个 App 内部的概念，Obsidian 那边不需要看到，
 * 也不希望为了一个视图开关去污染笔记正文（写 `#聚焦` 标签会让文件在
 * Syncthing 上来回变动，且用户并不想在 Obsidian 里检索它）。
 * 所以存 SharedPreferences，键是不带前缀的 todo id。
 *
 * ## id 会漂移
 * todo id 由 `StableTodoId`（父链 + 文本 + 序号 的 SHA-256）算出来。
 * 用户改了任务文字 → id 变了 → 这条 pin 自然失效。
 * 这是「不往文件里写标识」的必然代价，接受；表现为该任务不再出现在聚焦里，
 * 用户再手动加一次即可。不做模糊重挂（文本相似度匹配会误挂到别的任务上）。
 */
interface FocusPinStore {
    /** 当前被手动 pin 的 todo id 集合。订阅后会持续收到变更。 */
    val pinnedIds: Flow<Set<String>>

    fun pin(todoId: String)
    fun unpin(todoId: String)
    fun toggle(todoId: String): Boolean
    suspend fun currentIds(): Set<String>
}

class SharedPrefsFocusPinStore(context: Context) : FocusPinStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val pinnedIds: Flow<Set<String>> = callbackFlow {
        fun sendCurrent() {
            trySend(readIds()).isSuccess
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PINNED) sendCurrent()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        sendCurrent()
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    override fun pin(todoId: String) {
        prefs.edit { putStringSet(KEY_PINNED, readIds() + todoId) }
    }

    override fun unpin(todoId: String) {
        prefs.edit { putStringSet(KEY_PINNED, readIds() - todoId) }
    }

    override fun toggle(todoId: String): Boolean {
        val now = readIds()
        val added = todoId !in now
        prefs.edit {
            putStringSet(KEY_PINNED, if (added) now + todoId else now - todoId)
        }
        return added
    }

    override suspend fun currentIds(): Set<String> = readIds()

    private fun readIds(): Set<String> =
        // 必须复制一份：getStringSet 返回的是 prefs 内部实例，
        // 直接改它会让 SharedPreferences 处于不一致状态（官方文档明确警告）。
        prefs.getStringSet(KEY_PINNED, emptySet())?.toHashSet() ?: emptySet()

    private companion object {
        private const val PREFS_NAME = "mdtodo_focus_pins"
        private const val KEY_PINNED = "pinned_todo_ids"
    }
}
