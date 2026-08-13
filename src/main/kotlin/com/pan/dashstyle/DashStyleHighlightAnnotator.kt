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
 *   A) 作为 Annotator（plugin.xml 的 <annotator language=CSS/SCSS/LESS> 注册），给 CSS/SCSS/LESS 的 PSI 着色。
 *
 * 之前版本尝试同时实现全局 HighlightVisitor 扩展点（绕过语言过滤保底），但 webstorm-2025.3 SDK 中
 * HighlightVisitor 接口包路径/方法签名发生了变化导致编译失败。
 * 目前 plugin.xml 已经按 CSS / SCSS / LESS 三个语言 ID 分别注册 <annotator>，足以覆盖三大主流预处理器场景；
 * 若后续需要「任何文件类型（含 Vue <style> 内嵌内容）都能触发置灰」，再单独写一个只实现 HighlightVisitor
 * 接口的独立类（通过反射调用其 visit 方法，避免编译期强依赖接口签名）。
 */
class DashStyleHighlightAnnotator : Annotator {

    private val unusedInspection by lazy(LazyThreadSafetyMode.PUBLICATION) { UnusedCssModuleClassInspection() }

    // =================== Annotator 入口 ===================
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val rs = element as? CssRuleset ?: return
        runCatching { annotateUnused(rs, holder) }
        runCatching { annotateDuplicate(rs, holder) }
    }

    // ================================================================
    // #1 未使用置灰：
    //    - 用 expandSelector 展开后的组合名去 snap.used 判断是否"整个 ruleset 未被引用"
    //    - 如果整个 ruleset 都没被用 → 把 selectorList 的绝对范围整个前景置灰（范围严格夹紧到 selectorList）
    //    - 这样兼容 LESS/SCSS 的 &-suffix / &_suffix / 多层嵌套，不会因为原始 selector 里是 &-foo
    //      而在原文中找不到展开后的 parent-foo 字面量导致匹配失败；也不会越界误染到 declaration 里。
    // ================================================================
    private fun annotateUnused(rs: CssRuleset, holder: AnnotationHolder) {
        val cssFile = rs.containingFile ?: return
        val cssVf = cssFile.virtualFile ?: return
        if (!MODULE_EXTS.any { cssVf.name.endsWith(it, ignoreCase = true) }) return

        val snap = runCatching { unusedInspection.snapshotFor(cssFile) }.getOrNull() ?: return
        if (snap.hasDynamic) return

        // Step 1: expandSelector 展开所有选择器组合
        val expandedSelector = runCatching { Util.expandSelector(rs) }.getOrNull().orEmpty()
        if (expandedSelector.isBlank()) return
        val expandedClasses = CLASS_NAME_RE.findAll(expandedSelector).mapNotNull { m ->
            val raw = m.groupValues[2]
            raw.trim().takeIf { it.isNotEmpty() }
        }.distinct().toList()
        if (expandedClasses.isEmpty()) return

        // Step 2: 只有当展开后得到的所有类名 全部都不在 used 集合里，才认为整个 ruleset 未被使用。
        // 如果展开后有多个选择器组合，其中任意组合命中 used，则认为 ruleset 仍在使用中，不置灰避免误伤。
        val allUnused = expandedClasses.all { cls -> cls !in snap.used }
        if (!allUnused) return

        // Step 3: 拿到 selectorList，严格按它的 textRange 画灰（范围不包含 declarations）
        val selectorList = runCatching { rs.selectorList }.getOrNull() ?: return
        if (!selectorList.isPhysical) return
        val slRange = selectorList.textRange
        if (slRange.length <= 0) return

        // 最终夹紧：不超过 containingFile 的长度（理论上不会越界，但为安全起见）
        val fileLen = runCatching { cssFile.textLength }.getOrNull() ?: Int.MAX_VALUE
        val start = slRange.startOffset.coerceAtLeast(0)
        val end = slRange.endOffset.coerceAtMost(start + 1).coerceAtMost(fileLen)
        if (end <= start) return

        runCatching {
            val tr = com.intellij.openapi.util.TextRange(start, end)
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(tr)
                .textAttributes(UNUSED_CSS_CLASS_KEY)
                .create()
        }
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
        // 公开：给 StaticGlobalHighlightVisitor（独立类）、DuplicateCssDeclarationsInspection Companion 直接复用
        @JvmStatic
        val CLASS_NAME_RE: Regex = Regex("""(^|[^\w-])\.([_a-zA-Z][_a-zA-Z0-9-]*)(?=[^\w-]|${'$'})""")

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

        // 重复：波浪下划线（无背景色，避免和 declaration 行内文字颜色/选中高亮冲突；用户只想要黄色波浪「提醒」即可）
        val DUPLICATE_CSS_BLOCK_KEY: TextAttributesKey = run {
            val effect: Color = JBColor.namedColor(
                "EditorColors.WEAK_WARNING_ATTRIBUTES",
                JBColor(Color(0xE0, 0xA5, 0x00), Color(0xFF, 0xC1, 0x07))
            )
            val fallback = TextAttributes().apply {
                effectType = EffectType.WAVE_UNDERSCORE
                effectColor = effect
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
