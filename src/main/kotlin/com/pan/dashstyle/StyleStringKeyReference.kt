package com.pan.dashstyle

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding
import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.css.StylesheetFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.lang.javascript.psi.JSVariable

/**
 * PsiReference，用于将 styles["foo-bar"] 映射为 styles.fooBar
 */
class StyleStringKeyReference(
    element: PsiElement,
    private val kebabName: String
) : PsiReferenceBase<PsiElement>(element, TextRange(1, element.textLength - 1)) {
    override fun resolve(): PsiElement? {
        val stylesObj = this.getStyleElement();
        if (stylesObj === null || stylesObj.text === null) {
            return stylesObj
        }
        val originName = kebabName;

        val kebabName = if (kebabName.contains("-")) kebabName else Util.camelToKebab(kebabName)
        // 统一转为 camelCase 用于匹配属性名
        val camelName = if (kebabName.contains("-")) Util.kebabToCamel(kebabName) else kebabName

        val pattern =
            Regex("""(^|[^\w-])\.${Regex.escape(kebabName)}($|[^\w-])""")

        val candidates: List<PsiElement> =
            collectStyleMembers(stylesObj) { item ->
                when (item) {
                    is CssRuleset -> {
                        val selectorText = item.selectorList?.text ?: ""
                        if (pattern.containsMatchIn(selectorText)) {
                            listOf(item)
                        } else {
                            emptyList()
                        }
                    }

                    is JSProperty -> {
                        if (item.name == originName) {
                            listOf(item)
                        } else {
                            emptyList()
                        }
                    }

                    else -> emptyList()
                }
            }


        return candidates.firstOrNull()
    }

    fun getStyleElement(): PsiElement? {
        val literal = element as? JSLiteralExpression ?: return null

        val indexAccess =
            PsiTreeUtil.getParentOfType(literal, JSIndexedPropertyAccessExpression::class.java) ?: return null

        val qualifierExpr = indexAccess.qualifier ?: return null

        val stylesObj = qualifierExpr.reference?.resolve();
        val containingFile = element.containingFile
        if ((stylesObj?.text == null) && containingFile?.name?.endsWith(".vue") == true && containingFile is XmlFile) {
            // 当作 Vue 文件处理
            // 条件: 当前位置必须在 <template> 标签内部（包括绑定表达式）
            val templateTag = Util.findTagInFile(containingFile, "template") ?: return stylesObj
            if (!PsiTreeUtil.isAncestor(templateTag, element, false)) return stylesObj;
            val varName = qualifierExpr.text;

            // 在整个文件或 script 块中搜索同名变量声明
            val variable = Util.findVariableDeclarationByName(varName, Util.findScriptTag(containingFile))
            if (variable !== null) {
                val initializer = variable.initializer;
                if (initializer !== null) {
                    if (initializer is JSCallExpression) {
                        if (Util.isUseCssModuleFromVue(initializer)) {
                            val moduleStyleTag = Util.findModuleStyleTag(containingFile) ?: return stylesObj
                            return moduleStyleTag
                        }
                    }
                    return initializer;
                }
            }
            if (qualifierExpr.text == "$" + "style") {
                val moduleStyleTag = Util.findModuleStyleTag(containingFile) ?: return stylesObj
                return moduleStyleTag
            }
        }

        return stylesObj;
    }

    fun <T> collectStyleMembers(
        stylesObj: PsiElement,
        collector: (PsiElement) -> Collection<T>
    ): List<T> {

        val result = mutableListOf<T>()

        fun collectFrom(element: PsiElement) {
            result.addAll(collector(element))
        }

        when (stylesObj) {
            is ES6ImportedBinding -> {
                stylesObj.findReferencedElements().forEach { declaration ->
                    when (declaration) {
                        is StylesheetFile -> {
                            declaration.stylesheet
                                ?.rulesetList
                                ?.rulesets
                                ?.forEach(::collectFrom)
                        }

                        else -> {
                            PsiTreeUtil.findChildrenOfType(
                                declaration,
                                JSProperty::class.java
                            ).forEach(::collectFrom)
                        }
                    }
                }
            }

            is JSVariable,
            is JSObjectLiteralExpression -> {
                PsiTreeUtil.findChildrenOfType(
                    stylesObj,
                    JSProperty::class.java
                ).forEach(::collectFrom)
            }
            // <style module> 标签本身
            is XmlTag -> {
                if (stylesObj.name.equals("style", ignoreCase = true)) {
                    // 直接遍历 <style> 标签的子树，找到所有 CssRuleset
                    PsiTreeUtil.findChildrenOfType(stylesObj, CssRuleset::class.java)
                        .forEach(::collectFrom)
                }
            }
        }

        return result
    }

    private val ICON = IconLoader.getIcon(
        "/icons/dash.svg",
        StyleStringKeyReference::class.java
    )

    override fun getVariants(): Array<Any> {
        val stylesObj = this.getStyleElement();
        if (stylesObj === null) {
            return emptyArray()
        }
        val kebabOptions = this.collectStyleMembers<String>(stylesObj, { item ->
            when (item) {
                is CssRuleset -> {
                    val text = item.selectorList?.text ?: ""
                    Regex("""\.([a-zA-Z0-9_-]+)""")
                        .findAll(text)
                        .map { it.groupValues[1] }
                        .toList()
                }

                is JSProperty ->
                    item.name?.let { listOf(it) } ?: emptyList()

                else -> emptyList()
            }
        })

        // 排序后生成补全项
        return kebabOptions.sorted().map { kebab ->
            val camel = Util.kebabToCamel(kebab)
            LookupElementBuilder.create(kebab)
                .withTypeText(camel, true)                    // 右侧灰字显示 camelCase
                .withIcon(ICON)             // CSS 图标
                .withTailText(" (DashStyle)", true)              // 尾部提示
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

    // 关键：返回 true 表示这是软引用
    override fun isSoft(): Boolean = true
}
