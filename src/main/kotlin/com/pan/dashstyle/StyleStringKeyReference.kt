package com.pan.dashstyle

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifierAlias
import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding
import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.css.StylesheetFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiFile

import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.index.JavaScriptIndex
import com.intellij.lang.javascript.psi.ecma6.impl.TypeScriptFunctionExpressionImpl
import com.intellij.lang.javascript.psi.impl.JSBlockStatementImpl
import com.intellij.openapi.diagnostic.thisLogger

/**
 * PsiReference，用于将 styles["foo-bar"] 映射为 styles.fooBar
 */
class StyleStringKeyReference(
    element: PsiElement,
    private val kebabName: String
) : PsiReferenceBase<PsiElement>(element, TextRange(1, element.textLength - 1)) {
    private val LOG = Logger.getInstance(StyleStringKeyReferenceProvider::class.java)
    override fun resolve(): PsiElement? {
        val stylesObj = this.getStyleElement();
        if (stylesObj === null || stylesObj.text === null) {
            return stylesObj
        }
        val originName = kebabName;

        val kebabName = if (kebabName.contains("-")) kebabName else camelToKebab(kebabName)
        // 统一转为 camelCase 用于匹配属性名
        val camelName = if (kebabName.contains("-")) kebabToCamel(kebabName) else kebabName

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


    // 查找文件中的指定标签（如 "template" 或 "style"）
    private fun findTagInFile(file: PsiFile, tagName: String): XmlTag? {
        return PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .firstOrNull { it.name.equals(tagName, ignoreCase = true) }
    }

    // 辅助函数：在指定容器中搜索同名变量声明
    private fun findVariableDeclarationByName(name: String, scriptTag: XmlTag?): JSVariable? {
        if (scriptTag === null) {
            return null;
        }
        if (name.isBlank()) return null

        val topLevelBlocks = PsiTreeUtil.collectElements(scriptTag, { ele ->
            return@collectElements ele.text.trim()
                .isNotEmpty() && ele.parent.javaClass.simpleName == "VueScriptSetupEmbeddedContentImpl"
        })

        // 从所有嵌入块中查找变量，取最后一个匹配的
        val allMatchingVars = topLevelBlocks.flatMap { block ->
            PsiTreeUtil.findChildrenOfType(block, JSVariable::class.java)
                .filter { it.name == name }
        }

        return allMatchingVars.maxByOrNull { it.textOffset }
    }


    // 查找 <script> 标签
    private fun findScriptTag(file: PsiFile): XmlTag? {
        return PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .firstOrNull { it.name.equals("script", ignoreCase = true) }
    }

    private fun findModuleStyleTag(file: PsiFile): XmlTag? {
        return PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .firstOrNull { tag ->
                tag.name.equals("style", ignoreCase = true) &&
                        tag.getAttribute("module") != null
            }
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
            val templateTag = findTagInFile(containingFile, "template") ?: return stylesObj
            if (!PsiTreeUtil.isAncestor(templateTag, element, false)) return stylesObj;
            val varName = qualifierExpr.text;

            // 在整个文件或 script 块中搜索同名变量声明
            val variable = findVariableDeclarationByName(varName, findScriptTag(containingFile))
            if (variable !== null) {
                val initializer = variable.initializer;
                if (initializer !== null) {
                    print("initializer${initializer.text}\n")
                    if (initializer is JSCallExpression) {
                        if (isUseCssModuleFromVue(initializer)) {
                            val moduleStyleTag = findModuleStyleTag(containingFile) ?: return stylesObj
                            return moduleStyleTag
                        }
                    }
                    return initializer;
                }
            }
            if (qualifierExpr.text == "$" + "style") {
                val moduleStyleTag = findModuleStyleTag(containingFile) ?: return stylesObj
                return moduleStyleTag
            }
        }

        return stylesObj;
    }

    fun isUseCssModuleFromVue(initializer: JSCallExpression): Boolean {
        // 获取被调用的表达式（如 useCssModule 或别名 css）
        val methodExpr = initializer.methodExpression
        // 检查这个引用是否来自 'vue' 导入
        var resolved = methodExpr?.reference?.resolve();
        if (resolved == null) return false;
        if (resolved is ES6ImportSpecifierAlias) {
            resolved = resolved.findAliasedElement()
        }
        if (resolved == null) return false
        val cf = resolved.containingFile
        val virtualFile = cf?.virtualFile ?: cf?.originalFile?.virtualFile

        val filePath = virtualFile?.path?.lowercase()
        if (filePath == null) {
            return false
        }
        return filePath.contains("node_modules/@vue")
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
            val camel = kebabToCamel(kebab)
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
