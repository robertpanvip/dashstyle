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
import java.awt.Color

/**
 * 独立于 LocalInspectionTool 的 Annotator：不管 ProblemsHolder / Inspection level，直接在编辑器渲染层
 * 给 CSS Module 的「未使用 class」标灰、「重复 declarations 块」标黄带波浪。
 *
 * 背景：用户反馈 LocalInspectionTool 里 ProblemsHolder.registerProblem(LIKE_UNUSED_SYMBOL) 在部分
 * WebStorm 发行版下只登记到 Problems 面板，不渲染文字灰化；我们用 Annotator 保底。
 * Snapshot 复用 UnusedCssModuleClassInspection.snapshotFor（已有 CachedValue），不会重复计算。
 */
class DashStyleHighlightAnnotator : Annotator {

    private val unusedInspection by lazy(LazyThreadSafetyMode.PUBLICATION) { UnusedCssModuleClassInspection() }

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val rs = element as? CssRuleset ?: return
        runCatching { annotateUnused(rs, holder) }
        runCatching { annotateDuplicate(rs, holder) }
    }

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
        if (!selector.isPhysical || selector.containingFile !== cssFile) return

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

        // 未使用：浅灰 + 不画波浪
        val UNUSED_CSS_CLASS_KEY: TextAttributesKey = run {
            val fallback = TextAttributes()
            fallback.foregroundColor = Color(0x98, 0x98, 0x98)
            TextAttributesKey.createTextAttributesKey("DASHSTYLE_UNUSED_CSS_CLASS", DefaultLanguageHighlighterColors.INLINE_PARAMETER_HINT).also { key ->
                runCatching {
                    val f = TextAttributesKey::class.java.getDeclaredField("myFallbackAttributes")
                        .apply { isAccessible = true }
                    f.set(key, fallback)
                }
            }
        }

        // 重复：黄底 + 波浪下划线（复用 WEAK_WARNING 的默认效果）
        val DUPLICATE_CSS_BLOCK_KEY: TextAttributesKey = run {
            val fallback = TextAttributes()
            fallback.effectType = EffectType.WAVE_UNDERSCORE
            fallback.effectColor = Color(0xE0, 0xA5, 0x00)
            fallback.backgroundColor = Color(0xFF, 0xF6, 0xD6, 96)
            // WARNINGS_ATTRIBUTES 在某些平台版本不存在，兜底用更稳定的 IDENTIFIER 作为基准。
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
