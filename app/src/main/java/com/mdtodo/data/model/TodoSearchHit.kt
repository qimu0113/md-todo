package com.mdtodo.data.model

import android.net.Uri

/**
 * 一条搜索命中记录。
 *
 * 带上 [categoryName] / [categoryUri] 是为了让搜索结果能说清
 * 「这条待办在哪个分类里」，并且点进去能直接跳到那个分类。
 */
data class TodoSearchHit(
    val categoryName: String,
    val categoryUri: Uri,
    val todo: Todo,
)
