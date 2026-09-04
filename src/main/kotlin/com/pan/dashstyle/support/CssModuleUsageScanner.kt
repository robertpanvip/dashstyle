package com.pan.dashstyle.support

import com.intellij.lang.javascript.psi.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlFile

/**
 * CSS Module 使用端扫描：遍历 JSX/Vue 源码，收集 CSS Module class 的所有引用。
 *
 * 从 CssModuleResolver 拆出，职责聚焦于 usage 扫描，
 * 依赖 [CssModuleResolver] 做容器解析（qualifier → CssContainer）。
 */
object CssModuleUsageScanner {

    data class ClassUsage(val kebabName: String, val site: PsiElement)

    /**
     * 遍历 sourceFile 内所有针对指定 container 绑定名的引用，产出 class 使用集合。
     * 仅处理：styles.fooBar / styles["foo-bar"] / :class="$style.fooBar"。
     * 动态访问 styles[expr] 会被标为 "any usage"，caller 据此决定是否跳过整个置灰。
     *
     * 注意：每个 qualifier 都通过 PSI resolve 确认其指向的 CssContainer 与传入的 container
     * 相同，**不依赖**名称匹配。这避免了本地变量（如 `const styles = { card: 'card' }`）
     * 与 CSS Module import 同名时产生的误判。
     */
    fun scanUsages(
        sourceFile: PsiFile,
        container: CssModuleResolver.CssContainer
    ): Pair<MutableSet<String>, Boolean /* hasDynamic */> {
        val used = mutableSetOf<String>()
        var dynamic = false

        // 1. JSIndexedPropertyAccessExpression: styles["foo"]
        PsiTreeUtil.findChildrenOfType(sourceFile, JSIndexedPropertyAccessExpression::class.java).forEach { idx ->
            val q = idx.qualifier ?: return@forEach
            val (c, _) = CssModuleResolver.resolveQualifier(q, sourceFile) ?: return@forEach
            if (c != container) return@forEach
            val inner = idx.indexExpression
            when {
                inner is JSLiteralExpression -> {
                    val s = inner.stringValue ?: return@forEach
                    val kebab = if (s.contains("-")) s else NamingUtil.camelToKebab(s)
                    used += kebab
                }
                else -> dynamic = true
            }
        }

        // 2. JSReferenceExpression with qualifier: styles.foo
        PsiTreeUtil.findChildrenOfType(sourceFile, JSReferenceExpression::class.java).forEach { ref ->
            val q = ref.qualifier ?: return@forEach
            val (c, _) = CssModuleResolver.resolveQualifier(q, sourceFile) ?: return@forEach
            if (c != container) return@forEach
            val name = ref.referenceName ?: return@forEach
            if (name == "let" || name == "const" || name == "var") return@forEach
            val kebab = if (name.contains("-")) name else NamingUtil.camelToKebab(name)
            used += kebab
        }

        // 3. Vue template 属性值 fallback（当 Vue 插件未将 $style.xxx 解析为 JS PSI 时）
        //    扫描 <template> 内所有属性值，提取 $alias.xxx 和 $alias["xxx"] 模式
        if (container is CssModuleResolver.CssContainer.VueStyleTag && sourceFile is XmlFile) {
            val alias = container.moduleAlias  // "$style" 或 "$xxx"
            scanVueTemplateAttributes(sourceFile, alias, used, dynamicRef = { dynamic = true })
        }

        return used to dynamic
    }

    /**
     * 扫描 Vue XML 文件 template 标签内的属性值，提取 module alias 引用。
     * 用于 Vue 插件未将 template 表达式解析为 JS PSI 的 fallback。
     */
    private fun scanVueTemplateAttributes(
        vueFile: XmlFile,
        alias: String,
        used: MutableSet<String>,
        dynamicRef: () -> Unit
    ) {
        val templateTag = Util.findTagInFile(vueFile, "template") ?: return
        val aliasDollar = if (alias.startsWith("\$")) alias else "\$$alias"
        // 匹配 $alias.xxx 或 $alias["xxx"] 或 $alias['xxx']
        val memberPattern = Regex("""\Q$aliasDollar\E\s*\.\s*([a-zA-Z_]\w*)""")
        val bracketPattern = Regex("""\Q$aliasDollar\E\s*\[\s*"([^"]*)"\s*\]""")
        val bracketSinglePattern = Regex("""\Q$aliasDollar\E\s*\[\s*'([^']*)'\s*\]""")
        // 匹配任何 $alias[ 开头但没有引号的情况，即动态引用
        val openBracketPattern = Regex("""\Q$aliasDollar\E\s*\[\s*(?!["'])""")

        for (attr in PsiTreeUtil.findChildrenOfType(templateTag, XmlAttribute::class.java)) {
            val value = attr.value ?: continue
            // 静态字符串成员
            for (m in memberPattern.findAll(value)) {
                val name = m.groupValues[1]
                val kebab = if (name.contains("-")) name else NamingUtil.camelToKebab(name)
                used += kebab
            }
            for (m in bracketPattern.findAll(value)) {
                used += m.groupValues[1]
            }
            for (m in bracketSinglePattern.findAll(value)) {
                used += m.groupValues[1]
            }
            // 动态引用 $alias[expr]（expr 不是字符串字面量）：
            // 匹配 $alias[ 之后第一个字符不是引号，说明是变量表达式
            if (openBracketPattern.containsMatchIn(value)) {
                dynamicRef()
            }
        }
    }
}
