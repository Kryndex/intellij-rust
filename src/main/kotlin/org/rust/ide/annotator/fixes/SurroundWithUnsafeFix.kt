package org.rust.ide.annotator.fixes

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsExpr
import org.rust.lang.core.psi.RsExprStmt
import org.rust.lang.core.psi.RsPsiFactory

class SurroundWithUnsafeFix(val expr: RsExpr) : LocalQuickFixAndIntentionActionOnPsiElement(expr) {
    override fun getFamilyName() = text
    override fun getText() = "Surround with unsafe block"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val parent = expr.parent
        when (parent) {
            is RsExprStmt -> {
                val unsafe = RsPsiFactory(project).createUnsafeBlockExpr(expr.parent.text)
                expr.parent.replace(unsafe)
            }
            else -> {
                val unsafe = RsPsiFactory(project).createUnsafeBlockExpr(expr.text)
                expr.replace(unsafe)
            }
        }
    }
}
