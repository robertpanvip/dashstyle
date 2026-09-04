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
import com.intellij.psi.xml.XmlAttribute
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
        /**
         * 表示一个 CSS Module 文件及其在 JS 中的 import binding。
         *
         * 注意：equals/hashCode 仅基于 psiFile + virtualFile + importBindingName，
         * **不包含** importDeclaration。这是因为 resolveQualifier 在解析引用时
         * 会找到真实的 ES6ImportDeclaration（非 null），而调用方（如
         * resolveContainerForUsageScan、测试代码）可能传 null。如果 data class
         * 自动把 importDeclaration 纳入 equals，就会导致容器比较失败，
         * 所有 usage 扫描都被过滤掉。
         */
        class ImportedFile(
            val psiFile: PsiFile,
            val virtualFile: VirtualFile,
            val importBindingName: String,
            val importDeclaration: ES6ImportDeclaration?
        ) : CssContainer() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is ImportedFile) return false
                return psiFile == other.psiFile &&
                        virtualFile == other.virtualFile &&
                        importBindingName == other.importBindingName
            }
            override fun hashCode(): Int {
                var result = psiFile.hashCode()
                result = 31 * result + virtualFile.hashCode()
                result = 31 * result + importBindingName.hashCode()
                return result
            }
            override fun toString(): String {
                return "ImportedFile(psiFile=$psiFile, vf=$virtualFile, binding=$importBindingName)"
            }
        }

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

        // 完全依赖 IntelliJ JS PSI 的 reference.resolve()
        // 它本身已经做了 scope-aware 查找，优先最近的声明，不需要我们手动逐层搜索
        val resolved = (qualifierExpr as? JSReferenceExpression)?.reference?.resolve()
        if (resolved != null) {
            val (container, name) = fromResolvedBinding(resolved, qualifierName)
            if (container != null) return container to name
        }

        // Vue fallback: 只有 template 中的 $style/$xxx 需要特殊处理
        // 因为 Vue template 中 $style 不是通过 JS 声明的，是由 Vue 编译器注入
        if (contextFile?.name?.endsWith(".vue") == true && contextFile is XmlFile) {
            val templateTag = Util.findTagInFile(contextFile, "template")
            if (templateTag != null && PsiTreeUtil.isAncestor(templateTag, qualifierExpr, false)) {
                // $style / $xxx (module alias) 是 Vue 注入，没有 JS 声明需要自己找
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
            }
        }

        // 没有 resolve 结果，说明 qualifierExpr 不是 JSReferenceExpression 或 resolve 失败
        // 此时 fallback 检查 setup script 中同名变量（Vue useCssModule 场景）
        if (contextFile != null && qualifierExpr is JSReferenceExpression) {
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
     *
     * 注意：每个 qualifier 都通过 PSI resolve 确认其指向的 CssContainer 与传入的 container
     * 相同，**不依赖**名称匹配。这避免了本地变量（如 `const styles = { card: 'card' }`）
     * 与 CSS Module import 同名时产生的误判。
     */
    fun scanUsages(sourceFile: PsiFile, container: CssContainer): Pair<MutableSet<String>, Boolean /* hasDynamic */> {
        val used = mutableSetOf<String>()
        var dynamic = false

        // 1. JSIndexedPropertyAccessExpression: styles["foo"]
        PsiTreeUtil.findChildrenOfType(sourceFile, JSIndexedPropertyAccessExpression::class.java).forEach { idx ->
            val q = idx.qualifier ?: return@forEach
            val (c, _) = resolveQualifier(q, sourceFile) ?: return@forEach
            if (c != container) return@forEach
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

        // 2. JSReferenceExpression with qualifier: styles.foo
        PsiTreeUtil.findChildrenOfType(sourceFile, JSReferenceExpression::class.java).forEach { ref ->
            val q = ref.qualifier ?: return@forEach
            val (c, _) = resolveQualifier(q, sourceFile) ?: return@forEach
            if (c != container) return@forEach
            val name = ref.referenceName ?: return@forEach
            if (name == "let" || name == "const" || name == "var") return@forEach
            val kebab = if (name.contains("-")) name else Util.camelToKebab(name)
            used += kebab
        }

        // 3. Vue template 属性值 fallback（当 Vue 插件未将 $style.xxx 解析为 JS PSI 时）
        //    扫描 <template> 内所有属性值，提取 $alias.xxx 和 $alias["xxx"] 模式
        if (container is CssContainer.VueStyleTag && sourceFile is XmlFile) {
            val alias = container.moduleAlias  // "$style" 或 "$xxx"
            scanVueTemplateAttributes(sourceFile, alias, used, dynamicRef = { dynamic = true })
        }

        return used to dynamic
    }

    /**
     * 扫描 Vue XML 文件 template 标签内的属性值，提取 module alias 引用。
     * 用于 Vue 插件未将 template 表达式解析为 JS PSI 的 fallback。
     */
    private fun scanVueTemplateAttributes(
        vueFile: XmlFile,
        alias: String,
        used: MutableSet<String>,
        dynamicRef: () -> Unit
    ) {
        val templateTag = Util.findTagInFile(vueFile, "template") ?: return
        val aliasDollar = if (alias.startsWith("\$")) alias else "\$$alias"
        // 匹配 $alias.xxx 或 $alias["xxx"] 或 $alias['xxx']
        val memberPattern = Regex("""\Q$aliasDollar\E\s*\.\s*([a-zA-Z_]\w*)""")
        val bracketPattern = Regex("""\Q$aliasDollar\E\s*\[\s*"([^"]*)"\s*\]""")
        val bracketSinglePattern = Regex("""\Q$aliasDollar\E\s*\[\s*'([^']*)'\s*\]""")
        // 匹配任何 $alias[ 开头但没有引号的情况，即动态引用
        val openBracketPattern = Regex("""\Q$aliasDollar\E\s*\[\s*(?!["'])""")

        for (attr in PsiTreeUtil.findChildrenOfType(templateTag, XmlAttribute::class.java)) {
            val value = attr.value ?: continue
            // 静态字符串成员
            for (m in memberPattern.findAll(value)) {
                val name = m.groupValues[1]
                val kebab = if (name.contains("-")) name else Util.camelToKebab(name)
                used += kebab
            }
            for (m in bracketPattern.findAll(value)) {
                used += m.groupValues[1]
            }
            for (m in bracketSinglePattern.findAll(value)) {
                used += m.groupValues[1]
            }
            // 动态引用 $alias[expr]（expr 不是字符串字面量）：
            // 匹配 $alias[ 之后第一个字符不是引号，说明是变量表达式
            if (openBracketPattern.containsMatchIn(value)) {
                dynamicRef()
            }
        }
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
