/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.highlight

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Consumer
import org.rust.lang.core.cfg.ExitPoint
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RsElementTypes.Q
import org.rust.lang.core.psi.RsElementTypes.RETURN
import org.rust.lang.core.psi.ext.ancestors
import org.rust.lang.core.psi.ext.elementType
import org.rust.lang.core.psi.ext.isAsync
import org.rust.lang.core.psi.ext.isTry

class RsHighlightExitPointsHandlerFactory : HighlightUsagesHandlerFactoryBase() {
    override fun createHighlightUsagesHandler(editor: Editor, file: PsiFile, target: PsiElement): HighlightUsagesHandlerBase<*>? {
        if (file !is RsFile) return null

        val createHandler: (PsiElement) -> RsHighlightExitPointsHandler? = { element ->
            val elementType = element.elementType
            if (elementType == RETURN || (elementType == Q && element.parent is RsTryExpr)) {
                RsHighlightExitPointsHandler(editor, file, element)
            } else null
        }
        val prevToken = PsiTreeUtil.prevLeaf(target) ?: return null
        return createHandler(target) ?: createHandler(prevToken)
    }

}

private class RsHighlightExitPointsHandler(editor: Editor, file: PsiFile, var target: PsiElement) : HighlightUsagesHandlerBase<PsiElement>(editor, file) {
    override fun getTargets() = listOf(target)

    override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<List<PsiElement>>) {
        selectionConsumer.consume(targets)
    }

    override fun computeUsages(targets: MutableList<PsiElement>?) {
        val sink: (ExitPoint) -> Unit = { exitPoint ->
            when (exitPoint) {
                is ExitPoint.Return -> addOccurrence(exitPoint.e)
                is ExitPoint.TryExpr -> if (exitPoint.e is RsTryExpr) addOccurrence(exitPoint.e.q) else addOccurrence(exitPoint.e)
                is ExitPoint.DivergingExpr -> addOccurrence(exitPoint.e)
                is ExitPoint.TailExpr -> addOccurrence(exitPoint.e)
                is ExitPoint.TailStatement -> Unit
            }
        }

        for (ancestor in target.ancestors) {
            if (ancestor is RsBlockExpr && ancestor.isTry && target.elementType == Q) {
                break
            } else if (ancestor is RsBlockExpr && ancestor.isAsync) {
                ExitPoint.process(ancestor.block, sink)
                break
            } else if (ancestor is RsFunction) {
                ExitPoint.process(ancestor.block, sink)
                break
            } else if (ancestor is RsLambdaExpr) {
                ExitPoint.process(ancestor.expr, sink)
                break
            }
        }
    }
}
