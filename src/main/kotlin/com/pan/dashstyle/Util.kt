package com.pan.dashstyle

import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifierAlias
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import kotlin.text.contains

class Util {
    companion object {
        fun findScriptTag(file: PsiFile): XmlTag? {
            return PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
                .firstOrNull { it.name.equals("script", ignoreCase = true) }
        }
        fun findModuleStyleTag(file: PsiFile): XmlTag? {
            return PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
                .firstOrNull { tag ->
                    tag.name.equals("style", ignoreCase = true) &&
                            tag.getAttribute("module") != null
                }
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

        // 查找文件中的指定标签（如 "template" 或 "style"）
        fun findTagInFile(file: PsiFile, tagName: String): XmlTag? {
            return PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
                .firstOrNull { it.name.equals(tagName, ignoreCase = true) }
        }

        // 辅助函数：在指定容器中搜索同名变量声明
        fun findVariableDeclarationByName(name: String, scriptTag: XmlTag?): JSVariable? {
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

         fun kebabToCamel(name: String): String {
            return name.split("-").mapIndexed { index, part ->
                if (index == 0) part else part.replaceFirstChar { it.uppercase() }
            }.joinToString("")
        }

         fun camelToKebab(name: String): String {
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

        fun expandSelector(ruleset: CssRuleset): String {
            val raw = ruleset.selectorList?.text ?: return ""

            val parent = PsiTreeUtil.getParentOfType(
                ruleset,
                CssRuleset::class.java,
                true
            ) ?: return raw

            val parentSelector = expandSelector(parent)

            return expandAmpersand(raw, parentSelector)
        }

        /**
         * 扩展选择器中的 & 符号，支持 Less/SCSS 的各种 & 用法：
         * 1. 基本替换: & → .parent
         * 2. 后缀拼接: &-bar → .parent-bar
         * 3. 下划线后缀: &_bar → .parent_bar
         * 4. 多 & 组合: & + &, & &, & > &
         * 5. 类拼接: &.active → .parent.active
         * 6. 伪类: &:hover → .parent:hover
         * 7. 多选择器逗号分隔: .a, .b { &-c {} } → .a-c, .b-c
         * 8. Less 变量插值占位符: @{var} 保留原文（无法在无上下文时解析）
         */
        fun expandAmpersand(rawSelector: String, parentSelector: String): String {
            // 先处理 Less/SCSS 变量插值 @{...}，暂时用占位符保护，最后还原
            val placeholders = mutableMapOf<String, String>()
            var processedSelector = rawSelector
            val varPattern = Regex("""@\{([^}]+)\}""")
            var matchResult = varPattern.find(processedSelector)
            var counter = 0
            while (matchResult != null) {
                val placeholder = "__VAR_PLACEHOLDER_${counter}__"
                placeholders[placeholder] = matchResult.value
                processedSelector = processedSelector.replaceRange(matchResult.range, placeholder)
                counter++
                matchResult = varPattern.find(processedSelector)
            }

            val expanded = if (!processedSelector.contains("&")) {
                // 处理多父选择器的情况：逗号分隔的每个父选择器都要拼接子选择器
                val parentParts = parentSelector.split(",").map { it.trim() }
                val childParts = processedSelector.split(",").map { it.trim() }
                val combinations = mutableListOf<String>()
                for (p in parentParts) {
                    for (c in childParts) {
                        combinations.add("$p $c")
                    }
                }
                combinations.joinToString(", ")
            } else {
                // 处理多父选择器 (逗号分隔)
                val parentParts = parentSelector.split(",").map { it.trim() }
                val results = mutableListOf<String>()

                for (parentPart in parentParts) {
                    val childParts = processedSelector.split(",").map { it.trim() }
                    for (childPart in childParts) {
                        results.add(replaceAmpersandInPart(childPart, parentPart))
                    }
                }
                results.joinToString(", ")
            }

            // 还原变量插值占位符
            var finalResult = expanded
            for ((placeholder, original) in placeholders) {
                finalResult = finalResult.replace(placeholder, original)
            }
            return finalResult
        }

        internal fun replaceAmpersandInPart(childPart: String, parentPart: String): String {
            val result = StringBuilder()
            var i = 0
            val n = childPart.length

            while (i < n) {
                if (childPart[i] == '&') {
                    // 无论 & 后面跟什么，都执行替换：&-suffix / &_suffix / &.class / &:pseudo / &
                    result.append(parentPart)
                    i++ // 跳过 &，后面的 -._: 等字符在下次循环中正常追加
                } else {
                    result.append(childPart[i])
                    i++
                }
            }

            return result.toString()
        }
    }
}