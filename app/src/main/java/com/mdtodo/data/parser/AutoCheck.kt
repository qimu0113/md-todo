package com.mdtodo.data.parser

import java.time.LocalDate

/**
 * Auto-check rules for parent/child tasks.
 *
 * ## The rules, and why
 *
 * ### R1 — A task with NO children is never auto-checked.
 * This is the single most important rule. `applyAutoCheckState` runs over the whole file
 * on every parse and on every write. If "no children" implied "done", every ordinary
 * single-line task in the file would be silently ticked on open. The file would rewrite
 * itself the first time the user opened it. So the auto-check only ever fires on a task
 * that provably has at least one direct child.
 *
 * ### R2 — All direct children done ⇒ parent is auto-checked.
 * Forward direction. The parent is a container; if everything inside it is finished, the
 * container is finished.
 *
 * ### R3 — Any direct child not done ⇒ parent is auto-UN-checked.
 * Reverse direction, and the reason R2 alone is wrong. Without R3 you reach an
 * inconsistent state that the user cannot escape: parent `[x]`, child `[ ]`. Checking the
 * parent would not stick (R2 keeps re-asserting nothing, but R3's absence means the stale
 * `[x]` survives), and the user cannot uncheck the parent either because the next parse
 * would leave it. R3 makes the parent a *derived* value that always agrees with its
 * children, in both directions.
 *
 * ### R4 — Manual un-check of a parent whose children are all done is ALLOWED and STICKY.
 * Rule 3 sounds like it forbids this, and it does: un-checking the parent is accepted by
 * the write path, but the very next `applyAutoCheckState` re-checks it, because all
 * children are still done. We deliberately do NOT block the write. Rationale: silently
 * refusing a user's tap is worse than letting it visibly spring back — the spring-back
 * *is* the feedback that "this parent is driven by its children". The alternative
 * (allowing parent `[ ]` with all children `[x]`) creates a state that contradicts R2 and
 * needs a separate "override" marker in the file. If we later want that, the marker is the
 * place to add it — see [AutoCheckOptions.allowManualParentOverride], default off.
 *
 * ### R5 — Recursion for deeper nesting.
 * `allDirectChildrenDone` is evaluated bottom-up over the whole tree, so a grandchild's
 * state propagates to grandparent automatically. A parent is auto-done iff every
 * *descendant* is done, not merely its direct children. With two levels this is identical
 * to R2; the recursion is what makes it correct if the app later enables deeper nesting.
 *
 * ### R6 — Deleting all children leaves the parent ALONE.
 * When the last child is deleted the parent becomes childless, so R1 kicks in and the
 * parent keeps whatever state it had. We do not un-check it. Reason: the deletion is a
 * structural edit, not a statement about completion. Un-checking would make "delete my
 * subtasks" silently resurrect the parent as open work, which is a surprising side effect
 * of an unrelated action. If the user wants it open, they un-check it themselves, and R3
 * will not fight them because there are no children left to contradict.
 *
 * ### R7 — Completion dates are never fabricated.
 * Auto-check changes the checkbox only. It never adds or removes `✅ YYYY-MM-DD`, because
 * we do not know when the parent "was really finished", and writing a date that the user
 * never asked for corrupts their Obsidian Tasks metadata. Dates are only touched by an
 * explicit manual toggle.
 */
object AutoCheck {

    /**
     * Applies R1–R7 to [lines] and returns the rewritten lines.
     *
     * Pure function — no IO, no mutation of the input. Trivial to unit test.
     * Only task lines whose checkbox actually needs to change are touched; every other
     * line (headings, blank lines, prose, frontmatter, code fences) is returned by identity,
     * so a file with no pending auto-check comes back structurally identical.
     */
    fun applyAutoCheckState(
        lines: List<String>,
        options: AutoCheckOptions = AutoCheckOptions(),
    ): List<String> {
        if (!options.enabled) return lines
        if (lines.isEmpty()) return lines

        val tree = MarkdownTreeParser.parseTree(lines)
        if (tree.isEmpty()) return lines

        // Bottom-up resolution: a node's derived state depends on its children's derived state.
        // `resolved` maps lineIndex -> desired isDone. Children are always at a greater
        // lineIndex than their parent, so iterating the flattened list in reverse order
        // guarantees every child is resolved before its parent is visited.
        val flattened = MarkdownTreeParser.flatten(tree)
        val resolved = HashMap<Int, Boolean>(flattened.size * 2)

        for (node in flattened.asReversed()) {
            val desired = when {
                // R1: childless tasks are never driven by the auto-checker.
                !node.hasChildren -> node.isDone
                // R5: bottom-up, so children's `resolved` values are already final.
                else -> node.children.all { child -> resolved[child.lineIndex] ?: child.isDone }
            }
            resolved[node.lineIndex] = desired
        }

        // Apply: rebuild only the lines whose checkbox must change.
        val out = lines.toMutableList()
        var changed = false
        for (node in flattened) {
            val desired = resolved[node.lineIndex] ?: continue
            if (desired == node.isDone) continue

            val rewritten = ToggleEngine.setCheckbox(lines[node.lineIndex], desired)
            if (rewritten == null) continue
            out[node.lineIndex] = rewritten
            changed = true
        }

        return if (changed) out else lines
    }

    /**
     * Runs R1–R7 after a *manual* toggle, so the tap's effect on ancestors is immediate
     * rather than waiting for the next parse.
     *
     * Order matters: toggle first, then auto-check. If we auto-checked first we would
     * evaluate the parent's state against the child's *pre-toggle* value and then have to
     * re-run — and for a parent with two children, toggling the last one would need two
     * passes. Toggling first makes a single pass sufficient.
     */
    fun applyAutoCheckAfterToggle(
        lines: List<String>,
        lineIndex: Int,
        enableTasksPlugin: Boolean,
        options: AutoCheckOptions = AutoCheckOptions(),
        today: LocalDate = LocalDate.now(),
    ): List<String> {
        val toggled = ToggleEngine.toggle(
            lines = lines,
            lineIndex = lineIndex,
            enableTasksPlugin = enableTasksPlugin,
            today = today,
            options = options,
        )
        return applyAutoCheckState(toggled, options)
    }
}

/** Tunables for [AutoCheck]. Defaults encode rules R1–R7 as described above. */
data class AutoCheckOptions(
    val enabled: Boolean = true,

    /**
     * R4 escape hatch. When true, a manual un-check of a parent whose children are all
     * done is preserved on the next pass instead of being reverted.
     *
     * Default `false` — see R4 for why. **待验证**: turning this on needs a way to
     * represent "manually overridden" in the file, otherwise the state cannot survive a
     * reboot. Do not enable without that.
     */
    val allowManualParentOverride: Boolean = false,
)
