package com.pan.dashstyle

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * 两条职责：
 *   A) 作为 Annotator（plugin.xml 的 <annotator language=CSS/SCSS/LESS> 注册），给 CSS/SCSS/LESS 的 PSI 着色；
 *   B) 作为真正跨语言的 HighlightVisitor（com.intellij.highlightVisitor 扩展点注册），在所有语言/插件（即使 SCSS/LESS 插件未启用）
 *      都能访问编辑器颜色层，确保 DashStyle.Unused置灰 / Duplicate标黄 100% 可视化生效。
 *
 * 为什么需要 B：
 *   LocalInspectionTool.registerProblem(LIKE_UNUSED_SYMBOL) 在部分 WebStorm 发行版只登记 Problems，
 *   不渲染 Editor 字体灰。plugin.xml <annotator language="ANY"/> 在很多 IDE 版本会被语言过滤完全不调用，
 *   因此用真正的全局 HighlightVisitor 保底。
 */
class DashStyleHighlightAnnotator : Annotator,
    // 顺带实现 HighlightVisitor（IntelliJ 标准接口），供 highlightVisitor 扩展点复用同一套逻辑
    com.intellij.codeHighlighting.TextEditorHighlightingPassFactory,
    com.intellij.codeInsight.daemon.impl.HighlightVisitor {

    private val unusedInspection by lazy(LazyThreadSafetyMode.PUBLICATION) { UnusedCssModuleClassInspection() }

    // =================== Annotator 入口 ===================
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val rs = element as? CssRuleset ?: return
        runCatching { annotateUnused(rs, holder) }
        runCatching { annotateDuplicate(rs, holder) }
    }

    // =================== HighlightVisitor 入口（全局） ===================
    override fun suitableForFile(file: com.intellij.psi.PsiFile): Boolean {
        val name = file.name?.lowercase().orEmpty()
        return name.endsWith(".css") || name.endsWith(".scss") || name.endsWith(".sass") ||
            name.endsWith(".less") || name.endsWith(".vue")
    }

    override fun visit(element: PsiElement, holder: AnnotationHolder) {
        // 纯规则：只有 CssRuleset 才处理
        val rs = element as? CssRuleset ?: return
        runCatching { annotateUnused(rs, holder) }
        runCatching { annotateDuplicate(rs, holder) }
    }

    override fun clone(): HighlightVisitor = this

    // =========== TextEditorHighlightingPassFactory（兼容老版本 highlightVisitor 注册方式） ===========
    override fun createHighlightingPass(file: com.intellij.psi.PsiFile, editor: com.intellij.openapi.editor.Editor): com.intellij.codeHighlighting.TextEditorHighlightingPass? = null
    override fun createHighlightingPasses(file: com.intellij.psi.PsiFile, document: com.intellij.openapi.editor.Document, all: Boolean): MutableCollection<com.intellij.codeHighlighting.TextEditorHighlightingPass> = mutableListOf()

    // ================================================================
    // #1 未使用置灰（selector 范围）
    // ================================================================
    private fun annotateUnused(rs: CssRuleset, holder: AnnotationHolder) {
        val cssFile = rs.containingFile ?: return
        val cssVf = cssFile.virtualFile ?: return
        if (!MODULE_EXTS.any { cssVf.name.endsWith(it, ignoreCase = true) }) return

        val snap = runCatching { unusedInspection.snapshotFor(cssFile) }.getOrNull() ?: return
        if (snap.hasDynamic) return
        val classes = extractClassNamesFromRuleset(rs)
        if (classes.isEmpty()) return

        val selector = runCatching { rs.selectorList }.getOrNull() ?: return
        if (!selector.isPhysical) return
        // HighlightVisitor 可能跨文件使用，但我们只在 cssFile 里操作
        val anyUnused = classes.any { it !in snap.used }
        if (!anyUnused) return

        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(selector)
            .textAttributes(UNUSED_CSS_CLASS_KEY)
            .create()
    }

    // ================================================================
    // #2 重复 declarations 标黄（ruleset.block 范围，波浪下划线）
    // ================================================================
    private fun annotateDuplicate(rs: CssRuleset, holder: AnnotationHolder) {
        val file = rs.containingFile ?: return
        val root = runCatching { locateStyleScopeRoot(rs, file) }.getOrNull() ?: return
        val groups = getOrComputeDuplicateGroups(root, file)
        if (groups.isEmpty()) return

        val block = rs.block ?: return
        val sig = normalizeSignature(rs) ?: return
        val group = groups[sig] ?: return
        if (group.size < 2) return

        val msg = "${group.size} rules share identical declarations"
        holder.newSilentAnnotation(HighlightSeverity.WEAK_WARNING)
            .range(block)
            .tooltip(msg)
            .textAttributes(DUPLICATE_CSS_BLOCK_KEY)
            .create()
    }

    private fun locateStyleScopeRoot(rs: CssRuleset, file: PsiElement): PsiElement? {
        if (file is com.intellij.psi.PsiFile) {
            val lower = file.name?.lowercase().orEmpty()
            if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".sass") || lower.endsWith(".less")) {
                return runCatching {
                    val m = file.javaClass.methods.firstOrNull { it.name == "getStylesheet" && it.parameterCount == 0 }
                    (m?.invoke(file) as? PsiElement) ?: file
                }.getOrDefault(file)
            }
            if (lower.endsWith(".vue")) {
                val tag = PsiTreeUtil.getContextOfType(rs, com.intellij.psi.xml.XmlTag::class.java)
                if (tag != null && tag.name.equals("style", ignoreCase = true)) return tag
            }
        }
        return null
    }

    private fun normalizeSignature(rs: CssRuleset): String? {
        val decls = runCatching { PsiTreeUtil.findChildrenOfType(rs.block, CssDeclaration::class.java).toList() }.getOrNull() ?: return null
        if (decls.isEmpty()) return null
        val tokens = decls.mapNotNull { d ->
            val p = d.propertyName?.trim()?.lowercase() ?: return@mapNotNull null
            val v = normalizeVal(d.value?.text?.trim() ?: return@mapNotNull null)
            "$p:$v"
        }.sorted()
        return tokens.joinToString("|").takeIf { it.isNotBlank() }
    }

    private fun normalizeVal(raw: String): String {
        var s = raw
        val hex3 = Regex("""#([0-9a-fA-F]{3})(?![0-9a-fA-F])""")
        s = hex3.replace(s) { m ->
            val c = m.groupValues[1]
            "#${c[0]}${c[0]}${c[1]}${c[1]}${c[2]}${c[2]}"
        }
        return s.replace(Regex("""\s+"""), " ").trim().removeSuffix(",").lowercase()
    }

    private fun extractClassNamesFromRuleset(rs: CssRuleset): List<String> {
        val raw = runCatching { rs.selectorList?.text }.getOrNull().orEmpty().trim()
        if (raw.isEmpty()) return emptyList()
        val normalized = runCatching { Util.expandSelector(rs) }.getOrNull()
            ?: raw.replace('&', ' ').replace(Regex("""\s+"""), " ").trim()
        val cleaned = normalized.replace(Regex(""":+[\w-]+(?:\([^)]*\))?"""), "")
        return CLASS_NAME_RE.findAll(cleaned).mapNotNull { m ->
            val rawName = m.groupValues[1]
            val name = if (rawName.startsWith(".")) rawName.drop(1) else rawName
            name.trim().takeIf { it.isNotEmpty() }
        }.distinct().toList()
    }

    companion object {
        private val MODULE_EXTS = listOf(".module.css", ".module.scss", ".module.sass", ".module.less")
        private val CLASS_NAME_RE = Regex("""(^|[^\w-])\.([_a-zA-Z][_a-zA-Z0-9-]*)(?=[^\w-]|${'$'})""")

        // 未使用：跟随主题的灰（Darcula=浅灰，Light=中灰）
        val UNUSED_CSS_CLASS_KEY: TextAttributesKey = run {
            val fg: Color = JBColor.namedColor(
                "Gutter.foreground",
                JBColor(Color(0x98, 0x98, 0x98), Color(0x9f, 0x9f, 0x9f))
            )
            val fallback = TextAttributes().apply { foregroundColor = fg }
            val baseKey = runCatching {
                val field = DefaultLanguageHighlighterColors::class.java.getField("INLINE_PARAMETER_HINT")
                field.get(null) as? TextAttributesKey
            }.getOrNull() ?: DefaultLanguageHighlighterColors.IDENTIFIER
            TextAttributesKey.createTextAttributesKey("DASHSTYLE_UNUSED_CSS_CLASS", baseKey).also { key ->
                runCatching {
                    val f = TextAttributesKey::class.java.getDeclaredField("myFallbackAttributes")
                        .apply { isAccessible = true }
                    f.set(key, fallback)
                }
            }
        }

        // 重复：黄底 + 波浪下划线（跟随主题 WEAK_WARNING 色 + 自适应）
        val DUPLICATE_CSS_BLOCK_KEY: TextAttributesKey = run {
            val effect: Color = JBColor.namedColor(
                "EditorColors.WEAK_WARNING_ATTRIBUTES",
                JBColor(Color(0xE0, 0xA5, 0x00), Color(0xFF, 0xC1, 0x07))
            )
            val bg: Color = JBColor.namedColor(
                "Notification.warningBackground",
                JBColor(Color(0xFF, 0xF6, 0xD6, 96), Color(0x59, 0x4C, 0x11, 130))
            )
            val fallback = TextAttributes().apply {
                effectType = EffectType.WAVE_UNDERSCORE
                effectColor = effect
                backgroundColor = bg
            }
            val baseKey: TextAttributesKey = runCatching {
                val field = DefaultLanguageHighlighterColors::class.java.getField("WARNINGS_ATTRIBUTES")
                field.get(null) as? TextAttributesKey
            }.getOrNull() ?: DefaultLanguageHighlighterColors.IDENTIFIER
            TextAttributesKey.createTextAttributesKey("DASHSTYLE_DUPLICATE_CSS", baseKey).also { key ->
                runCatching {
                    val f = TextAttributesKey::class.java.getDeclaredField("myFallbackAttributes")
                        .apply { isAccessible = true }
                    f.set(key, fallback)
                }
            }
        }

        private fun getOrComputeDuplicateGroups(root: PsiElement, contextFile: com.intellij.psi.PsiFile): Map<String, List<CssRuleset>> {
            return com.intellij.psi.util.CachedValuesManager.getManager(contextFile.project).getCachedValue(
                contextFile,
                com.intellij.psi.util.CachedValueProvider {
                    val rulesets = PsiTreeUtil.findChildrenOfType(root, CssRuleset::class.java).filter { it.block != null }
                    val bySig = hashMapOf<String, MutableList<CssRuleset>>()
                    for (r in rulesets) {
                        val sig = runCatching {
                            val decls = PsiTreeUtil.findChildrenOfType(r.block, CssDeclaration::class.java).toList()
                            if (decls.isEmpty()) null
                            else decls.mapNotNull { d ->
                                val p = d.propertyName?.trim()?.lowercase() ?: return@mapNotNull null
                                val v = (d.value?.text ?: "").let { s ->
                                    var sv = s
                                    val hex3 = Regex("""#([0-9a-fA-F]{3})(?![0-9a-fA-F])""")
                                    sv = hex3.replace(sv) { m ->
                                        val c = m.groupValues[1]
                                        "#${c[0]}${c[0]}${c[1]}${c[1]}${c[2]}${c[2]}"
                                    }
                                    sv.replace(Regex("""\s+"""), " ").trim().removeSuffix(",").lowercase()
                                }
                                "$p:$v"
                            }.sorted().joinToString("|").takeIf { it.isNotBlank() }
                        }.getOrNull() ?: continue
                        bySig.getOrPut(sig) { mutableListOf() } += r
                    }
                    val result = bySig.filterValues { it.size >= 2 }
                    com.intellij.psi.util.CachedValueProvider.Result.create(
                        result,
                        contextFile,
                        com.intellij.psi.util.PsiModificationTracker.MODIFICATION_COUNT
                    )
                }
            )
        }
    }
}
