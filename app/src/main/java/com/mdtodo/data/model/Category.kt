package com.mdtodo.data.model

import android.net.Uri

data class Category(
    val displayName: String,
    val uri: Uri,
    val todoCount: Int,
    val doneCount: Int,
    val dueTodayCount: Int,
    val overdueCount: Int,
    /**
     * 前几条未完成任务的可读文本，供首页卡片/行做内容预览。
     *
     * 移植 OPPO 便签的宫格观感：卡片里要有内容摘要，卡片才会高低错落，
     * 瀑布流才有意义。数据来源是 [com.mdtodo.data.FileRepository.getCategories]
     * 里**本来就已经读出来的** todos，不产生额外文件 IO。
     */
    val previewLines: List<String> = emptyList(),
    /**
     * 文件所在的相对文件夹路径。根目录下为空字符串，子文件夹是 `"学习"`、
     * `"学习/高数"` 这种以 `/` 分隔的形式。
     *
     * 首页的分类选择栏就是按这个字段切分：chip 显示当前层的文件夹，
     * 网格只显示 [folderPath] 等于当前路径的文件。
     */
    val folderPath: String = "",
)
