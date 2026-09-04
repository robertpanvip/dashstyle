package com.pan.dashstyle.reference

import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import com.intellij.psi.util.PsiTreeUtil

/**
 * 让 styles.fooBar 能像 styles["foo-bar"] 一样 resolve 到对应的 CSS ruleset。
 * 并在 getVariants 时参与补全（和 string-key 那套一致）。
 */
class StyleMemberAccessReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference?> {
        val refExpr = element as? JSReferenceExpression ?: return emptyArray()
        val qualifier = refExpr.qualifier ?: return emptyArray()
        // 必须是 qualifier.memberName 的形式 — 且 qualifier 本身要是一个"可 resolve 的引用或变量"
        if (refExpr.referenceName.isNullOrBlank()) return emptyArray()

        // 先快速排除明显不是 styles 对象的：qualifier 文本长度>=2 且 parent 不是 index-access 的 qualifier
        if (element.parent is JSIndexedPropertyAccessExpression) return emptyArray()

        // 过滤掉函数调用的 qualifier (foo.bar(...))
        if (element.parent is JSCallExpression && (element.parent as JSCallExpression).methodExpression == element) {
            return emptyArray()
        }

        // 只有 qualifier 能 resolve 到 CSS Module 容器时才注册引用
        val probe = CssModuleResolver.resolveStylesContainer(refExpr)
        if (probe == null) return emptyArray()
        return arrayOf(StyleMemberAccessReference(refExpr))
    }
}

class StyleMemberAccessReference(
    element: JSReferenceExpression
) : PsiReferenceBase<JSReferenceExpression>(element, PsiReferenceUtil.getNameRangeIn(element)) {

    override fun resolve(): PsiElement? {
        val name = element.referenceName ?: return null
        return CssModuleResolver.resolveClassName(element, name)?.ruleset
    }

    override fun isSoft(): Boolean = true

    override fun getVariants(): Array<Any> {
        val (container, _) = CssModuleResolver.resolveStylesContainer(element) ?: return emptyArray()
        val classes = CssModuleResolver.collectAllClasses(container)
        // member-access 用 camelCase（符合 JS 对象习惯），type text 展示 kebab
        return classes.map { it.kebabName }.distinct().sorted().map { kebab ->
            val camel = NamingUtil.kebabToCamel(kebab)
            com.intellij.codeInsight.lookup.LookupElementBuilder.create(camel)
                .withTypeText(kebab, true)
                .withTailText(" (DashStyle)", true)
        }.toTypedArray()
    }
}

object PsiReferenceUtil {
    /** member access 的 range 只高亮 "fooBar" 这段（最后那个 identifier）。 */
    fun getNameRangeIn(expr: JSReferenceExpression): TextRange {
        val name = expr.referenceName
        val full = expr.text
        if (name == null || full == null) return TextRange.EMPTY_RANGE
        val idx = full.lastIndexOf(name)
        return if (idx >= 0) TextRange.create(idx, idx + name.length) else TextRange.EMPTY_RANGE
    }
}
