package com.mdtodo.data.repo

import com.mdtodo.data.parser.AutoCheck
import com.mdtodo.data.parser.AutoCheckOptions
import com.mdtodo.data.parser.MarkdownTreeParser
import com.mdtodo.data.parser.ToggleEngine
import java.time.LocalDate

/**
 * Minimal persistence contract. Deliberately identical in shape to the existing
 * `com.isotjs.todosian.data.FileRepository.updateLines`, so the SAF-backed implementation
 * can be reused as-is:
 *
 * ```
 * suspend fun updateLines(uri: Uri, transform: (List<String>) -> List<String>?): Result<Unit>
 * ```
 *
 * Returning `null` from [transform] means "write nothing" — that is the abort path we use
 * for conflicts.
 *
 * Declared here (rather than imported from the old package) so the data layer has no
 * dependency on Android or on the legacy module and stays unit-testable on the JVM.
 */
interface LineStore {
    suspend fun updateLines(uri: String, transform: (List<String>) -> List<String>?): Result<Unit>
}

/** Result of a toggle that also ran the auto-check. */
sealed interface ToggleResult {
    /** Write succeeded. [rebased] is true when the file had changed underneath us. */
    data class Success(val rebased: Boolean) : ToggleResult

    /** The task is gone from the file (deleted by a sync). Nothing was written. */
    data class Missing(val todoId: String) : ToggleResult

    /** Underlying IO failed. */
    data class Failed(val error: Throwable) : ToggleResult
}

/**
 * Applies a toggle **and** the parent auto-check inside a SINGLE atomic write.
 *
 * ## Why one `updateLines` call and not two
 * Ticking the last child must change two lines: the child's checkbox and the parent's.
 * Two separate `updateLines` calls would mean two reads and two writes:
 *   - write #1 ticks the child → the file is momentarily inconsistent
 *     (parent `[ ]`, all children `[x]`)
 *   - a Syncthing client watching the folder can observe and propagate that intermediate
 *     state, and on the *other* device the auto-check would fire from the half-written file
 *   - if the process dies between the two writes, the file is left permanently inconsistent
 *
 * Doing both rewrites inside one `transform` — which becomes one read and one write under
 * the repository's Mutex — makes the pair atomic with respect to both the filesystem
 * observer and process death. There is no instant at which the file is half-updated.
 */
class TodoRepository(private val store: LineStore) {

    /**
     * Toggles the task identified by [todoId], then re-evaluates every ancestor.
     *
     * @param expectedFingerprint the fingerprint of the content the UI rendered. Pass `null`
     *        to skip the conflict check (e.g. a first write into a file nobody else has open).
     * @return [ToggleResult]. On [ToggleResult.Missing] nothing was written.
     */
    suspend fun toggleWithAutoCheck(
        uri: String,
        todoId: String,
        expectedFingerprint: Fingerprint?,
        enableTasksPlugin: Boolean,
        options: AutoCheckOptions = AutoCheckOptions(),
        today: LocalDate = LocalDate.now(),
    ): ToggleResult {
        // The transform runs synchronously inside the store's Mutex, so writing to this
        // local from inside the lambda and reading it after `updateLines` returns is safe.
        var outcome: ConflictOutcome? = null

        val result = store.updateLines(uri) { freshLines ->
            val current = Fingerprint.of(freshLines)
            val rebased = expectedFingerprint != null && current != expectedFingerprint

            // Resolve by STABLE ID, never by the line index the UI captured — that index
            // may point at a different task after a sync.
            val node = MarkdownTreeParser.flatten(MarkdownTreeParser.parseTree(freshLines))
                .firstOrNull { it.id == todoId }

            if (node == null) {
                // Abort: return null so the store skips the write entirely.
                outcome = ConflictOutcome.TargetMissing(todoId)
                return@updateLines null
            }

            val toggled = ToggleEngine.toggle(
                lines = freshLines,
                lineIndex = node.lineIndex,
                enableTasksPlugin = enableTasksPlugin,
                today = today,
                options = options,
            )

            // Ancestors are re-evaluated in the same breath, in the same write.
            val settled = AutoCheck.applyAutoCheckState(toggled, options)

            outcome = if (rebased) ConflictOutcome.Rebased(settled) else ConflictOutcome.Clean(settled)
            settled
        }

        return when {
            result.isFailure -> ToggleResult.Failed(result.exceptionOrNull()!!)
            outcome is ConflictOutcome.TargetMissing -> ToggleResult.Missing(todoId)
            outcome is ConflictOutcome.Rebased -> ToggleResult.Success(rebased = true)
            outcome is ConflictOutcome.Clean -> ToggleResult.Success(rebased = false)
            // updateLines returned success but the transform never ran (store skipped it).
            // Treat as missing rather than claiming a write that did not happen.
            else -> ToggleResult.Missing(todoId)
        }
    }

    /**
     * Re-runs the auto-check over externally-changed content (e.g. after a Syncthing sync
     * landed a file where all children are done but the parent is not).
     *
     * Writes **only** when the auto-check actually changes something. That matters: this
     * runs on every file-change notification, and a write that rewrites identical bytes
     * would trigger another change notification, which would trigger another write —
     * an infinite feedback loop that pins the battery and, on some sync services, can
     * generate conflict copies.
     */
    suspend fun applyAutoCheckToFile(
        uri: String,
        options: AutoCheckOptions = AutoCheckOptions(),
    ): ToggleResult {
        var changed = false

        val result = store.updateLines(uri) { freshLines ->
            val settled = AutoCheck.applyAutoCheckState(freshLines, options)
            if (settled == freshLines) {
                // Identical content: return null so the store does not write.
                return@updateLines null
            }
            changed = true
            settled
        }

        return when {
            result.isFailure -> ToggleResult.Failed(result.exceptionOrNull()!!)
            changed -> ToggleResult.Success(rebased = false)
            else -> ToggleResult.Success(rebased = false)
        }
    }
}
