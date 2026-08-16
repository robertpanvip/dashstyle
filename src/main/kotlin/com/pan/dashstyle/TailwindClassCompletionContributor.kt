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
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/**
 * 在 CSS 的 `@apply` 指令内对 Tailwind 类做自动补全。
 *
 * 补全项右侧灰字显示该类展开后的 CSS 声明（预览框），按 Enter 直接补全。
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
            ?: return
        // 只处理 @apply 指令（部分发行版 CssAtRule 没有稳定的 getAtRuleName API，用文本判断）
        if (!atRule.text.trimStart().startsWith("@apply", ignoreCase = true)) return

        val prefix = result.prefixMatcher.prefix
        val candidates = TailwindClassResolver.search(prefix)
        if (candidates.isEmpty()) return

        val lookupElements = candidates.map { t ->
            LookupElementBuilder.create(t.name)
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