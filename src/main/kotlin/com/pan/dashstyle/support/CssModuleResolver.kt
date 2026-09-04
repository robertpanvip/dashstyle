package com.pan.dashstyle.support

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding
import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.css.StylesheetFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * CSS Module 核心容器解析层。
 *
 * 职责聚焦于：从 PSI 元素（qualifier / literal / referenceExpression）
 * 解析出对应的 [CssContainer]（目标 CSS 文件/Vue <style>/本地对象字面量）。
 *
 * 文件级操作（查找/创建/import 生成）→ [CssModuleFileResolver]
 * 使用端扫描（JSX/Vue class 引用收集）→ [CssModuleUsageScanner]
 * class → ruleset 解析与遍历 → [CssSelectorResolver]
 */
object CssModuleResolver {

    val MODULE_EXTS = listOf(".module.css", ".module.scss", ".module.sass", ".module.less")

    // ================================================================
    // CssContainer：目标 CSS 容器的统一抽象
    // ================================================================
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

    // ================================================================
    // 入口：从 PsiElement 解析出 CssContainer
    // ================================================================

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

    fun resolveContainerByFile(project: Project, cssVFile: VirtualFile, importBindingName: String): CssContainer.ImportedFile? {
        val psiFile = PsiManager.getInstance(project).findFile(cssVFile) ?: return null
        return CssContainer.ImportedFile(psiFile, cssVFile, importBindingName, null)
    }
}
