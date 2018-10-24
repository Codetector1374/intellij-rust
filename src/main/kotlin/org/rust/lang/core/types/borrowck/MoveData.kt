/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.DataFlowContext
import org.rust.lang.core.DataFlowOperator
import org.rust.lang.core.KillFrom
import org.rust.lang.core.KillFrom.Execution
import org.rust.lang.core.KillFrom.ScopeEnd
import org.rust.lang.core.cfg.ControlFlowGraph
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.borrowck.LoanPathElement.Interior
import org.rust.lang.core.types.borrowck.LoanPathKind.*
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.ty.TyUnknown
import org.rust.openapiext.testAssert

class MoveData(
    val paths: MutableList<MovePath> = mutableListOf(),

    /** Cache of loan path to move path, for easy lookup. */
    val pathMap: MutableMap<LoanPath, MovePath> = mutableMapOf(),

    /** Each move or uninitialized variable gets an entry here. */
    val moves: MutableList<Move> = mutableListOf(),

    /**
     * Assignments to a variable, like `x = foo`. These are assigned bits for data-flow since we must track them
     * to ensure that immutable variables are assigned at most once along each path.
     */
    val varAssignments: MutableList<Assignment> = mutableListOf(),

    /**
     * Assignments to a path, like `x.f = foo`. These are not assigned data-flow bits,
     * but we track them because they still kill move bits.
     */
    private val pathAssignments: MutableList<Assignment> = mutableListOf(),

    /** Assignments to a variable or path, like `x = foo`, but not `x += foo`. */
    private val assigneeElements: MutableSet<RsElement> = mutableSetOf()
) {
    fun isEmpty(): Boolean =
        moves.isEmpty() && pathAssignments.isEmpty() && varAssignments.isEmpty()

    fun isNotEmpty(): Boolean =
        !isEmpty()

    private fun eachExtendingPath(movePath: MovePath, action: (MovePath) -> Boolean): Boolean {
        if (!action(movePath)) return false
        var path = movePath.firstChild
        while (path != null) {
            if (!eachExtendingPath(path, action)) return false
            path = path.nextSibling
        }
        return true
    }

    private fun eachApplicableMove(movePath: MovePath, action: (Move) -> Boolean): Boolean {
        var result = true
        eachExtendingPath(movePath) { path ->
            var move: Move? = path.firstMove
            while (move != null) {
                if (!action(move)) {
                    result = false
                    break
                }
                move = move.nextMove
            }
            result
        }
        return result
    }

    private fun killMoves(path: MovePath, killElement: RsElement, killKind: KillFrom, dfcxMoves: MoveDataFlow) {
        if (!path.loanPath.isPrecise) return

        eachApplicableMove(path) { move ->
            dfcxMoves.addKill(killKind, killElement, move.index)
            true
        }
    }

    /**
     * Adds the gen/kills for the various moves and assignments into the provided data flow contexts.
     * Moves are generated by moves and killed by assignments and scoping.
     * Assignments are generated by assignment to variables and killed by scoping
     */
    fun addGenKills(bccx: BorrowCheckContext, dfcxMoves: MoveDataFlow, dfcxAssign: AssignDataFlow) {
        moves.forEachIndexed { i, move ->
            dfcxMoves.addGen(move.element, i)
        }

        varAssignments.forEachIndexed { i, assignment ->
            dfcxAssign.addGen(assignment.element, i)
            killMoves(assignment.path, assignment.element, Execution, dfcxMoves)
        }

        pathAssignments.forEach { assignment ->
            killMoves(assignment.path, assignment.element, Execution, dfcxMoves)
        }

        // Kill all moves related to a variable `x` when it goes out of scope
        paths.forEach { path ->
            val kind = path.loanPath.kind
            if (kind is Var || kind is Downcast) {
                val killScope = path.loanPath.killScope(bccx)
                val movePath = pathMap[path.loanPath] ?: return
                killMoves(movePath, killScope.element, ScopeEnd, dfcxMoves)
            }
        }

        // Kill all assignments when the variable goes out of scope
        varAssignments.forEachIndexed { i, assignment ->
            val lp = assignment.path.loanPath
            if (lp.kind is Var || lp.kind is Downcast) {
                val killScope = lp.killScope(bccx)
                dfcxAssign.addKill(ScopeEnd, killScope.element, i)
            }
        }
    }

    /**
     * Returns the existing move path for [loanPath], if any, and otherwise adds a new move path for [loanPath]
     * and any of its base paths that do not yet have an index.
     */
    private fun movePathOf(loanPath: LoanPath): MovePath {
        pathMap[loanPath]?.let { return it }

        val kind = loanPath.kind
        val oldSize = when (kind) {
            is Var -> {
                val index = paths.size
                paths.add(MovePath(loanPath))
                index
            }

            is Downcast, is Extend -> {
                val base = (kind as? Downcast)?.loanPath ?: (kind as? Extend)?.loanPath!!
                val parentPath = movePathOf(base)
                val index = paths.size

                val newMovePath = MovePath(loanPath, parentPath, null, null, parentPath.firstChild)
                parentPath.firstChild = newMovePath
                paths.add(newMovePath)
                index
            }
        }

        testAssert { oldSize == paths.size - 1 }
        pathMap[loanPath] = paths.last()
        return paths.last()
    }

    private fun processUnionFields(lpKind: LoanPathKind.Extend, action: (LoanPath) -> Unit) {
        val base = lpKind.loanPath
        val baseType = base.ty as? TyAdt ?: return
        val lpElement = lpKind.lpElement as? Interior ?: return
        val union = (baseType.item as? RsStructItem)?.takeIf { it.kind == RsStructKind.UNION } ?: return

        val interiorFieldName = (lpElement as? Interior.Field)?.name
        val variant = lpElement.element
        val mutCat = lpKind.mutCategory

        // Moving/assigning one union field automatically moves/assigns all its fields
        union.namedFields
            .filter { it.name != interiorFieldName }
            .forEach {
                val siblingLpKind = Extend(base, mutCat, Interior.Field(variant, it.name))
                val siblingLp = LoanPath(siblingLpKind, TyUnknown, base.element)
                action(siblingLp)
            }
    }

    /** Adds a new move entry for a move of [loanPath] that occurs at location [element] with kind [kind] */
    fun addMove(loanPath: LoanPath, element: RsElement, kind: MoveKind) {
        fun addMoveHelper(loanPath: LoanPath) {
            val path = movePathOf(loanPath)
            val nextMove = path.firstMove
            val newMove = Move(path, element, kind, moves.size, nextMove)
            path.firstMove = newMove
            moves.add(newMove)
        }

        var lp = loanPath
        var lpKind = lp.kind
        while (lpKind is Extend) {
            processUnionFields(lpKind) { addMoveHelper(it) }
            lp = lpKind.loanPath
            lpKind = lp.kind
        }

        addMoveHelper(loanPath)
    }

    fun addAssignment(loanPath: LoanPath, assign: RsElement, assignee: RsElement, mode: MutateMode) {
        fun addAssignmentHelper(loanPath: LoanPath) {
            if (mode == MutateMode.Init || mode == MutateMode.JustWrite) {
                assigneeElements.add(assignee)
            }

            val movePath = movePathOf(loanPath)
            val assignment = Assignment(movePath, assign)

            if (movePath.isVariablePath) {
                varAssignments.add(assignment)
            } else {
                pathAssignments.add(assignment)
            }
        }

        val lpKind = loanPath.kind
        if (lpKind is Extend) {
            processUnionFields(lpKind) { addAssignmentHelper(it) }
        } else {
            addAssignmentHelper(loanPath)
        }
    }

    fun existingBasePaths(loanPath: LoanPath): List<MovePath> {
        val result = mutableListOf<MovePath>()
        addExistingBasePaths(loanPath, result)
        return result
    }

    /** Adds any existing move path indices for [loanPath] and any base paths to [result], but doesn't add new move paths */
    private fun addExistingBasePaths(loanPath: LoanPath, result: MutableList<MovePath>) {
        val movePath = pathMap[loanPath]
        if (movePath != null) {
            eachBasePath(movePath) { result.add(it) }
            return
        }
        val kind = loanPath.kind
        val baseLoanPath = (kind as? Downcast)?.loanPath ?: (kind as? Extend)?.loanPath ?: return
        addExistingBasePaths(baseLoanPath, result)
    }

    fun eachBasePath(movePath: MovePath, predicate: (MovePath) -> Boolean): Boolean {
        var path = movePath
        while (true) {
            if (!predicate(path)) return false
            path = path.parent ?: return true
        }
    }
}

class FlowedMoveData private constructor(
    private val moveData: MoveData,
    private val dfcxMoves: MoveDataFlow,
    private val dfcxAssign: AssignDataFlow // will be used during borrow checking
) {
    fun eachMoveOf(element: RsElement, loanPath: LoanPath, predicate: (Move, LoanPath) -> Boolean): Boolean {
        // Bad scenarios:
        // 1. Move of `a.b.c`, use of `a.b.c`
        // 2. Move of `a.b.c`, use of `a.b.c.d`
        // 3. Move of `a.b.c`, use of `a` or `a.b`
        //
        // OK scenario:
        // 4. move of `a.b.c`, use of `a.b.d`

        val baseNodes = moveData.existingBasePaths(loanPath).takeIf { it.isNotEmpty() } ?: return true

        val movePath = moveData.pathMap[loanPath]

        var result = true
        return dfcxMoves.eachBitOnEntry(element) { index ->
            val move = moveData.moves[index]
            val movedPath = move.path
            if (baseNodes.any { it == movedPath }) {
                // Scenario 1 or 2: `loanPath` or some base path of `loanPath` was moved.
                if (!predicate(move, movedPath.loanPath)) {
                    result = false
                }
            } else if (movePath != null) {
                val eachExtension = moveData.eachBasePath(movedPath) {
                    // Scenario 3: some extension of `loanPath` was moved
                    if (it == movePath) predicate(move, movedPath.loanPath) else true
                }
                if (!eachExtension) result = false
            }
            result
        }
    }

    companion object {
        fun buildFor(moveData: MoveData, bccx: BorrowCheckContext, cfg: ControlFlowGraph): FlowedMoveData {
            val dfcxMoves = DataFlowContext(cfg, MoveDataFlowOperator, moveData.moves.size)
            val dfcxAssign = DataFlowContext(cfg, AssignDataFlowOperator, moveData.varAssignments.size)

            moveData.addGenKills(bccx, dfcxMoves, dfcxAssign)
            dfcxMoves.addKillsFromFlowExits()
            dfcxAssign.addKillsFromFlowExits()
            dfcxMoves.propagate()
            dfcxAssign.propagate()

            return FlowedMoveData(moveData, dfcxMoves, dfcxAssign)
        }
    }
}

data class MovePlace(val name: RsNamedElement)

data class Move(
    val path: MovePath,
    val element: RsElement,
    val kind: MoveKind,
    val index: Int,
    /** Next node in linked list of moves from `path` */
    val nextMove: Move?
)

data class MovePath(
    val loanPath: LoanPath,
    var parent: MovePath? = null,
    var firstMove: Move? = null,
    var firstChild: MovePath? = null,
    var nextSibling: MovePath? = null
) {
    val isVariablePath: Boolean
        get() = parent == null
}

enum class MoveKind {
    /** When declared, variables start out "moved" */
    Declared,
    /** Expression or binding that moves a variable */
    MovePat,
    /** By-move binding */
    Captured,
    /** Closure creation that moves a value */
    MoveExpr
}

object MoveDataFlowOperator : DataFlowOperator {
    override fun join(succ: Int, pred: Int): Int = succ or pred     // moves from both preds are in scope
    override val initialValue: Boolean get() = false                // no loans in scope by default
}

object AssignDataFlowOperator : DataFlowOperator {
    override fun join(succ: Int, pred: Int): Int = succ or pred     // moves from both preds are in scope
    override val initialValue: Boolean get() = false                // no assignments in scope by default
}

typealias MoveDataFlow = DataFlowContext<MoveDataFlowOperator>
typealias AssignDataFlow = DataFlowContext<AssignDataFlowOperator>

data class Assignment(
    /** Path being assigned */
    val path: MovePath,
    /** Where assignment occurs */
    val element: RsElement
)

val LoanPath.isPrecise: Boolean
    get() = when (kind) {
        is Var -> true
        is Extend -> if (kind.lpElement is Interior) false else kind.loanPath.isPrecise
        is Downcast -> kind.loanPath.isPrecise
    }