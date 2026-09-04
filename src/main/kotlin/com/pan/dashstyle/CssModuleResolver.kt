package com.pan.dashstyle

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding
import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.css.StylesheetFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * 共享的 CSS Module 解析层，供 F1/F4/F5/F6/F7/F2 统一复用。
 *
 * 统一覆盖两类访问形式：
 *  - styles["foo-bar"]   (JSIndexedPropertyAccessExpression + JSLiteralExpression)
 *  - styles.fooBar        (JSReferenceExpression, qualifier = styles object)
 *  - Vue $style.fooBar / $style["foo-bar"]
 */
object CssModuleResolver {

    private val MODULE_EXTS = listOf(".module.css", ".module.scss", ".module.sass", ".module.less")

    // ================================================================
    // 1. 入口：从 PsiElement（qualifier / literal / referenceExpression）拿到 styles 对象和目标 CSS 容器
    // ================================================================
    data class ResolvedClass(
        val ruleset: CssRuleset,
        val kebabName: String,
        val expandedSelector: String,
        val container: CssContainer
    )

    sealed class CssContainer {
        data class ImportedFile(
            val psiFile: PsiFile,
            val virtualFile: VirtualFile,
            val importBindingName: String,
            val importDeclaration: ES6ImportDeclaration?
        ) : CssContainer()

        data class VueStyleTag(
            val styleTag: XmlTag,
            val moduleAlias: String, // "$style" 或 "$xxx"
            val containingFile: PsiFile
        ) : CssContainer()

        /** 本地 JS 字面量对象（const styles = {fooBar:{...}}），不是真正 CSS 文件 */
        data class LocalObjectLiteral(
            val literal: JSObjectLiteralExpression,
            val containingFile: PsiFile,
            val variableName: String
        ) : CssContainer()
    }

    /**
     * 把任意一个"看起来是 styles.xxx / styles['xxx'] / xxx 里的字符串"的元素，
     * 回溯解析出对应的 CssContainer（目标 CSS 文件/Vue <style>）以及 qualiferName。
     * 失败返回 null。
     */
    fun resolveStylesContainer(siteElement: PsiElement): Pair<CssContainer, String>? {
        // Case A: string literal in styles["foo"]
        val literal = siteElement as? JSLiteralExpression
        if (literal != null) {
            val indexAccess = PsiTreeUtil.getParentOfType(literal, JSIndexedPropertyAccessExpression::class.java)
            if (indexAccess != null && indexAccess.indexExpression == literal) {
                val qualifier = indexAccess.qualifier ?: return null
                return resolveQualifier(qualifier, siteElement.containingFile)
            }
        }

        // Case B: styles.fooBar — 直接就是 JSReferenceExpression
        val refExpr = siteElement as? JSReferenceExpression
        if (refExpr != null && refExpr.qualifier != null) {
            val qualifier = refExpr.qualifier ?: return null
            return resolveQualifier(qualifier, siteElement.containingFile)
        }

        // Case C: JSProperty 的 name（很少见，但兜底）
        val parent = siteElement.parent
        if (parent is JSIndexedPropertyAccessExpression && parent.indexExpression == siteElement) {
            val qualifier = parent.qualifier ?: return null
            return resolveQualifier(qualifier, siteElement.containingFile)
        }
        if (parent is JSReferenceExpression && parent.qualifier == siteElement) {
            return resolveQualifier(siteElement, siteElement.containingFile)
        }
        return null
    }

    fun resolveQualifier(qualifierExpr: PsiElement, contextFile: PsiFile?): Pair<CssContainer, String>? {
        val qualifierName = qualifierExpr.text
        if (qualifierName.isBlank()) return null

        // 先直接 reference.resolve()
        val resolved = (qualifierExpr as? JSReferenceExpression)?.reference?.resolve()
        if (resolved != null) {
            val (container, name) = fromResolvedBinding(resolved, qualifierName)
            if (container != null) return container to name
        }

        // Vue fallback
        if (contextFile?.name?.endsWith(".vue") == true && contextFile is XmlFile) {
            val templateTag = Util.findTagInFile(contextFile, "template")
            if (templateTag != null && PsiTreeUtil.isAncestor(templateTag, qualifierExpr, false)) {
                // $style / $xxx (module alias)
                if (qualifierName.startsWith("\$")) {
                    val alias = qualifierName.drop(1)
                    val styles = PsiTreeUtil.findChildrenOfType(contextFile, XmlTag::class.java)
                        .filter { it.name.equals("style", ignoreCase = true) }
                    val mod = if (alias == "style") {
                        styles.firstOrNull { it.getAttribute("module") != null }
                    } else {
                        styles.firstOrNull { it.getAttributeValue("module") == alias }
                    }
                    if (mod != null) return CssContainer.VueStyleTag(mod, qualifierName, contextFile) to qualifierName
                    val any = styles.firstOrNull()
                    if (any != null) return CssContainer.VueStyleTag(any, "\$style", contextFile) to qualifierName
                }
                // setup 里声明的同名变量
                val scriptTag = Util.findScriptTag(contextFile)
                val variable = Util.findVariableDeclarationByName(qualifierName, scriptTag)
                if (variable != null) {
                    val init = variable.initializer
                    if (init is JSCallExpression && Util.isUseCssModuleFromVue(init)) {
                        val moduleStyleTag = Util.findModuleStyleTag(contextFile)
                        if (moduleStyleTag != null) return CssContainer.VueStyleTag(moduleStyleTag, qualifierName, contextFile) to qualifierName
                    }
                    if (init is JSObjectLiteralExpression) return CssContainer.LocalObjectLiteral(init, contextFile, qualifierName) to qualifierName
                    if (init != null) {
                        val (container, name) = fromResolvedBinding(init, qualifierName)
                        if (container != null) return container to name
                    }
                }
            }
        }

        // 再兜底：本地变量（scope-aware，不全局扫描文件）
        // 先找最近的作用域（函数/块），再从内向外逐层查找
        if (contextFile != null) {
            val variable = findVariableInScope(qualifierExpr, qualifierName, contextFile)
            if (variable != null) {
                val init = variable.initializer
                if (init is JSObjectLiteralExpression) return CssContainer.LocalObjectLiteral(init, contextFile, qualifierName) to qualifierName
                if (init != null) {
                    val (container, name) = fromResolvedBinding(init, qualifierName)
                    if (container != null) return container to name
                }
            }
        }
        return null
    }

    /**
     * 从 qualifierExpr 开始，向外逐层查找名为 [name] 的 JSVariable 声明。
     * 优先找当前作用域内的变量，避免在整个文件里按文本名匹配到错误变量。
     */
    private fun findVariableInScope(qualifierExpr: PsiElement, name: String, contextFile: PsiFile): JSVariable? {
        // 从 qualifierExpr 开始，逐层向外找 enclosing 函数/文件
        var scope: PsiElement? = PsiTreeUtil.getParentOfType(qualifierExpr, JSFunction::class.java, JSBlockStatement::class.java)
        // 如果 qualifierExpr 本身就是顶级表达式（不在任何函数内），scope 会设为 null
        val seen = mutableSetOf<PsiElement>()
        while (scope != null) {
            if (!seen.add(scope)) break
            val found = PsiTreeUtil.findChildrenOfType(scope, JSVariable::class.java)
                .firstOrNull { it.name == name }
            if (found != null) return found
            scope = PsiTreeUtil.getParentOfType(scope, JSFunction::class.java, JSBlockStatement::class.java)
        }
        // 兜底：文件级变量（import binding / 顶级声明）
        for (child in contextFile.children) {
            val v = PsiTreeUtil.findChildrenOfType(child, JSVariable::class.java)
                .firstOrNull { it.name == name }
            if (v != null) return v
        }
        return null
    }

    private fun fromResolvedBinding(resolved: PsiElement, fallbackName: String): Pair<CssContainer?, String> {
        when {
            resolved is ES6ImportedBinding -> {
                var bindingName = resolved.name ?: fallbackName
                val declaration = PsiTreeUtil.getParentOfType(resolved, ES6ImportDeclaration::class.java)
                val refs = resolved.findReferencedElements()
                for (ref in refs) {
                    if (ref is StylesheetFile) {
                        val vf = ref.virtualFile ?: continue
                        return CssContainer.ImportedFile(ref, vf, bindingName, declaration) to bindingName
                    }
                    if (ref is PsiFile && ref.virtualFile != null && MODULE_EXTS.any { ref.name.endsWith(it, ignoreCase = true) }) {
                        return CssContainer.ImportedFile(ref, ref.virtualFile!!, bindingName, declaration) to bindingName
                    }
                }
            }
            resolved is StylesheetFile -> {
                val vf = resolved.virtualFile
                if (vf != null) return CssContainer.ImportedFile(resolved, vf, fallbackName, null) to fallbackName
            }
            resolved is JSObjectLiteralExpression -> {
                return CssContainer.LocalObjectLiteral(resolved, resolved.containingFile, fallbackName) to fallbackName
            }
            resolved is JSVariable -> {
                val init = resolved.initializer
                if (init is JSObjectLiteralExpression) {
                    return CssContainer.LocalObjectLiteral(init, init.containingFile, resolved.name ?: fallbackName) to (resolved.name ?: fallbackName)
                }
            }
            resolved is XmlTag && resolved.name.equals("style", ignoreCase = true) -> {
                val modValue = resolved.getAttributeValue("module")
                val alias = if (modValue.isNullOrBlank()) "\$style" else "\$$modValue"
                return CssContainer.VueStyleTag(resolved, alias, resolved.containingFile) to alias
            }
        }
        return null to fallbackName
    }

    // ================================================================
    // 2. 具体 class 解析：从引用定位到 ruleset + 展开选择器
    // ================================================================
    fun resolveClassName(siteElement: PsiElement, requestedName: String): ResolvedClass? {
        val (container, _) = resolveStylesContainer(siteElement) ?: return null
        val kebabTarget = if (requestedName.contains("-")) requestedName else Util.camelToKebab(requestedName)
        val pattern = Regex("""\.${Regex.escape(kebabTarget)}(?=[^a-zA-Z0-9_-]|$)""")
        forEachRuleset(container) { ruleset ->
            val expanded = Util.expandSelector(ruleset)
            if (pattern.containsMatchIn(expanded)) {
                return ResolvedClass(ruleset, kebabTarget, expanded, container)
            }
        }
        return null
    }

    // ================================================================
    // 3. 遍历容器内所有 ruleset / 所有 class 名（给 #1 未使用检测、#2 重复声明检测用）
    // ================================================================
    data class ClassEntry(
        val kebabName: String,
        val ruleset: CssRuleset,
        val expandedSelector: String,
        val declarations: List<CssDeclaration>
    )

    fun collectAllClasses(container: CssContainer): List<ClassEntry> {
        val out = mutableListOf<ClassEntry>()
        forEachRuleset(container) { ruleset ->
            val expanded = Util.expandSelector(ruleset)
            val names = Util.extractClassNames(expanded).distinct()
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
    private inline fun forEachRuleset(container: CssContainer, action: (CssRuleset) -> Unit) {
        when (container) {
            is CssContainer.ImportedFile -> {
                val target = container.psiFile as? StylesheetFile ?: return
                PsiTreeUtil.findChildrenOfType(target.stylesheet, CssRuleset::class.java).forEach(action)
            }
            is CssContainer.VueStyleTag -> {
                PsiTreeUtil.findChildrenOfType(container.styleTag, CssRuleset::class.java).forEach(action)
            }
            is CssContainer.LocalObjectLiteral -> {
                // 本地对象没有 ruleset 概念，交给调用方特殊处理
            }
        }
    }

    // ================================================================
    // 4. 从 JSX/Vue 使用端扫描 CSS Module class 的所有引用（给 #1 未使用检测用）
    // ================================================================
    data class ClassUsage(val kebabName: String, val site: PsiElement)

    /**
     * 遍历 sourceFile 内所有针对指定 container 绑定名的引用，产出 class 使用集合。
     * 仅处理：styles.fooBar / styles["foo-bar"] / :class="$style.fooBar"。
     * 动态访问 styles[expr] 会被标为 "any usage"，caller 据此决定是否跳过整个置灰。
     */
    fun scanUsages(sourceFile: PsiFile, container: CssContainer): Pair<MutableSet<String>, Boolean /* hasDynamic */> {
        val used = mutableSetOf<String>()
        var dynamic = false

        val qualifierNames: Set<String> = when (container) {
            is CssContainer.ImportedFile -> setOf(container.importBindingName)
            is CssContainer.VueStyleTag -> setOf(container.moduleAlias, container.moduleAlias.removePrefix("$"))
            is CssContainer.LocalObjectLiteral -> setOf(container.variableName)
        }

        // 遍历所有 JSIndexedPropertyAccessExpression
        PsiTreeUtil.findChildrenOfType(sourceFile, JSIndexedPropertyAccessExpression::class.java).forEach { idx ->
            val q = idx.qualifier ?: return@forEach
            if (q.text !in qualifierNames) {
                // 再深入一层：resolve 一下 qualifier 确认绑定名
                val resolved = (q as? JSReferenceExpression)?.reference?.resolve()
                val (c, _) = fromResolvedBinding(resolved ?: return@forEach, q.text)
                if (c == null || c != container) return@forEach
            }
            val inner = idx.indexExpression
            when {
                inner is JSLiteralExpression -> {
                    val s = inner.stringValue ?: return@forEach
                    val kebab = if (s.contains("-")) s else Util.camelToKebab(s)
                    used += kebab
                }
                else -> dynamic = true
            }
        }

        // 遍历所有 JSReferenceExpression，且 qualifier.text == 绑定名
        PsiTreeUtil.findChildrenOfType(sourceFile, JSReferenceExpression::class.java).forEach { ref ->
            val q = ref.qualifier ?: return@forEach
            if (q.text !in qualifierNames) {
                val resolved = (q as? JSReferenceExpression)?.reference?.resolve() ?: return@forEach
                val (c, _) = fromResolvedBinding(resolved, q.text)
                if (c == null || c != container) return@forEach
            }
            val name = ref.referenceName ?: return@forEach
            if (name == "let" || name == "const" || name == "var") return@forEach
            val kebab = if (name.contains("-")) name else Util.camelToKebab(name)
            used += kebab
        }

        return used to dynamic
    }

    // ================================================================
    // 5. 同目录候选 CSS Module 文件（F5 创建 import、F6 找不到 import 时新建 class 用）
    // ================================================================
    fun findCandidateModuleFiles(sourceFile: PsiFile): List<Pair<VirtualFile, String /* suggest alias */>> {
        val vf = sourceFile.virtualFile ?: return emptyList()
        val parent = vf.parent ?: return emptyList()
        val base = vf.nameWithoutExtension
        val exact = mutableListOf<Pair<VirtualFile, String>>()
        val others = mutableListOf<Pair<VirtualFile, String>>()
        for (child in parent.children) {
            val ext = child.extension ?: continue
            val name = child.name
            if (!MODULE_EXTS.any { name.endsWith(it, ignoreCase = true) }) continue
            if (name.startsWith(base)) exact += child to "styles"
            else others += child to "styles" + (others.size + 1).toString().takeIf { others.size > 0 }.orEmpty()
        }
        return exact + others
    }

    fun resolveContainerByFile(project: Project, cssVFile: VirtualFile, importBindingName: String): CssContainer.ImportedFile? {
        val psiFile = PsiManager.getInstance(project).findFile(cssVFile) ?: return null
        return CssContainer.ImportedFile(psiFile, cssVFile, importBindingName, null)
    }
}
