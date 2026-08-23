package com.viameowts.vialogium.actionutils

import com.viameowts.vialogium.actions.ActionType
import com.viameowts.vialogium.actions.BlockBreakActionType
import com.viameowts.vialogium.actions.BlockPlaceActionType
import com.viameowts.vialogium.actions.ItemChangeActionType
import com.viameowts.vialogium.actions.ItemDropActionType
import com.viameowts.vialogium.actions.ItemPickUpActionType

/**
 * Tracks block positions already restored by a `block-break` while a rollback walks actions
 * newest -> oldest (id DESC). Shared by every rollback path (command, network packet) so the
 * ordering rules stay in one place and cannot diverge.
 *
 * Two problems this solves:
 *  - A `block-place` at a position a `block-break` already restored must not be re-executed
 *    (it would delete the just-restored block).
 *  - Older `item-insert`/`item-remove` at a container position must not be re-applied after the
 *    `block-break` snapshot already restored the full inventory; otherwise they strip the
 *    contents and the container rolls back empty.
 *
 * Must be created once per rollback run (outside the batching loop): actions are ordered globally
 * by id DESC across batches, so a `block-break` can land in an earlier batch than the older
 * actions it supersedes.
 */
class RollbackBlockTracker(
    /**
     * Positions of container `block-break`s known up-front (pre-scan). Required for `item-drop` /
     * `item-pick-up`, which are newer than the break and thus processed before it in id-DESC order,
     * so the during-rollback tracking below cannot catch them in time.
     */
    containerBreakPositions: Set<String> = emptySet(),
) {
    private val restoredPositions = HashSet<String>()
    private val restoredContainerPositions = HashSet<String>(containerBreakPositions)
    private val containerBreakPositions = containerBreakPositions

    private fun posKey(action: ActionType): String = "${action.world}:${action.pos.x}:${action.pos.y}:${action.pos.z}"

    /**
     * Whether [action] should be skipped (not executed) because a `block-break` either already
     * restored this position (with its full state and contents) or will restore it (pre-scanned
     * container). Skipped actions should still be marked rolled_back in the database.
     *
     * - `block-place` at a restored position: re-placing would delete the restored block.
     * - `item-insert` / `item-remove` at a container position: the snapshot already holds the
     *   inventory, so re-applying would distort it.
     * - `item-drop` / `item-pick-up` at a (pre-scanned) container position: those items came out of
     *   the container; the snapshot restores them inside it, so undoing the drop/pickup too would
     *   duplicate them.
     */
    fun shouldSkip(action: ActionType): Boolean {
        val key = posKey(action)
        return (action is BlockPlaceActionType && key in restoredPositions) ||
            (action is ItemChangeActionType && key in restoredContainerPositions) ||
            ((action is ItemDropActionType || action is ItemPickUpActionType) && key in containerBreakPositions)
    }

    /**
     * Records a successfully rolled-back action so dependent actions at the same position can be
     * skipped. Only `block-break` matters; a non-blank [ActionType.extraData] means the broken
     * block had a block entity (container, etc.) whose inventory is captured in the snapshot.
     */
    fun recordRolledBack(action: ActionType) {
        if (action !is BlockBreakActionType) return
        val key = posKey(action)
        restoredPositions.add(key)
        if (!action.extraData.isNullOrBlank()) {
            restoredContainerPositions.add(key)
        }
    }
}
