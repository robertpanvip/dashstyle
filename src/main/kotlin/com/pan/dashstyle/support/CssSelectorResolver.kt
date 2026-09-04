package com.pan.dashstyle.support

import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.css.StylesheetFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/**
 * CSS Module class → ruleset 解析与遍历。
 *
 * 从 CssModuleResolver 拆出，职责聚焦于：
 * - 从使用端元素（styles.xxx / styles["xxx"]）定位到具体的 CssRuleset
 * - 遍历容器内所有 ruleset 和 class 名（给未使用检测、重复声明检测用）
 *
 * 依赖 [CssModuleResolver] 做容器解析，[CssSelectorUtil] 做选择器展开。
 */
object CssSelectorResolver {

    data class ResolvedClass(
        val ruleset: CssRuleset,
        val kebabName: String,
        val expandedSelector: String,
        val container: CssModuleResolver.CssContainer
    )

    data class ClassEntry(
        val kebabName: String,
        val ruleset: CssRuleset,
        val expandedSelector: String,
        val declarations: List<CssDeclaration>
    )

    // ================================================================
    // 1. 从引用定位到 ruleset + 展开选择器
    // ================================================================

    fun resolveClassName(siteElement: PsiElement, requestedName: String): ResolvedClass? {
        val (container, _) = CssModuleResolver.resolveStylesContainer(siteElement) ?: return null
        val kebabTarget = if (requestedName.contains("-")) requestedName else NamingUtil.camelToKebab(requestedName)
        val pattern = Regex("""\.${Regex.escape(kebabTarget)}(?=[^a-zA-Z0-9_-]|$)""")
        forEachRuleset(container) { ruleset ->
            val expanded = CssSelectorUtil.expandSelector(ruleset)
            if (pattern.containsMatchIn(expanded)) {
                return ResolvedClass(ruleset, kebabTarget, expanded, container)
            }
        }
        return null
    }

    // ================================================================
    // 2. 遍历容器内所有 ruleset / 所有 class 名
    // ================================================================

    fun collectAllClasses(container: CssModuleResolver.CssContainer): List<ClassEntry> {
        val out = mutableListOf<ClassEntry>()
        forEachRuleset(container) { ruleset ->
            val expanded = CssSelectorUtil.expandSelector(ruleset)
            val names = CssSelectorUtil.extractClassNames(expanded).distinct()
            val decls = collectDirectDeclarations(ruleset)
            for (name in names) out += ClassEntry(name, ruleset, expanded, decls)
        }
        return out
    }

    /**
     * 只收集当前 ruleset.block 的**直接子节点**中的 CssDeclaration，
     * 避免把嵌套在子选择器（如 .text:hover）里的声明也合并进来。
     * 悬浮预览只展示当前选择器直接声明的样式，不是所有后代。
     */
    private fun collectDirectDeclarations(ruleset: CssRuleset): List<CssDeclaration> {
        val block = ruleset.block ?: return emptyList()
        val out = mutableListOf<CssDeclaration>()
        for (child in block.children) {
            if (child is CssDeclaration) {
                out += child
            }
        }
        return out
    }

    /** 迭代 CSS 容器里所有顶级 ruleset，包括嵌套 ruleset（但每个 CssRuleset PSI 节点只访问一次） */
    private inline fun forEachRuleset(container: CssModuleResolver.CssContainer, action: (CssRuleset) -> Unit) {
        when (container) {
            is CssModuleResolver.CssContainer.ImportedFile -> {
                val target = container.psiFile as? StylesheetFile ?: return
                PsiTreeUtil.findChildrenOfType(target.stylesheet, CssRuleset::class.java).forEach(action)
            }
            is CssModuleResolver.CssContainer.VueStyleTag -> {
                PsiTreeUtil.findChildrenOfType(container.styleTag, CssRuleset::class.java).forEach(action)
            }
            is CssModuleResolver.CssContainer.LocalObjectLiteral -> {
                // 本地对象没有 ruleset 概念，交给调用方特殊处理
            }
        }
    }
}
