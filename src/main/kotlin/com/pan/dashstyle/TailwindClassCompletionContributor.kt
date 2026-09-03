package com.pan.dashstyle

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
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
            val builder = LookupElementBuilder.create(t.name)
                .withIcon(ICON)
                .withTypeText(t.css, true)          // 右侧灰字 = CSS 预览
                .withTailText(" (${t.group})", true)

            // 关键区分：
            // - @apply 内：插入 Tailwind 类名（@apply 接受的就是类名）
            // - 普通 CSS 规则体内：插入展开后的 CSS 声明（flex: 1 1 auto;）
            if (!inApply) {
                builder.withInsertHandler(CssDeclarationInsertHandler(t.css))
            }
            builder
        }
        result.addAllElements(lookupElements)
    }

    /**
     * 在普通 CSS 规则体内，把类名替换为展开的 CSS 声明。
     * 例如：候选 flex-auto → 插入 flex: 1 1 auto;
     * 多声明类（如 px-4 → padding-left: 1rem; padding-right: 1rem;）会自动带分号。
     */
    private class CssDeclarationInsertHandler(private val cssDecl: String) : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val editor = context.editor
            val document = editor.document
            val startOffset = context.startOffset
            val endOffset = context.tailOffset

            // 构建要插入的完整声明文本（自动加 ; 保证多条声明也合法）
            val trimmed = cssDecl.trim()
            val insertText = if (trimmed.endsWith(";")) trimmed else "$trimmed;"

            // 替换掉用户输入的前缀（startOffset → endOffset）为完整声明
            document.replaceString(startOffset, endOffset, insertText)

            // 光标留在插入文本末尾（方便继续写分号或下一条声明）
            val caret = startOffset + insertText.length
            editor.caretModel.moveToOffset(caret)
        }
    }

    companion object {
        private val ICON = IconLoader.getIcon(
            "/icons/dash.svg",
            TailwindClassCompletionContributor::class.java
        )
    }
}