package com.pan.dashstyle

import com.intellij.codeInspection.*
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.css.CssFile
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.css.StylesheetFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * #1. 未使用的 CSS Module class 置灰（像 dead code 一样变灰）
 *
 *  工作范围：仅对能明确识别为 CSS Module 的容器进行检查：
 *   a) 被 JS/TS import 的 *.module.(css|scss|less|sass)（ES6 import 默认绑定）
 *   b) Vue SFC 中的 <style module> 标签
 *  全局 CSS（未被 import，或 import 了但不是 module 后缀的）不处理，避免误伤。
 *
 *  发现动态引用（styles[expr]）时放弃置灰，防止误报。
 */
class UnusedCssModuleClassInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "DashStyle"
    override fun getDisplayName(): String = "Unused CSS Module class"
    // shortName 直接交给 plugin.xml 的 <localInspection shortName="..."> 声明，
    // 不再在代码里硬编码，避免 CSS/SCSS/LESS 三条语言注册共用同一个 class 时发生 shortName 冲突。
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {

            // ---------- Case A: 访问 JS/TS 文件时，扫描 import 绑定，定位目标 CSS，再遍历 CSS ----------
            // ---------- Case B: 访问 StylesheetFile 时，反向查找所有引用它的 sourceFile，扫描 usages ----------
            override fun visitFile(file: PsiFile) {
                when {
                    file is JSFile -> processJsFile(file, holder)
                    file is XmlFile && file.name.endsWith(".vue") -> processVueFile(file, holder)
                    file is StylesheetFile -> processStylesheetFile(file, holder)
                    else -> { /* 其他文件类型跳过 */ }
                }
            }
        }
    }

    // ================================================================
    // JS/TS：import styles from './Foo.module.css'
    // ================================================================
    private fun processJsFile(jsFile: JSFile, holder: ProblemsHolder) {
        val imports = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(jsFile, ES6ImportDeclaration::class.java)
        for (imp in imports) {
            val moduleText = imp.importModuleText ?: continue
            val from = moduleText.trim('"', '\'')
            if (!MODULE_EXTS.any { from.endsWith(it, ignoreCase = true) }) continue

            val named = imp.namedImports
            val bindings = imp.importedBindings
            val defaultBinding = bindings.firstOrNull { b ->
                named == null || !com.intellij.psi.util.PsiTreeUtil.isAncestor(named, b, false)
            } ?: bindings.firstOrNull() ?: continue

            val viaRef = defaultBinding.reference?.resolve()?.containingFile
            var resolvedPsi: PsiFile? = viaRef
            if (resolvedPsi == null) {
                val parent = jsFile.virtualFile?.parent
                if (parent == null) continue
                val vf = parent.findFileByRelativePath_(from.trimStart('/'))
                    ?: parent.findChild(from.substringAfterLast('/'))
                resolvedPsi = vf?.let { PsiManager.getInstance(jsFile.project).findFile(it) }
                if (resolvedPsi == null) continue
            }
            val vFile = resolvedPsi.virtualFile ?: continue
            val container = CssModuleResolver.CssContainer.ImportedFile(
                resolvedPsi, vFile, defaultBinding.name ?: "styles", imp
            )
            inspectUnusedIn(jsFile, container, holder)
        }
    }

    // ================================================================
    // Vue SFC：<script> 里 import 或 <style module>
    // ================================================================
    private fun processVueFile(vueFile: XmlFile, holder: ProblemsHolder) {
        // <style module> (含命名)
        val styles = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(vueFile, XmlTag::class.java)
            .filter { it.name.equals("style", ignoreCase = true) }
        val moduleStyles = styles.filter { it.getAttribute("module") != null }
        if (moduleStyles.isEmpty()) {
            // 没有任何 <style module>，且 script 里也没有 *.module.* import → 不处理
            // 这里简单起见，也处理下普通 <style> + script import CSS module 的场景（和 JS 文件一致）
            val scriptTag = Util.findScriptTag(vueFile) ?: return
            val imports = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(scriptTag, ES6ImportDeclaration::class.java)
            for (imp in imports) {
                val from = (imp.importModuleText ?: continue).trim('"', '\'')
                if (!MODULE_EXTS.any { from.endsWith(it, ignoreCase = true) }) continue
                val named = imp.namedImports
                val defaultBinding = imp.importedBindings.firstOrNull { b ->
                    named == null || !com.intellij.psi.util.PsiTreeUtil.isAncestor(named, b, false)
                } ?: continue
                val viaRef = defaultBinding.reference?.resolve()?.containingFile ?: continue
                val vf = viaRef.virtualFile ?: continue
                inspectUnusedIn(
                    vueFile,
                    CssModuleResolver.CssContainer.ImportedFile(viaRef, vf, defaultBinding.name ?: "styles", imp),
                    holder
                )
            }
            return
        }
        for (modTag in moduleStyles) {
            val alias = (modTag.getAttributeValue("module")?.takeIf { it.isNotBlank() }?.let { "\$$it" }) ?: ("$" + "style")
            inspectUnusedIn(
                vueFile,
                CssModuleResolver.CssContainer.VueStyleTag(modTag, alias, vueFile),
                holder
            )
        }
    }

    // ================================================================
    // Case B：当直接打开 *.module.css/.scss/.less 文件时，反向找所有引用它的 sourceFiles，然后并集扫描 usage
    // ================================================================
    private fun processStylesheetFile(cssFile: StylesheetFile, holder: ProblemsHolder) {
        val vf = cssFile.virtualFile ?: return
        if (!MODULE_EXTS.any { vf.name.endsWith(it, ignoreCase = true) }) return

        val project = cssFile.project
        // 缓存反向查找结果（按 cssFile VFile URL 为 key），避免每次高亮都扫项目
        val referencingSources: List<Pair<PsiFile, String /* bindingName */>> =
            CachedValuesManager.getManager(project).getCachedValue(
                cssFile,
                CachedValueProvider {
                    val result = findReferencingSourceFiles(cssFile)
                    CachedValueProvider.Result.create(
                        result,
                        cssFile,
                        com.intellij.psi.util.PsiModificationTracker.MODIFICATION_COUNT
                    )
                }
            )

        if (referencingSources.isEmpty()) return  // 没被任何 JS/Vue import → 非 CSS Module，不置灰

        val container = CssModuleResolver.CssContainer.ImportedFile(cssFile, vf, "", null)
        val classes = CssModuleResolver.collectAllClasses(container)
        if (classes.isEmpty()) return

        val used = mutableSetOf<String>()
        var hasDynamic = false
        for ((srcFile, bindingNameHint) in referencingSources) {
            val effectiveContainer = CssModuleResolver.CssContainer.ImportedFile(cssFile, vf, bindingNameHint.ifBlank { "styles" }, null)
            val (u, dyn) = CssModuleResolver.scanUsages(srcFile, effectiveContainer)
            if (dyn) { hasDynamic = true; break }
            used += u
        }
        if (hasDynamic) return

        val internalReferenced = collectInternalReferences(classes)
        used += internalReferenced

        for (entry in classes) {
            if (entry.kebabName in used) continue
            val selector = entry.ruleset.selectorList ?: continue
            holder.registerProblem(
                selector,
                "CSS class `.${entry.kebabName}` is not used anywhere",
                ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                RemoveRuleQuickFix(entry.kebabName)
            )
        }
    }

    /** 在整个 project 范围内反查：所有通过 ES6 import 引入当前 cssFile 的 JS/Vue 源文件 */
    private fun findReferencingSourceFiles(cssFile: StylesheetFile): List<Pair<PsiFile, String>> {
        val project = cssFile.project
        val cssVf = cssFile.virtualFile ?: return emptyList()
        val cssPath = cssVf.path
        val cssName = cssVf.name
        val out = mutableListOf<Pair<PsiFile, String>>()
        val seen = mutableSetOf<String>()

        ApplicationManager.getApplication().runReadAction {
            val scope = GlobalSearchScope.projectScope(project)
            // 候选文件：所有可能包含 import 的 JS/TS/Vue 文件。先用文件名索引取交集，避免扫全局
            val fileNames = hashSetOf<String>()
            runCatching {
                val idx = ProjectFileIndex.getInstance(project)
                idx.iterateContent { vf ->
                    val n = vf.name
                    if (vf.isValid && !vf.isDirectory && (
                        n.endsWith(".js") || n.endsWith(".jsx") ||
                            n.endsWith(".ts") || n.endsWith(".tsx") ||
                            n.endsWith(".vue"))
                    ) {
                        fileNames += n
                    }
                    true
                }
            }
            val candidatePsiFiles = mutableListOf<PsiFile>()
            for (n in fileNames) {
                val vFiles = runCatching { FilenameIndex.getVirtualFilesByName(n, scope) }.getOrNull() ?: continue
                for (vf in vFiles) {
                    if (!vf.isValid || vf.isDirectory) continue
                    val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
                    candidatePsiFiles += psi
                }
            }

            for (psiFile in candidatePsiFiles) {
                val imports = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(psiFile, ES6ImportDeclaration::class.java)
                if (imports.isEmpty()) continue
                val fileVf = psiFile.virtualFile?.parent ?: continue
                for (imp in imports) {
                    val from = (imp.importModuleText ?: continue).trim('"', '\'')
                    if (!MODULE_EXTS.any { from.endsWith(it, ignoreCase = true) }) continue
                    val resolvedVf = fileVf.findFileByRel2(from.trimStart('/'))
                        ?: fileVf.findChild(from.substringAfterLast('/'))
                        ?: continue
                    if (resolvedVf.path != cssPath && resolvedVf.name != cssName) {
                        // 兜底：同一路径归一化比较
                        val normA = resolvedVf.path.replace('\\', '/').trimEnd('/')
                        val normB = cssPath.replace('\\', '/').trimEnd('/')
                        if (normA != normB) continue
                    }
                    val named = imp.namedImports
                    val defaultBinding = imp.importedBindings.firstOrNull { b ->
                        named == null || !com.intellij.psi.util.PsiTreeUtil.isAncestor(named, b, false)
                    } ?: imp.importedBindings.firstOrNull() ?: continue
                    val key = psiFile.virtualFile?.path.orEmpty() + "#" + (defaultBinding.name ?: "styles")
                    if (seen.add(key)) {
                        out += psiFile to (defaultBinding.name ?: "styles")
                    }
                }
            }
        }
        return out
    }

    // ================================================================
    // 核心逻辑：拿 classes 与 usage 比较
    // ================================================================
    private fun inspectUnusedIn(
        sourceFile: PsiFile,
        container: CssModuleResolver.CssContainer,
        holder: ProblemsHolder
    ) {
        val classes = CssModuleResolver.collectAllClasses(container)
        if (classes.isEmpty()) return

        val (used, hasDynamic) = CssModuleResolver.scanUsages(sourceFile, container)
        if (hasDynamic) return // 动态访问 → 不做判断避免误报

        // 同文件内 ruleset 之间的 @extend / @apply 也应该算 "used"
        val internalReferenced = collectInternalReferences(classes)
        used += internalReferenced

        for (entry in classes) {
            if (entry.kebabName in used) continue
            // 只高亮 selectorList（精确落到 selector 那一行，不包含 block 内容区域）
            val selector = entry.ruleset.selectorList ?: continue
            holder.registerProblem(
                selector,
                "CSS class `.${entry.kebabName}` is not used anywhere",
                ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                RemoveRuleQuickFix(entry.kebabName)
            )
        }
    }

    /** 很简单的 @extend 识别：@extend .foo → foo 被引用 */
    private fun collectInternalReferences(classes: List<CssModuleResolver.ClassEntry>): Set<String> {
        val referenced = mutableSetOf<String>()
        val classNames = classes.map { it.kebabName }.toSet()
        for (entry in classes) {
            val text = entry.ruleset.block?.text ?: continue
            for (candidate in classNames) {
                val pattern = """@extend\s+\.?${Regex.escape(candidate)}(?=[^a-zA-Z0-9_-]|$)""".toRegex()
                if (pattern.containsMatchIn(text)) referenced += candidate
            }
        }
        return referenced
    }

    class RemoveRuleQuickFix(private val className: String) : LocalQuickFix {
        override fun getName(): String = "Remove unused `.${className}` rule"
        override fun getFamilyName(): String = "DashStyle: Remove unused CSS class"
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val rule = descriptor.psiElement.parent as? CssRuleset ?: return
            if (!rule.isPhysical || !rule.isWritable) return
            // 删掉 ruleset，但保留最后一个换行符以防两个 class 粘连
            val next = rule.nextSibling
            rule.delete()
            if (next is PsiWhiteSpace && next.text.startsWith("\n") && next.text.length > 1) {
                // 把多余的换行压缩
                val doc = PsiDocumentManager.getInstance(project).getDocument(rule.containingFile)
                if (doc != null) {
                    val range = next.textRange
                    doc.replaceString(range.startOffset, range.endOffset, "\n")
                }
            }
        }
    }

    companion object {
        private val MODULE_EXTS = listOf(".module.css", ".module.scss", ".module.sass", ".module.less")

        private fun com.intellij.openapi.vfs.VirtualFile.findFileByRelativePath_(rel: String): com.intellij.openapi.vfs.VirtualFile? {
            var cur: com.intellij.openapi.vfs.VirtualFile? = this
            for (seg in rel.replace('\\', '/').split('/')) {
                if (seg.isEmpty() || seg == ".") continue
                if (seg == "..") { cur = cur?.parent; continue }
                cur = cur?.findChild(seg) ?: return null
            }
            return cur
        }

        private fun com.intellij.openapi.vfs.VirtualFile.findFileByRel2(rel: String): com.intellij.openapi.vfs.VirtualFile? {
            return findFileByRelativePath_(rel)
        }
    }
}
