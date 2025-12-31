package com.pan.dashstyle
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.util.ProcessingContext
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.util.PsiTreeUtil


class StyleStringKeyReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference?> {

        val literal = element as? JSLiteralExpression ?: return emptyArray()
        val text = literal.stringValue
        // 关键判断：父节点必须是 JSIndexedPropertyAccessExpression（即 xxx["..."] 形式）
        val parentIndexAccess = PsiTreeUtil.getParentOfType(literal, JSIndexedPropertyAccessExpression::class.java)
        if (parentIndexAccess == null || parentIndexAccess.indexExpression != literal) {
            // 不是索引访问的字符串，或不是作为索引的那个字符串
            return emptyArray()
        }
        if(text == null){
            return emptyArray()
        }
        return arrayOf(StyleStringKeyReference(literal, text))
    }
}
