package com.pan.dashstyle

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.util.PsiTreeUtil

/**
 * 鼠标悬浮在 styles.fooBar / styles["foo-bar"] 上时，展示对应的完整 CSS ruleset（声明块）。
 * 对 CssRuleset 本身悬浮，也返回展开后选择器 + 声明代码块。
 */
class DashStyleDocumentationProvider : AbstractDocumentationProvider() {

    companion object {
        private fun htmlEscape(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element == null) return null

        // Case 1: 直接悬浮在 CssRuleset 上（用户从导航跳进去后又悬浮）
        val targetRule = element as? com.intellij.psi.css.CssRuleset
            ?: originalElement?.let {
                it as? com.intellij.psi.css.CssRuleset
                    ?: PsiTreeUtil.getParentOfType(it, com.intellij.psi.css.CssRuleset::class.java)
            }
        if (targetRule != null) {
            return formatRulesetDoc(
                Util.expandSelector(targetRule),
                PsiTreeUtil.findChildrenOfType(targetRule.block, CssDeclaration::class.java).toList()
            )
        }

        // Case 2: 悬浮在 JSX/Vue 的引用处
        val site = originalElement ?: element
        val resolved = resolveFromSite(site)
        if (resolved != null) {
            val decls = PsiTreeUtil.findChildrenOfType(resolved.ruleset.block, CssDeclaration::class.java).toList()
            val locationInfo = when (resolved.container) {
                is CssModuleResolver.CssContainer.ImportedFile ->
                    "in ${resolved.container.virtualFile.path}"
                is CssModuleResolver.CssContainer.VueStyleTag ->
                    "in Vue <style${if (resolved.container.moduleAlias != "$" + "style") " module=\"${resolved.container.moduleAlias.drop(1)}\"" else " module"}>"
                is CssModuleResolver.CssContainer.LocalObjectLiteral ->
                    "in local object `${resolved.container.variableName}`"
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
                ?: CssModuleResolver.resolveClassName(element, name)
            return resolved?.ruleset
        }
        return null
    }

    // --------------------------------------------------
    // internal helpers
    // --------------------------------------------------
    private fun resolveFromSite(site: PsiElement): CssModuleResolver.ResolvedClass? {
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
        return CssModuleResolver.resolveClassName(target, nameHint)
    }

    private fun formatRulesetDoc(selector: String, declarations: List<CssDeclaration>, footer: String? = null): String {
        val body = buildString {
            append("<div style=\"padding:2px 4px\">")
            if (selector.isNotBlank()) {
                append("<div style=\"color:#6c5ce7;font-weight:600;font-family:ui-monospace,Menlo,Consolas,monospace;margin-bottom:4px\">")
                append(htmlEscape(selector))
                append(" {</div>")
            }
            if (declarations.isEmpty()) {
                append("<div style=\"color:#888;font-style:italic;padding-left:16px;font-family:ui-monospace,Menlo,Consolas,monospace\">/* empty */</div>")
            } else {
                for (d in declarations) {
                    val prop = d.propertyName ?: continue
                    val value = d.value?.text ?: continue
                    append("<div style=\"padding-left:16px;font-family:ui-monospace,Menlo,Consolas,monospace;line-height:1.5\">")
                    append("<span style=\"color:#0984e3\">${htmlEscape(prop)}</span>")
                    append(": ")
                    append("<span style=\"color:#2d3436\">${htmlEscape(value)}</span>")
                    append("<span style=\"color:#636e72\">;</span>")
                    append("</div>")
                }
            }
            if (selector.isNotBlank()) {
                append("<div style=\"color:#6c5ce7;font-weight:600;font-family:ui-monospace,Menlo,Consolas,monospace;margin-top:4px\">}</div>")
            }
            if (footer != null) {
                append("<div style=\"margin-top:8px;color:#95a5a6;font-size:11px;padding-top:4px;border-top:1px solid #ecf0f1\">")
                append(htmlEscape(footer))
                append("</div>")
            }
            append("</div>")
        }
        return body
    }
}
