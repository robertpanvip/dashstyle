package com.pan.dashstyle.annotator

import com.pan.dashstyle.DashStyleBundle.message
import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.support.*

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * 鼠标悬浮在 styles.fooBar / styles["foo-bar"] 上时，展示对应的完整 CSS ruleset（声明块）。
 * 对 CssRuleset 本身悬浮，也返回展开后选择器 + 声明代码块。
 *
 * 注意：所有 inline style 的颜色都通过 JBColor.namedColor(...) 取当前 IDE 主题配色对应的 key，
 * 避免在 Darcula / Light / High Contrast 等主题下出现紫字配紫底、或浅字配浅底的不可读问题。
 */
class DashStyleDocumentationProvider : AbstractDocumentationProvider() {

    companion object {
        private fun htmlEscape(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

        private fun Color.toCssHex(): String =
            "#%02x%02x%02x".format(red and 0xFF, green and 0xFF, blue and 0xFF)

        // --- 颜色 key：用 JBColor.namedColor 绑定 IDE 现有 LaF key，Darcula/Light/HC 自动切换 ---
        // 选择器（.foo {）：类似 HTML tag name / hyperlink 颜色
        private val COLOR_SELECTOR: JBColor by lazy {
            JBColor.namedColor(
                "Hyperlink.linkForeground",
                JBColor(Color(0x6c, 0x5c, 0xe7), Color(0xa2, 0x9b, 0xfe))
            )
        }
        // 属性名（color:）：类似 HTML 属性色
        private val COLOR_PROPERTY: JBColor by lazy {
            JBColor.namedColor(
                "Attributes.attributeForeground",
                JBColor(Color(0x09, 0x84, 0xe3), Color(0x74, 0xb9, 0xff))
            )
        }
        // 属性值（red）：普通文档前景（跟随主题 label.foreground）
        private val COLOR_VALUE: JBColor by lazy {
            JBColor.namedColor(
                "Label.foreground",
                JBColor(Color(0x2d, 0x34, 0x36), Color(0xdf, 0xe4, 0xe6))
            )
        }
        // 分号 / 次要字符：label.disabledForeground
        private val COLOR_PUNCT: JBColor by lazy {
            JBColor.namedColor("Label.disabledForeground", JBColor(Color(0x63, 0x6e, 0x72), Color(0x95, 0xa5, 0xa6)))
        }
        // 空块注释灰：同 COLOR_PUNCT
        private val COLOR_COMMENT: JBColor by lazy { COLOR_PUNCT }
        // 分隔线：Separator.separatorColor
        private val COLOR_SEPARATOR: JBColor by lazy {
            JBColor.namedColor(
                "Separator.separatorColor",
                JBColor(Color(0xec, 0xf0, 0xf1), Color(0x2f, 0x36, 0x40))
            )
        }
        // footer 小字色：同 COLOR_PUNCT
        private val COLOR_FOOTER: JBColor by lazy { COLOR_PUNCT }
        // 「选择器展开引擎」Quick Doc 弹窗外层背景：跟随 IDE Panel.background（Darcula=深灰，Light=白，HC=高对比）
        private val COLOR_PANEL_BG: JBColor by lazy {
            JBColor.namedColor(
                "Panel.background",
                JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x3C, 0x3F, 0x41))
            )
        }

        /** 快速取 TextAttributesKey 在当前 scheme 下的前景（如果用户没自定义 scheme 就退化到 JBColor），主要给 fallback。*/
        @Suppress("unused")
        private fun fgOf(key: TextAttributesKey): Color =
            EditorColorsManager.getInstance().globalScheme.getAttributes(key).foregroundColor ?: COLOR_VALUE
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        return runCatching gen@{
            doGenerateDoc(element, originalElement)
        }.getOrNull()
    }

    private fun doGenerateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element == null) return null

        // Case 1: 直接悬浮在 CssRuleset 上（用户从导航跳进去后又悬浮）
        val targetRule = element as? com.intellij.psi.css.CssRuleset
            ?: originalElement?.let {
                it as? com.intellij.psi.css.CssRuleset
                    ?: PsiTreeUtil.getParentOfType(it, com.intellij.psi.css.CssRuleset::class.java)
            }
        if (targetRule != null) {
            return formatRulesetDoc(
                CssSelectorUtil.expandSelector(targetRule),
                CssSelectorUtil.collectEffectiveDeclarations(targetRule)
            )
        }

        // Case 2: 悬浮在 JSX/Vue 的引用处
        val site = originalElement ?: element
        val resolved = resolveFromSite(site)
        if (resolved != null) {
            // mixin 调用（.foo(); / @include foo;）也展开进预览，与 Case 1 语义一致
            val decls = CssSelectorUtil.collectEffectiveDeclarations(resolved.ruleset)
            val locationInfo = when (resolved.container) {
                is CssModuleResolver.CssContainer.ImportedFile ->
                    message("doc.location.imported.file", resolved.container.virtualFile.path)
                is CssModuleResolver.CssContainer.VueStyleTag ->
                    if (resolved.container.moduleAlias != "\$style")
                        message("doc.location.vue.style.alias", resolved.container.moduleAlias.drop(1))
                    else
                        message("doc.location.vue.style")
                is CssModuleResolver.CssContainer.LocalObjectLiteral ->
                    message("doc.location.local.object", resolved.container.variableName)
            }
            return formatRulesetDoc(resolved.expandedSelector, decls, locationInfo)
        }

        return null
    }

    override fun getDocumentationElementForLookupItem(
        psiManager: PsiManager?,
        `object`: Any?,
        element: PsiElement?
    ): PsiElement? {
        // 补全弹出里用户按 F1 / quick doc 时也能显示 CSS 内容
        val name = (`object` as? String) ?: (`object` as? com.intellij.codeInsight.lookup.LookupElement)?.lookupString
        if (element != null && name != null) {
            val resolved = resolveFromSite(element)
                ?: CssSelectorResolver.resolveClassName(element, name)
            return resolved?.ruleset
        }
        return null
    }

    // --------------------------------------------------
    // internal helpers
    // --------------------------------------------------
    private fun resolveFromSite(site: PsiElement): CssSelectorResolver.ResolvedClass? {
        val direct = site as? JSLiteralExpression
            ?: site as? JSReferenceExpression
            ?: run {
                val p1 = site.parent
                if (p1 is JSLiteralExpression || p1 is JSReferenceExpression) p1 else null
            }
        val target = direct ?: site.parent?.parent ?: return null
        val nameHint = when (target) {
            is JSLiteralExpression -> target.stringValue
            is JSReferenceExpression -> target.referenceName
            else -> null
        } ?: return null
        return CssSelectorResolver.resolveClassName(target, nameHint)
    }

    private fun formatRulesetDoc(selector: String, declarations: List<CssDeclaration>, footer: String? = null): String {
        val monoFont = "font-family:ui-monospace,Menlo,Consolas,monospace"
        val selCss = "color:${COLOR_SELECTOR.toCssHex()};font-weight:600;$monoFont;margin-bottom:4px"
        val closeCss = "color:${COLOR_SELECTOR.toCssHex()};font-weight:600;$monoFont;margin-top:4px"
        val bodyCss = "padding-left:16px;$monoFont;line-height:1.5"
        val propCss = "color:${COLOR_PROPERTY.toCssHex()}"
        val valueCss = "color:${COLOR_VALUE.toCssHex()}"
        val punctCss = "color:${COLOR_PUNCT.toCssHex()}"
        val emptyCss = "color:${COLOR_COMMENT.toCssHex()};font-style:italic;padding-left:16px;$monoFont"
        val footerLine = "border-top:1px solid ${COLOR_SEPARATOR.toCssHex()};color:${COLOR_FOOTER.toCssHex()};font-size:11px;padding-top:4px;margin-top:8px"
        // 外层背景：跟随 IDE Panel.background，避免 Darcula 下白底、Light 下紫底等突兀感
        val outerCss = "padding:2px 4px;background-color:${COLOR_PANEL_BG.toCssHex()}"

        val body = buildString {
            append("<div style=\"$outerCss\">")
            if (selector.isNotBlank()) {
                append("<div style=\"$selCss\">")
                append(htmlEscape(selector))
                append(" {</div>")
            }
            if (declarations.isEmpty()) {
                append("<div style=\"$emptyCss\">${htmlEscape(message("doc.empty.rule.comment"))}</div>")
            } else {
                for (d in declarations) {
                    val prop = d.propertyName ?: continue
                    val value = d.value?.text ?: continue
                    append("<div style=\"$bodyCss\">")
                    append("<span style=\"$propCss\">${htmlEscape(prop)}</span>")
                    append(": ")
                    append("<span style=\"$valueCss\">${htmlEscape(value)}</span>")
                    append("<span style=\"$punctCss\">;</span>")
                    append("</div>")
                }
            }
            if (selector.isNotBlank()) {
                append("<div style=\"$closeCss\">}</div>")
            }
            if (footer != null) {
                append("<div style=\"$footerLine\">")
                append(htmlEscape(footer))
                append("</div>")
            }
            append("</div>")
        }
        return body
    }
}
