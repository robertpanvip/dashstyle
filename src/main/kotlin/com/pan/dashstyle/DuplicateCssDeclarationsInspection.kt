package com.pan.dashstyle

import com.intellij.codeInspection.*
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.css.CSSLanguage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import com.intellij.psi.css.*
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.openapi.fileTypes.PlainTextLanguage

/**
 * #2. 单文件重复 CSS declarations 检测（变黄 + 波浪线）。
 *
 * 只针对单个 CSS/SCSS/LESS 文件或单个 Vue <style> 块内部，不做跨文件检测。
 * 只比较 declaration "属性:归一化值" 序列的精确等价，避免 false-positive。
 *
 * QuickFix：
 *  让用户输入一个新的公共类名 commonName → 在文件末尾追加 `.commonName { 相同声明 }`
 *  → 在所有重复 ruleset 中删除这些重复声明，改为 prepend `@extend .commonName;`
 *     （非 CSS/SCSS/LESS 文件则提示不支持，因为原生 CSS 没有 extend 语法 — 此时降级为：
 *       直接用选择器并集 `.A, .B { ... }`，并将各自 ruleset 内的原声明删除）
 */
class DuplicateCssDeclarationsInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "DashStyle"
    override fun getDisplayName(): String = "Duplicate CSS declarations (single file)"
    // 同 UnusedCssModuleClassInspection：shortName 完全由 plugin.xml 提供，
    // 保证 CSS / SCSS / LESS 三条语言维度注册的 shortName 各自独立、全局唯一。
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                // CSS/SCSS/LESS 文件（兼容不同版本的 Scss/Less File 实现，用反射+后缀兜底）
                if (isStylesheetLike(file)) {
                    inspectStyleScope(stylesheetRoot(file), file, holder, ScopeKind.FILE(file))
                    return
                }
                // Vue <style> 标签（每个 style 独立检查，避免不同块混用）
                if (file.name?.endsWith(".vue") == true) {
                    val styles = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
                        .filter { it.name.equals("style", true) }
                    for (s in styles) inspectStyleScope(s, file, holder, ScopeKind.VUE_STYLE(s))
                }
            }
        }
    }

    private fun isStylesheetLike(file: PsiFile): Boolean {
        if (file is StylesheetFile) return true
        val rc = file.javaClass.name
        if (rc.contains("StylesheetFile", true) || rc.contains("CssFile", true) ||
            rc.contains("ScssFile", true) || rc.contains("LessFile", true) ||
            rc.contains("SassFile", true)) return true
        val ext = file.virtualFile?.extension?.lowercase()
        return ext in setOf("css", "scss", "sass", "less")
    }

    private fun stylesheetRoot(file: PsiFile): PsiElement {
        return (file as? StylesheetFile)?.stylesheet
            ?: runCatching {
                val m = file.javaClass.methods.firstOrNull { it.name == "getStylesheet" && it.parameterCount == 0 }
                m?.invoke(file) as? PsiElement
            }.getOrNull() ?: file
    }

    // ================================================================
    // 核心：signature = List<String>, 每个 entry 是归一化的 "prop:value"
    // ================================================================
    private sealed class ScopeKind {
        data class FILE(val file: PsiFile) : ScopeKind()
        data class VUE_STYLE(val tag: XmlTag) : ScopeKind()
    }

    private fun inspectStyleScope(root: PsiElement, contextFile: PsiFile, holder: ProblemsHolder, kind: ScopeKind) {
        val rulesets = PsiTreeUtil.findChildrenOfType(root, CssRuleset::class.java)
            .filter { rs -> rs.block != null }
        if (rulesets.size < 2) return

        // ruleset → 归一化声明签名
        data class Entry(val ruleset: CssRuleset, val declarations: List<CssDeclaration>, val signature: String)
        val entries = rulesets.mapNotNull { rs ->
            val decls = directDeclarations(rs.block)
            if (decls.size < 1) return@mapNotNull null
            val signature = normalizeSignature(decls)
            if (signature.isBlank()) return@mapNotNull null
            Entry(rs, decls, signature)
        }

        // 按签名分组，只看 size >= 2 的组
        val groups = entries.groupBy { it.signature }.filterValues { it.size >= 2 }
        if (groups.isEmpty()) return

        for ((_, group) in groups) {
            // 重复组的每一处都高亮 + 带 QuickFix（之前只高亮 group.drop(1)，用户把光标放在第一个 ruleset 上
            // 时看不到任何 warning，导致「没实现」的错觉；现在整组所有位置都显式标黄。）
            val fixes: Array<LocalQuickFix> =
                arrayOf(ExtractCommonRuleQuickFix(group.map { it.ruleset }, group.first().declarations))
            val count = group.size
            val commonDecl = group.first().declarations.joinToString("\n", limit = 3) { "  ${it.text}" } +
                (if (group.first().declarations.size > 3) "\n  ..." else "")
            val msg = "$count rules share identical declarations:\n$commonDecl"
            for (entry in group) {
                val range = entry.ruleset.block ?: continue
                holder.registerProblem(range, msg, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, *fixes)
            }
        }
    }

    /** 归一化：属性按字母序 + 每个属性值去除多余空格 + 色值#rgb→#rrggbb + 去掉尾随逗号 */
    private fun normalizeSignature(decls: List<CssDeclaration>): String {
        val tokens = decls.mapNotNull { d ->
            val p = d.propertyName?.trim()?.lowercase() ?: return@mapNotNull null
            val v = normalizeValue(d.value?.text?.trim() ?: return@mapNotNull null)
            "$p:$v"
        }.sorted()
        return tokens.joinToString("|")
    }

    private fun normalizeValue(raw: String): String {
        var s = raw
        // 色值 #rgb → #rrggbb
        val hex3 = Regex("""#([0-9a-fA-F]{3})(?![0-9a-fA-F])""")
        s = hex3.replace(s) { m ->
            val c = m.groupValues[1]
            "#${c[0]}${c[0]}${c[1]}${c[1]}${c[2]}${c[2]}"
        }
        // 规范化空格：多余 whitespace / tab → 单空格
        s = s.replace(Regex("""\s+"""), " ").trim()
        // 去尾随逗号
        s = s.removeSuffix(",")
        return s.lowercase()
    }

    /**
     * 只取 block 的直接 CssDeclaration 子节点（不递归进嵌套 ruleset）。
     * 避免把 &:hover / &-active 等嵌套块内的声明当作父 block 的声明参与重复检测。
     */
    private fun directDeclarations(block: CssBlock?): List<CssDeclaration> =
        Companion.directDeclarationsStatic(block)

    // ================================================================
    // QuickFix
    // ================================================================
    private class ExtractCommonRuleQuickFix(
        private val duplicates: List<CssRuleset>,
        private val commonDeclarations: List<CssDeclaration>
    ) : LocalQuickFix {
        override fun getName(): String {
            val strategy = duplicates.firstOrNull()?.let { quickFixStrategy(it) } ?: "shared selector"
            return "Extract ${commonDeclarations.size} shared declarations into a new common class ($strategy)"
        }
        override fun getFamilyName(): String = "DashStyle: Extract common CSS class"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val input = Messages.showInputDialog(
                project,
                "Name the new shared CSS class (kebab-case recommended):",
                "Extract common CSS class",
                Messages.getQuestionIcon(),
                "shared-" + commonDeclarations.firstOrNull()?.propertyName?.replace(' ', '-').orEmpty(),
                null
            )
            if (input.isNullOrBlank()) return
            val className = input.trim().trimStart('.')
            if (!className.matches(Regex("""^[_a-zA-Z][_a-zA-Z0-9-]*$"""))) {
                Messages.showWarningDialog(project, "Invalid class name.", "Extract common class")
                return
            }

            val insertOffset = computeInsertionOffset(duplicates) ?: return

            WriteCommandAction.writeCommandAction(project).withName("Extract common CSS class").run<Nothing> {
                val declarationsText = commonDeclarations.joinToString("\n") { d ->
                    val prop = d.propertyName ?: ""
                    val value = d.value?.text ?: ""
                    "  $prop: $value;"
                }
                val ruleText = "\n.$className {\n$declarationsText\n}\n"

                val file = duplicates.firstOrNull()?.containingFile ?: return@run
                val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return@run

                // 收集所有文本修改，从后往前应用，避免偏移错乱。
                // 原先用 PSI addBefore + createCssLine 插入引用：在 less/scss 里 mixin 调用
                // （.shared-name(); / @extend）不是合法 CSS 声明，CSS PSI 解析不了，createCssLine
                // 会回退返回整个 `.__tmp__ { ... }` ruleset，导致把临时 ruleset 原样插进 block，
                // 产生 `.x .__tmp__ { ... } {; }` 的垃圾输出。这里改为纯文本重写，彻底规避。
                data class Edit(val start: Int, val end: Int, val text: String)
                val edits = mutableListOf<Edit>()

                val sigsToRemove = commonDeclarations.map { normalizeRuleSig(it) }.toSet()
                for (rs in duplicates) {
                    val block = rs.block ?: continue
                    val ref = when (preprocessorOf(rs)) {
                        "less" -> ".$className();"
                        "scss", "sass" -> "@extend .$className;"
                        else -> null
                    }
                    // 逐个处理**直接声明**：匹配到的共享声明替换为引用/删除，其它直接声明不动。
                    // 关键：只改动声明的 textRange，绝不动 nested ruleset（嵌套块必须原样保留，
                    // 否则 `.publish-container` 里的 `.space-between-flex` 等子块会被整块重建弄丢）。
                    val decls = directDeclarationsStatic(block)
                    var insertedRef = false
                    for (d in decls) {
                        if (normalizeRuleSig(d) !in sigsToRemove) continue
                        val r = d.textRange
                        if (ref != null && !insertedRef) {
                            edits.add(Edit(r.startOffset, r.endOffset, ref))
                            insertedRef = true
                        } else {
                            edits.add(Edit(r.startOffset, r.endOffset, ""))
                        }
                    }
                }

                // 在最近公共父作用域内追加公共 class（确保 mixin/@extend 调用在其定义之后，且 var 引用仍在作用域内）
                edits.add(Edit(insertOffset, insertOffset, ruleText))

                for (e in edits.sortedByDescending { it.start }) {
                    doc.replaceString(e.start, e.end, e.text)
                }
                // 不在 write action 内调用 doPostponedOperationsAndUnblockDocument：
                // 它会同步派发 AWT 事件，导致 "AWT events are not allowed inside write action" 错误。
            }
        }

        private fun normalizeRuleSig(d: CssDeclaration): String {
            val p = d.propertyName?.trim()?.lowercase().orEmpty()
            val v = d.value?.text?.trim()?.replace(Regex("""\s+"""), " ")?.lowercase().orEmpty()
            return "$p:$v"
        }

        /** 该 ruleset 对应的「合并引用」语法策略（供 fix 名称与插入逻辑共用）。 */
        private fun quickFixStrategy(rs: CssRuleset): String {
            return when (preprocessorOf(rs)) {
                "less" -> "LESS mixin call"
                "scss", "sass" -> "@extend"
                else -> "shared selector"
            }
        }

        /** 具体某个 ruleset 的预处理语言；对 Vue <style lang> 也识别（不再只看文件扩展名）。 */
        private fun preprocessorOf(rs: CssRuleset): String = determinePreprocessor(rs.containingFile, rs)

        private fun determinePreprocessor(file: PsiFile?, sample: CssRuleset? = null): String {
            val n = file?.name?.lowercase() ?: return "css"
            if (n.endsWith(".scss")) return "scss"
            if (n.endsWith(".sass")) return "sass"
            if (n.endsWith(".less")) return "less"
            // Vue SFC：样式写在 <style lang="..."> 里，扩展名是 .vue，须向上找 <style> 的 lang 属性
            if (n.endsWith(".vue") && sample != null) {
                var tag = PsiTreeUtil.getParentOfType(sample, XmlTag::class.java)
                while (tag != null) {
                    if (tag.name.equals("style", true)) {
                        val lang = tag.getAttributeValue("lang")?.lowercase()
                        if (lang?.contains("less") == true) return "less"
                        if (lang?.contains("scss") == true || lang?.contains("sass") == true) return "sass"
                        return "css"
                    }
                    tag = tag.parentTag
                }
            }
            return "css"
        }

        /**
         * 决定公共 class 的插入位置：放到这组重复 ruleset 的**最近公共父作用域**内，
         * 而不是总提到文件顶层。这样：
         *  1) 值里引用的 CSS 变量（var(...)）、LESS/SCSS 变量仍在原作用域内，不会失效；
         *  2) mixin / @extend 调用点都在该类定义之后（LESS 不允许使用尚未定义的要 mixin）。
         * 当重复规则都位于顶层（无共同父 ruleset）时，回退到文件/tag 根的最前面。
         */
        private fun computeInsertionOffset(duplicates: List<CssRuleset>): Int? {
            val lca = commonAncestorRuleset(duplicates)
            if (lca != null) {
                val block = lca.block ?: return null
                // 插到该块开 `{` 之后，保证在调用点之前定义
                return block.textRange.startOffset + 1
            }
            val root = findInsertionRoot(duplicates.first()) ?: return null
            return root.textRange.startOffset
        }

        /** 最近公共父 ruleset（越深越近）；无则返回 null（代表都在顶层）。 */
        private fun commonAncestorRuleset(duplicates: List<CssRuleset>): CssRuleset? {
            var common: Set<CssRuleset>? = null
            for (rs in duplicates) {
                // 候选链 = 从根到自身（含自身）：当某个重复规则本身就是另一个重复规则的祖先时，
                // LCA 应是该重复规则本身，不能因为它的祖先链为空而被判定为「顶层」。
                val chain = (ancestorRulesets(rs) + rs).toSet()
                common = if (common == null) chain else common.intersect(chain)
                if (common.isEmpty()) return null
            }
            // 取深度最大的公共祖先
            val candidates = common ?: return null  // duplicates 非空时不可能为 null，这里仅作编译器兜底
            return candidates.maxByOrNull { ancestorRulesets(it).size }
        }

        /** 自底向上的祖先 CssRuleset 链（不含自己）。 */
        private fun ancestorRulesets(rs: CssRuleset): List<CssRuleset> {
            val list = mutableListOf<CssRuleset>()
            var p = rs.parent
            while (p != null) {
                if (p is CssRuleset) list.add(p)
                p = p.parent
            }
            return list
        }

        private fun findInsertionRoot(sample: CssRuleset): PsiElement? {
            // 找最顶层的 ancestor（stylesheet / style tag / file），在其最后插入
            var root: PsiElement = sample
            while (true) {
                val p = root.parent
                if (p == null || p is PsiFile || p is XmlTag) break
                root = p
            }
            // Vue <style> 内嵌 CSS：parent 是 XmlTag（<style>）时，返回 root 本身
            // （CSS 根元素），其 textRange.endOffset 在 </style> 之前，确保插入在标签内。
            // 独立 CSS 文件：parent 是 PsiFile，返回 PsiFile 在文件末尾插入。
            val parent = root.parent
            return if (parent is XmlTag) root else (parent ?: root)
        }

        private fun appendTextToRoot(project: Project, root: PsiElement, ruleText: String) {
            val factory = PsiFileFactory.getInstance(project)
            val tmp = try {
                factory.createFileFromText("__dashstyle_tmp__.css", CSSLanguage.INSTANCE, ruleText)
            } catch (_: Throwable) {
                factory.createFileFromText("__dashstyle_tmp__.css", PlainTextLanguage.INSTANCE, ruleText)
            }
            val last = root.lastChild
            if (last != null) root.addAfter(tmp.firstChild, last) else root.add(tmp.firstChild)
        }

        private fun createCssLine(project: Project, anchor: PsiElement, text: String): PsiElement {
            val factory = PsiFileFactory.getInstance(project)
            val tmp = factory.createFileFromText(
                "__dashstyle_line__.css",
                CSSLanguage.INSTANCE,
                ".__tmp__ { $text }"
            )
            val rs = PsiTreeUtil.findChildrenOfType(tmp, CssRuleset::class.java).first()
            return PsiTreeUtil.findChildrenOfType(rs.block, PsiElement::class.java)
                .firstOrNull { it.text.contains(text) } ?: tmp.firstChild
        }
    }

    // ================================================================
    // 给 DashStyleHighlightAnnotator（作为 Annotator 直接着色）和 StaticGlobalHighlightVisitor（无 holder）的公开入口
    // ================================================================
    companion object {

        /**
         * Annotator 路径（DashStyleHighlightAnnotator）使用：给 block 段画 WEAK_WARNING 波浪下划线。
         * DashStyleHighlightAnnotator 里的 DUPLICATE_CSS_BLOCK_KEY 已经绑定 JBColor WEAK_WARNING_ATTRIBUTES（波浪线无背景色），
         * 不会和 declaration 文字颜色冲突。
         */
        @JvmStatic
        fun attachDuplicateWave(rs: CssRuleset, holder: AnnotationHolder) {
            val cssFile = rs.containingFile ?: return
            val block = rs.block ?: return
            // 把整个 file 扫一遍分组（性能：走文件级 CachedValue，避免每 ruleset 重复分组计算）
            val snap = groupedSnapshot(cssFile)
            val mySig = runCatching {
                val decls = directDeclarationsStatic(block)
                normalizeSignatureStatic(decls)
            }.getOrNull() ?: return
            if (mySig.isBlank()) return
            val group = snap[mySig] ?: return
            if (group.size < 2) return
            val count = group.size
            val commonDecl = group.first().declarations.joinToString("\n", limit = 3) { "  ${it.text}" } +
                (if (group.first().declarations.size > 3) "\n  ..." else "")
            val msg = "$count rules share identical declarations:\n$commonDecl"
            runCatching {
                holder.newAnnotation(HighlightSeverity.WEAK_WARNING, msg)
                    .range(block.textRange)
                    .textAttributes(DashStyleHighlightAnnotator.DUPLICATE_CSS_BLOCK_KEY)
                    .create()
            }
        }

        /**
         * 给 StaticGlobalHighlightVisitor（visit 无 holder）使用的 Inspection 版登记入口：
         *  复用 buildVisitor 里的 inspectStyleScope 逻辑，改用 InspectionManager.createProblemDescriptor +
         *  ProblemHighlightType.GENERIC_ERROR_OR_WARNING（IDE 会用标准弱警告色渲染，且带 Extract common QuickFix）。
         */
        @JvmStatic
        fun inspectRulesetAndRegisterProblems(rs: CssRuleset, project: Project) {
            val file = rs.containingFile ?: return
            val mgr = com.intellij.codeInspection.InspectionManager.getInstance(project)

            // 简洁做法：用文件级 CachedValue 拿分组结果：O(N) 一次共享，避免对每个 visit 的 ruleset 都全文件扫描（O(N²)）。
            val groups = groupedSnapshot(file).filterValues { it.size >= 2 }
            if (groups.isEmpty()) return
            for ((_, group) in groups) {
                val fixes: Array<LocalQuickFix> = arrayOf(
                    ExtractCommonRuleQuickFixWrapper(group.map { it.ruleset }, group.first().declarations)
                )
                val count = group.size
                val commonDecl = group.first().declarations.joinToString("\n", limit = 3) { "  ${it.text}" } +
                    (if (group.first().declarations.size > 3) "\n  ..." else "")
                val msg = "$count rules share identical declarations:\n$commonDecl"
                for (e in group) {
                    val range = e.ruleset.block ?: continue
                    runCatching {
                        mgr.createProblemDescriptor(
                            range, msg,
                            fixes.firstOrNull(),
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING, true
                        )
                    }
                }
            }
        }

        // ----------------------------------------------------------------
        // 把 normalizeSignature / normalizeValue 提为 static 方便 Annotator/HighLightVisitor 复用
        // ----------------------------------------------------------------
        @JvmStatic
        fun normalizeSignatureStatic(decls: List<CssDeclaration>): String {
            val tokens = decls.mapNotNull { d ->
                val p = d.propertyName?.trim()?.lowercase() ?: return@mapNotNull null
                val v = normalizeValueStatic(d.value?.text?.trim() ?: return@mapNotNull null)
                "$p:$v"
            }.sorted()
            return tokens.joinToString("|")
        }

        @JvmStatic
        fun normalizeValueStatic(raw: String): String {
            var s = raw
            val hex3 = Regex("""#([0-9a-fA-F]{3})(?![0-9a-fA-F])""")
            s = hex3.replace(s) { m ->
                val c = m.groupValues[1]
                "#${c[0]}${c[0]}${c[1]}${c[1]}${c[2]}${c[2]}"
            }
            s = s.replace(Regex("""\s+"""), " ").trim().removeSuffix(",")
            return s.lowercase()
        }

        /**
         * 只取 block 的直接 CssDeclaration 子节点（不递归进嵌套 ruleset）。
         * companion 版本，供 attachDuplicateWave / groupedSnapshot 使用。
         */
        @JvmStatic
        fun directDeclarationsStatic(block: CssBlock?): List<CssDeclaration> {
            if (block == null) return emptyList()
            val result = ArrayList<CssDeclaration>()
            var child: PsiElement? = block.firstChild
            while (child != null) {
                if (child is CssDeclaration) result.add(child)
                child = child.nextSibling
            }
            return result
        }

        // file-level cache：用 CachedValue 按文件缓存分组结果（依赖只认该文件本身，
        // 其它文件改动不会让本文件的重复分组失效）。替代原先手动 ConcurrentHashMap + PSI 强引用，
        // 避免跨项目持有过期 PSI 的内存泄漏。
        private data class Entry(val ruleset: CssRuleset, val declarations: List<CssDeclaration>, val signature: String)
        private fun groupedSnapshot(cssFile: PsiFile): Map<String, List<Entry>> {
            return CachedValuesManager.getManager(cssFile.project).getCachedValue(cssFile, CachedValueProvider {
                val root = (cssFile as? StylesheetFile)?.stylesheet ?: cssFile
                val rulesets = PsiTreeUtil.findChildrenOfType(root, CssRuleset::class.java).filter { it.block != null }
                val entries = rulesets.mapNotNull { rs ->
                    val decls = directDeclarationsStatic(rs.block)
                    if (decls.isEmpty()) return@mapNotNull null
                    Entry(rs, decls, normalizeSignatureStatic(decls))
                }
                val grouped = entries.groupBy { it.signature }
                CachedValueProvider.Result.create(grouped, cssFile)
            })
        }
    }

    // ExtractCommonRuleQuickFix 是 private class，Companion 访问不到；包一层 bridge。
    private class ExtractCommonRuleQuickFixWrapper(
        private val duplicates: List<CssRuleset>,
        private val commonDeclarations: List<CssDeclaration>
    ) : LocalQuickFix {
        override fun getName(): String = "Extract ${commonDeclarations.size} shared declarations into a new common class"
        override fun getFamilyName(): String = "DashStyle: Extract common CSS class"
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            // 委托：构造一个临时的 DuplicateCssDeclarationsInspection 并触发内部 QuickFix（通过反射调用 private 内部类）
            runCatching {
                val outerCls = DuplicateCssDeclarationsInspection::class.java
                val innerCls = outerCls.declaredClasses.firstOrNull { c ->
                    c.simpleName == "ExtractCommonRuleQuickFix"
                } ?: return@runCatching
                val ctor = innerCls.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return@runCatching
                ctor.isAccessible = true
                // 内部类构造的第一个参数是 outer instance；这里用一个 outer 临时实例即可
                val outerInstance = DuplicateCssDeclarationsInspection()
                val fix = ctor.newInstance(outerInstance, duplicates, commonDeclarations) as? LocalQuickFix ?: return@runCatching
                fix.applyFix(project, descriptor)
            }
        }
    }
}
