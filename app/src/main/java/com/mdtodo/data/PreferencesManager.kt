package com.mdtodo.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

import com.mdtodo.BuildConfig

class PreferencesManager(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFolderUri(): Uri? {
        val raw = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        return runCatching { raw.toUri() }.getOrNull()
    }

    fun saveFolderUri(uri: Uri) {
        if (prefs.getInt(KEY_LAST_SEEN_VERSION_CODE, 0) == 0) {
            prefs.edit {
                putString(KEY_FOLDER_URI, uri.toString())
                putInt(KEY_LAST_SEEN_VERSION_CODE, BuildConfig.VERSION_CODE)
            }
        } else {
            prefs.edit {
                putString(KEY_FOLDER_URI, uri.toString())
            }
        }
    }

    fun clearFolderUri() {
        prefs.edit { remove(KEY_FOLDER_URI) }
    }

    fun getLastSeenVersionCode(): Int {
        return prefs.getInt(KEY_LAST_SEEN_VERSION_CODE, 0)
    }

    fun saveLastSeenVersionCode(versionCode: Int) {
        prefs.edit { putInt(KEY_LAST_SEEN_VERSION_CODE, versionCode) }
    }

    // ===== 关于每日笔记 =====
    // 原先这里有6 个模板/开关相关方法（getDailyTemplate / setAutoCreateTime 等）。
    // 每日笔记功能（含 DailyNoteWorker 与设置 UI）已按用户决定暂时移除，
    // 这些方法一并删除避免留死代码。等父子树完成后会重新实现，
    // 届时仍沿用 SharedPreferences（不引入 DataStore——模板只是一条字符串，
    // 异步流优势在此毫无价值，反而要改多处现有调用点）。

    companion object {
        private const val PREFS_NAME = "mdtodo_prefs"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
    }
}
