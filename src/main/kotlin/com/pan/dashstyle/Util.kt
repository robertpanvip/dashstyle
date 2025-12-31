package com.pan.dashstyle

import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifierAlias
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.psi.PsiFile
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

    }
}