package com.mdtodo.data.model

/**
 * Stable identity + parent/child relations for a parsed Markdown task tree.
 *
 * Copied/adapted from `com.isotjs.todosian.data.model.Todo` (Todosian).
 * Changes vs. the original flat model:
 *   1. `id` is DETERMINISTIC (see [StableTodoId]) instead of `UUID.randomUUID()`.
 *      The original regenerates UUIDs on every parse, so it can never be used as a
 *      Compose `key` or a selection anchor.
 *   2. Adds `parentId` / `depth` / `children` (the original has none).
 *
 * Everything else (metadata fields, `indentLevel` semantics) is intentionally kept
 * byte-compatible with the original so `MarkdownParser` can keep returning `Todo`
 * and existing call sites keep working.
 */
data class TodoNode(
    /** Deterministic id. Stable across re-parses of an unchanged file. */
    val id: String,

    /** Parent's [id], or `null` for a root task. */
    val parentId: String?,

    val text: String,
    val isDone: Boolean,

    /** 0-based index into the source `lines` list. NOT stable — always re-resolve via [id]. */
    val lineIndex: Int,

    /** Same metric as the original `MarkdownParser.indentLevel()`. Kept for compatibility. */
    val indentLevel: Int = 0,

    /** 0 for root tasks, parent.depth + 1 for children. Authoritative for tree shape. */
    val depth: Int = 0,

    // Obsidian Tasks plugin metadata (Tasks Emoji Format) — identical to original `Todo`.
    val dueDate: String? = null,
    val startDate: String? = null,
    val scheduledDate: String? = null,
    val completionDate: String? = null,
    val createdDate: String? = null,
    val priority: String? = null,
    val recurrence: String? = null,

    val children: List<TodoNode> = emptyList(),
) {
    /** True when this node has at least one direct child. */
    val hasChildren: Boolean get() = children.isNotEmpty()

    /** True when this node has children AND every direct child is done. */
    val allChildrenDone: Boolean
        get() = children.isNotEmpty() && children.all { it.isDone }

    /** Every descendant (not just direct children) is done. Used by the recursive auto-check rule. */
    val allDescendantsDone: Boolean
        get() = descendants().all { it.isDone }

    /** This node + all descendants, depth-first, children before their own subtree ordering. */
    fun descendants(): List<TodoNode> = children.flatMap { listOf(it) + it.descendants() }

    /** The subtree rooted here, including this node. */
    fun subtree(): List<TodoNode> = listOf(this) + descendants()
}
