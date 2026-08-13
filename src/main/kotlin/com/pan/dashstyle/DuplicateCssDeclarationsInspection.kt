package com.pan.dashstyle

import com.intellij.codeInspection.*
import com.intellij.lang.css.CSSLanguage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import com.intellij.psi.css.*
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
                // CSS/SCSS/LESS 文件
                if (file is StylesheetFile) {
                    inspectStyleScope(file.stylesheet, file, holder, ScopeKind.FILE(file))
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
            val decls = PsiTreeUtil.findChildrenOfType(rs.block, CssDeclaration::class.java).toList()
            if (decls.size < 1) return@mapNotNull null
            val signature = normalizeSignature(decls)
            if (signature.isBlank()) return@mapNotNull null
            Entry(rs, decls, signature)
        }

        // 按签名分组，只看 size >= 2 的组
        val groups = entries.groupBy { it.signature }.filterValues { it.size >= 2 }
        if (groups.isEmpty()) return

        for ((_, group) in groups) {
            for (entry in group.drop(1)) {
                // 高亮整个 block，而非 selector，因为问题出在 declarations 内容
                val range = entry.ruleset.block ?: continue
                val count = group.size
                val commonDecl = group.first().declarations.joinToString("\n", limit = 3) {
                    "  ${it.text}"
                } + (if (group.first().declarations.size > 3) "\n  ..." else "")
                val msg = "$count rules share identical declarations:\n$commonDecl"
                val fixable = when (kind) {
                    is ScopeKind.FILE -> true
                    is ScopeKind.VUE_STYLE -> true
                }
                val fixes: Array<LocalQuickFix> = if (fixable)
                    arrayOf(ExtractCommonRuleQuickFix(group.map { it.ruleset }, group.first().declarations))
                else
                    emptyArray()
                holder.registerProblem(
                    range,
                    msg,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    *fixes
                )
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

    // ================================================================
    // QuickFix
    // ================================================================
    private class ExtractCommonRuleQuickFix(
        private val duplicates: List<CssRuleset>,
        private val commonDeclarations: List<CssDeclaration>
    ) : LocalQuickFix {
        override fun getName(): String = "Extract ${commonDeclarations.size} shared declarations into a new common class (with @extend)"
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

            val insertionTarget = findInsertionRoot(duplicates.first()) ?: return
            val preprocessorName = determinePreprocessor(duplicates.first().containingFile)

            WriteCommandAction.writeCommandAction(project).withName("Extract common CSS class").run<Nothing> {
                // 1) 追加公共 class
                val declarationsText = commonDeclarations.joinToString("\n") { d ->
                    val prop = d.propertyName ?: ""
                    val value = d.value?.text ?: ""
                    "  $prop: $value;"
                }
                val ruleText = "\n.$className {\n$declarationsText\n}\n"
                appendTextToRoot(project, insertionTarget, ruleText)

                // 2) 在每个重复 ruleset 中，删除重复声明，添加 @extend（或使用 CSS 原生并集选择器兜底）
                for (rs in duplicates) {
                    val sigsToRemove = commonDeclarations.map { normalizeRuleSig(it) }.toSet()
                    val existingAll = PsiTreeUtil.findChildrenOfType(rs.block, CssDeclaration::class.java).toList()
                    for (d in existingAll) {
                        if (normalizeRuleSig(d) in sigsToRemove) d.delete()
                    }
                    val useExtend = preprocessorName in setOf("scss", "sass", "less")
                    if (useExtend) {
                        val extText = "@extend .$className;"
                        val line = createCssLine(project, insertionTarget, extText)
                        val anchor = rs.block?.firstChild
                        if (anchor != null) rs.block?.addBefore(line, anchor)
                    }
                    // 原生 CSS 没有 extend — 不再改选择器为并集（那需要对用户语义判断，保守起见只删重复声明，用户自己决定）
                }
            }
        }

        private fun normalizeRuleSig(d: CssDeclaration): String {
            val p = d.propertyName?.trim()?.lowercase().orEmpty()
            val v = d.value?.text?.trim()?.replace(Regex("""\s+"""), " ")?.lowercase().orEmpty()
            return "$p:$v"
        }

        private fun determinePreprocessor(file: PsiFile?): String {
            val n = file?.name?.lowercase() ?: return "css"
            return when {
                n.endsWith(".scss") -> "scss"
                n.endsWith(".sass") -> "sass"
                n.endsWith(".less") -> "less"
                else -> "css"
            }
        }

        private fun findInsertionRoot(sample: CssRuleset): PsiElement? {
            // 找最顶层的 ancestor（stylesheet / style tag / file），在其最后插入
            var root: PsiElement = sample
            while (true) {
                val p = root.parent
                if (p == null || p is PsiFile || p is XmlTag) break
                root = p
            }
            return root.parent ?: root
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
}
