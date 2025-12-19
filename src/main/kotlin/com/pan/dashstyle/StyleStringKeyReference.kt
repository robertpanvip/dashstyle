package com.pan.dashstyle

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.icons.AllIcons
import com.intellij.psi.css.StylesheetFile

/**
 * PsiReference，用于将 styles["foo-bar"] 映射为 styles.fooBar
 */
class StyleStringKeyReference(
    element: PsiElement,
    private val kebabName: String
) : PsiReferenceBase<PsiElement>(element, TextRange(1, element.textLength - 1)) {
    private val LOG = Logger.getInstance(StyleStringKeyReferenceProvider::class.java)
    override fun resolve(): PsiElement? {
        val literal = element as? JSLiteralExpression ?: return null
        val kebabName = if (kebabName.contains("-")) kebabName else camelToKebab(kebabName)
        // 统一转为 camelCase 用于匹配属性名
        val camelName = if (kebabName.contains("-")) kebabToCamel(kebabName) else kebabName
        val indexAccess = PsiTreeUtil.getParentOfType(literal, JSIndexedPropertyAccessExpression::class.java) ?: run {
            //LOG.warn("DashStyle resolve: 未找到索引访问表达式 (JSIndexedPropertyAccessExpression)")
            return null
        }

        //LOG.info("DashStyle resolve: 找到索引表达式: ${indexAccess.text}")

        // 获取 qualifier（styles 部分）
        val qualifierExpr = indexAccess.qualifier ?: run {
            //LOG.warn("DashStyle resolve: 索引表达式没有 qualifier")
            return null
        }

        //LOG.info("DashStyle resolve: qualifier 文本: ${qualifierExpr.text}")

        val stylesObj = qualifierExpr.reference?.resolve() ?: return null

        //LOG.info("DashStyle resolve: stylesObj 类型: ${stylesObj.javaClass.simpleName}")

        // 收集目标元素列表（优先 JSProperty，其次 CssClass）
        val candidates = mutableListOf<PsiElement>()

        if (stylesObj is ES6ImportedBinding) {
            val resolved = stylesObj.findReferencedElements()
            for (declaration in resolved) {
                //LOG.info("DashStyle resolve: declaration 类型: ${declaration.javaClass.simpleName}")

                if(declaration is StylesheetFile){

                    val stylesheet = declaration.stylesheet ?: continue
                    for (ruleset in stylesheet.rulesets) {
                        val selectorText = ruleset.selectorList?.text ?: continue
                        //LOG.info("DashStyle resolve: selectorText 类型: $selectorText $kebabName")
                        val pattern = Regex("""(^|[^\w-])\.${Regex.escape(kebabName)}($|[^\w-])""")
                        if (pattern.containsMatchIn(selectorText)) {
                            candidates.add(ruleset)
                            //LOG.info("DashStyle resolve: 找到匹配 ruleset: $selectorText")
                        }
                    }
                }

            }
        }

        // 本地定义兜底
        if (stylesObj is JSVariable || stylesObj is JSObjectLiteralExpression) {
            PsiTreeUtil.findChildrenOfType(stylesObj, JSProperty::class.java)
                .firstOrNull { it.name == camelName }?.let {
                    candidates.add(it)
                }
        }

        return candidates.firstOrNull()?.also {
            //LOG.info("DashStyle resolve: 成功跳转到: ${it.javaClass.simpleName} (${it.text})")
        }
    }

    private fun kebabToCamel(name: String): String {
        return name.split("-").mapIndexed { index, part ->
            if (index == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString("")
    }

    private fun camelToKebab(name: String): String {
        return buildString {
            name.forEach { ch ->
                if (ch.isUpperCase()) {
                    append("-")
                    append(ch.lowercaseChar())
                } else {
                    append(ch)
                }
            }
        }.removePrefix("-")  // 防止首字母大写时多出一个前导 -
    }

    override fun getVariants(): Array<Any> {
        val literal = element as? JSLiteralExpression ?: return emptyArray()

        val indexAccess = PsiTreeUtil.getParentOfType(literal, JSIndexedPropertyAccessExpression::class.java) ?: return emptyArray()

        val qualifierExpr = indexAccess.qualifier ?: return emptyArray()

        val stylesObj = qualifierExpr.reference?.resolve() ?: return emptyArray()

        val kebabOptions = mutableSetOf<String>()

        // 1. 处理 imported binding（CSS Modules 或 JS export）
        if (stylesObj is ES6ImportedBinding) {
            val resolved = stylesObj.findReferencedElements()
            for (declaration in resolved) {
                // CSS/SCSS/LESS 文件
                if (declaration is StylesheetFile) {
                    val stylesheet = declaration.stylesheet ?: continue
                    for (ruleset in stylesheet.rulesets) {
                        val selectorText = ruleset.selectorList?.text ?: continue
                        // 提取所有 .class 名
                        val regex = Regex("""\.([a-zA-Z0-9_-]+)""")
                        regex.findAll(selectorText).forEach { match ->
                            kebabOptions.add(match.groupValues[1])
                        }
                    }
                }
                // JS/TS export const styles = { ... }
                else {
                    PsiTreeUtil.findChildrenOfType(declaration, JSProperty::class.java)
                        .mapNotNull { it.name }
                        .forEach { kebabOptions.add(camelToKebab(it)) }
                }
            }
        }

        // 2. 本地定义（const styles = { ... }）
        if (stylesObj is JSVariable || stylesObj is JSObjectLiteralExpression) {
            PsiTreeUtil.findChildrenOfType(stylesObj, JSProperty::class.java)
                .mapNotNull { it.name }
                .forEach { kebabOptions.add(it) }
        }

        //LOG.info("DashStyle variants: 找到 ${kebabOptions.size} 个补全选项: $kebabOptions")

        // 排序后生成补全项
        return kebabOptions.sorted().map { kebab ->
            val camel = kebabToCamel(kebab)
            LookupElementBuilder.create(kebab)
                .withTypeText(camel, true)                    // 右侧灰字显示 camelCase
                .withIcon(AllIcons.FileTypes.Css)             // CSS 图标
                .withTailText(" (styles)", true)              // 尾部提示
                .withInsertHandler { context, _ ->
                    val editor = context.editor
                    val document = context.document
                    val offset = editor.caretModel.offset

                    val currentChar = document.charsSequence.getOrNull(offset)
                    if (currentChar == '"' || currentChar == '\'') {
                        // 已经有了结束引号，直接把光标移进去
                        editor.caretModel.moveToOffset(offset)
                    } else {
                        // 没有结束引号，补上和开始引号相同的
                        val startQuote = element.text.firstOrNull { it == '"' || it == '\'' }
                        if (startQuote != null) {
                            document.insertString(offset, startQuote.toString())
                            editor.caretModel.moveToOffset(offset + 1)
                        }
                    }
                }
        }.toTypedArray()
    }
}
