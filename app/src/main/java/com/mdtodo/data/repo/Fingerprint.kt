package com.mdtodo.data.repo

import java.security.MessageDigest

/**
 * Optimistic concurrency token for a Markdown file's content.
 *
 * ## The problem this solves
 * `FileRepository.updateLines(uri) { lines -> ... }` re-reads the file *inside* a Mutex and
 * hands the fresh content to the transform. That Mutex only serialises writes **within this
 * process**. It does nothing about Syncthing, which rewrites the same file from another
 * device at any moment. So between the moment the UI rendered a task list and the moment the
 * user's tap reaches `updateLines`, the file may have changed underneath.
 *
 * The original `MarkdownParser.toggleLine(lines, lineIndex, ...)` writes by `lineIndex`.
 * If a line was inserted above, that index now points at a *different* task — the classic
 * lost-update. Silently ticking the wrong task is worse than failing.
 *
 * ## The scheme
 * 1. The UI keeps the [Fingerprint] of the content it rendered.
 * 2. On tap it hands that fingerprint plus the **stable task id** to the write layer.
 * 3. Inside `updateLines`, we fingerprint the freshly-read lines.
 *    - Same fingerprint → nobody else touched it; apply by id, safe.
 *    - Different fingerprint → **rebase**: do not reject the write. Re-resolve the id in the
 *      fresh content and apply there. Line numbers may have moved, but the id still points at
 *      the same task, so the intent survives.
 * 4. If the id cannot be re-resolved (the task was deleted by the other device), we return
 *    [ConflictOutcome.TargetMissing] and write nothing. Rebasing onto a deleted task is not
 *    possible and guessing is worse than telling the user.
 *
 * ## Why not simply compare and reject?
 * Rejecting on any mismatch would make Syncthing's routine background syncs look like errors
 * to the user — "someone else edited this file" every few minutes. Rebasing turns the common
 * case (file appended to, our task untouched) into a silent success. That is the behaviour
 * users expect from a local-first Markdown editor.
 */
data class Fingerprint(val hash: String, val lineCount: Int) {

    companion object {
        /**
         * Content hash of [lines].
         *
         * Uses `\u0000` as the joiner and length-prefixes each line so that the classic
         * concatenation ambiguity cannot produce a collision:
         * `["ab", "c"]` and `["a", "bc"]` must not hash the same. With an explicit `\n`
         * joiner alone, a file whose lines are `["ab","c"]` and one with `["a","bc"]` would
         * both join to `"ab\nc"` — that is a real, reachable collision in a todo list
         * (`- [ ] a` + `b` vs `- [ ] a` + `b`... not reachable here because of the regex,
         * but the joiner costs nothing to make unambiguous).
         */
        fun of(lines: List<String>): Fingerprint {
            val sb = StringBuilder()
            for (line in lines) {
                sb.append(line.length).append(':').append(line).append('\n')
            }
            return Fingerprint(sha256Hex(sb.toString()), lines.size)
        }

        private fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append("0123456789abcdef"[v ushr 4])
                sb.append("0123456789abcdef"[v and 0x0F])
            }
            return sb.toString()
        }
    }
}

/** Outcome of an optimistic-locked write. */
sealed interface ConflictOutcome {

    /** Content was unchanged since the UI rendered it; the write applied directly. */
    data class Clean(val lines: List<String>) : ConflictOutcome

    /**
     * Content changed under us, but the task id still resolved in the fresh content.
     * The edit was rebased onto the new lines and [lines] is the result to write.
     */
    data class Rebased(val lines: List<String>) : ConflictOutcome

    /**
     * The task no longer exists in the fresh content — deleted on another device, or the
     * id changed because its text was edited there. Nothing was written. The UI should
     * re-read and surface "this task was changed elsewhere".
     */
    data class TargetMissing(val todoId: String) : ConflictOutcome
}
