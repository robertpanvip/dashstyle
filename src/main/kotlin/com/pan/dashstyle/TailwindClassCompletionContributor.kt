package com.pan.dashstyle

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.IconLoader
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.css.CssAtRule
import com.intellij.psi.css.CssBlock
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/**
 * 在 CSS 的 `@apply` 指令内或 CSS 规则体（如 `.test { ju| }`）中对 Tailwind 类做自动补全。
 *
 * 补全项右侧灰字显示该类展开后的 CSS 声明（预览框）。按 Enter 的实际插入内容因场景而异：
 *   - @apply 内：插入 Tailwind 类名（@apply 本来就接收类名）
 *   - 普通 CSS 规则体内：插入展开后的完整 CSS 声明（如 flex-auto → `flex: 1 1 auto;`）
 *     这种设计让开发者写普通 CSS 规则时也能享受 Tailwind 的原子化声明预览 + 一键展开。
 *
 * 类清单来自 [TailwindClassResolver]（纯逻辑，内置常用类，开箱即用）。
 */
class TailwindClassCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    doComplete(parameters, result)
                }
            }
        )
    }

    private fun doComplete(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val atRule = PsiTreeUtil.getParentOfType(position, CssAtRule::class.java, false)
        val inApply = atRule != null && atRule.text.trimStart().startsWith("@apply", ignoreCase = true)

        if (!inApply) {
            // CSS 规则体（如 .test { ju| }），必须是 CssBlock 内
            val cssBlock = PsiTreeUtil.getParentOfType(position, CssBlock::class.java, false)
                ?: return
            // 排除 @apply 内部的 CssBlock（上一层已处理）
            if (PsiTreeUtil.getParentOfType(cssBlock, CssAtRule::class.java, true) != null) return

            // 检查是否在 CSS 属性值内部（如 display: fle|）——此时不显示 Tailwind 补全，
            // 交给标准 CSS 补全来处理（如 flex → display: flex）。
            val cssDecl = PsiTreeUtil.getParentOfType(position, CssDeclaration::class.java, false)
            if (cssDecl != null) {
                val colonIdx = cssDecl.text.indexOf(':')
                if (colonIdx >= 0) {
                    val declStart = cssDecl.textOffset
                    val cursorOffset = parameters.offset
                    if (cursorOffset > declStart + colonIdx) return
                }
            }
        }

        val prefix = result.prefixMatcher.prefix
        val candidates = TailwindClassResolver.search(prefix)
        if (candidates.isEmpty()) return

        val lookupElements = candidates.map { t ->
            val builder = if (inApply) {
                // @apply 内：create() 传类名（要插入的就是类名）
                LookupElementBuilder.create(t.name)
            } else {
                // 普通 CSS 规则体内：create() 传要实际插入的完整 CSS 声明（核心！），
                // withPresentableText() 把候选列表显示改回 Tailwind 类名，
                // withLookupString() 保证输入前缀仍按类名匹配。
                //
                // 为什么不用 InsertHandler？之前诊断确认：LESS/CSS 平台对 CssDeclarationImpl
                // 场景有特殊处理，会跳过自定义 InsertHandler。把插入内容直接塞到 LookupElementBuilder
                // 核心字符串里，平台一定用它来替换前缀，不会再被跳过。
                val insertText = if (t.css.trim().endsWith(";")) t.css.trim() else "${t.css.trim()};"
                LookupElementBuilder.create(insertText)
                    .withPresentableText(t.name)
                    .withLookupString(t.name)
            }
            builder
                .withIcon(ICON)
                .withTypeText(t.css, true)          // 右侧灰字 = CSS 预览
                .withTailText(" (${t.group})", true)
        }
        result.addAllElements(lookupElements)
    }

    companion object {
        private val ICON = IconLoader.getIcon(
            "/icons/dash.svg",
            TailwindClassCompletionContributor::class.java
        )
    }
}
