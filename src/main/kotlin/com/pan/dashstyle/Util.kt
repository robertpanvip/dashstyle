package com.pan.dashstyle

import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifierAlias
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

class Util {
    companion object {
        // ================================================================
        // 性能优化：Regex 预编译（避免每次调用时重新构造 Pattern）
        // ================================================================
        private val RE_LESS_INTERP = Regex("""@\{([^}]+)\}""")
        private val RE_SASS_INTERP = Regex("""#\{\s*(\$[^}]+?)\s*\}""")
        private val RE_SASS_ATROOT = Regex("""@at-root\s+(?:\([^)]*\)\s+)?""")
        private val RE_CLASS_SELECTOR = Regex("""\.([a-zA-Z_][a-zA-Z0-9_-]*)""")
        private val RE_SASS_PLACEHOLDER = Regex("""%[a-zA-Z_][\w-]*""")
        private val RE_COMMA_SPLIT = Regex(""",\s*""")

        private val EXPAND_SELECTOR_KEY: Key<CachedValue<String>> =
            Key.create("dashstyle.expanded.selector.v2")

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
            val methodExpr = initializer.methodExpression
            val resolved0: PsiElement? = methodExpr?.reference?.resolve() ?: return false
            var resolved: PsiElement? = resolved0
            if (resolved is ES6ImportSpecifierAlias) resolved = resolved.findAliasedElement()
            val cf = resolved?.containingFile ?: return false
            val virtualFile = cf.virtualFile ?: cf.originalFile?.virtualFile
            val filePath = virtualFile?.path?.lowercase() ?: return false
            return filePath.contains("node_modules/@vue")
        }

        fun findTagInFile(file: PsiFile, tagName: String): XmlTag? {
            return PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
                .firstOrNull { it.name.equals(tagName, ignoreCase = true) }
        }

        fun findVariableDeclarationByName(name: String, scriptTag: XmlTag?): JSVariable? {
            if (scriptTag === null || name.isBlank()) return null

            val topLevelBlocks = PsiTreeUtil.collectElements(scriptTag) { ele ->
                ele.text.trim().isNotEmpty() &&
                        ele.parent.javaClass.simpleName == "VueScriptSetupEmbeddedContentImpl"
            }

            return topLevelBlocks
                .flatMap { block ->
                    PsiTreeUtil.findChildrenOfType(block, JSVariable::class.java)
                        .filter { it.name == name }
                }
                .maxByOrNull { it.textOffset }
        }

        // ================================================================
        // 命名转换（性能优化版：无额外对象分配的 buildString）
        // ================================================================
        fun kebabToCamel(name: String): String {
            if ('-' !in name) return name
            return buildString(name.length) {
                var up = false
                for (ch in name) {
                    if (ch == '-') { up = true; continue }
                    append(if (up) ch.uppercaseChar() else ch)
                    up = false
                }
            }
        }

        fun camelToKebab(name: String): String {
            var hasUpper = false
            for (ch in name) if (ch.isUpperCase()) { hasUpper = true; break }
            if (!hasUpper) return name
            return buildString(name.length + 4) {
                name.forEach { ch ->
                    if (ch.isUpperCase()) { append('-'); append(ch.lowercaseChar()) }
                    else append(ch)
                }
            }.removePrefix("-")
        }

        // ================================================================
        // expandSelector + PSI 缓存（性能优化）
        // 同一 ruleset 在 PSI 修改前只计算一次；父子关系递归天然命中
        // ================================================================
        fun expandSelector(ruleset: CssRuleset): String {
            val project = ruleset.project
            return CachedValuesManager.getManager(project).getCachedValue(
                ruleset, EXPAND_SELECTOR_KEY,
                {
                    val raw = ruleset.selectorList?.text.orEmpty()
                    val result = if (raw.isEmpty()) raw else {
                        val parent = PsiTreeUtil.getParentOfType(
                            ruleset, CssRuleset::class.java, true
                        )
                        val normalized = normalizeSelector(raw)
                        if (parent == null) normalized
                        else expandAmpersand(normalized, expandSelector(parent))
                    }
                    CachedValueProvider.Result.create(result, ruleset)
                }, false
            )
        }

        /**
         * Sass / SCSS / 原生 CSS 嵌套 归一化：
         *  - @at-root (with/without ...) selector → selector
         *  - %placeholder → 占位标记（不参与类名补全）
         *  - 只做结构变换，不改变 & 关系
         */
        private fun normalizeSelector(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return s
            if (s.contains("@at-root")) s = RE_SASS_ATROOT.replace(s, "")
            if (s.contains('%')) s = RE_SASS_PLACEHOLDER.replace(s) { m -> "__P_${m.value.drop(1)}__" }
            return s
        }

        /**
         * 从展开后的选择器中一次性抽取所有 class 名（getVariants/resolve 都用）。
         */
        fun extractClassNames(expandedSelector: String): List<String> {
            if (expandedSelector.isEmpty()) return emptyList()
            var s = expandedSelector
            if ("__P_" in s) s = s.replace(Regex("""__P_([A-Za-z0-9_-]+?)__"""), "%$1")
            return RE_CLASS_SELECTOR.findAll(s).map { it.groupValues[1] }.toList()
        }

        /**
         * Less / SCSS / 原生 CSS 嵌套 & 扩展：
         * 1. 基本 &
         * 2. &-suffix / &_suffix
         * 3. 多 & 组合
         * 4. 多父 × 多子 笛卡尔积
         * 5. Less @{var} / Sass #{$var} 占位保护
         * 6. 无 & 的标准嵌套（原生 CSS Nesting）
         */
        fun expandAmpersand(rawSelector: String, parentSelector: String): String {
            val placeholders = LinkedHashMap<String, String>()
            var processed = rawSelector
            fun protect(pattern: Regex) {
                var mr = pattern.find(processed)
                while (mr != null) {
                    val ph = "__VPH_${placeholders.size}__"
                    placeholders[ph] = mr.value
                    processed = processed.replaceRange(mr.range, ph)
                    mr = pattern.find(processed)
                }
            }
            protect(RE_LESS_INTERP)
            protect(RE_SASS_INTERP)

            val expanded = if ('&' !in processed) {
                val parents = splitByComma(parentSelector)
                val children = splitByComma(processed)
                val out = ArrayList<String>(parents.size * children.size)
                for (p in parents) for (c in children) out += "$p $c"
                out.joinToString(", ")
            } else {
                val parents = splitByComma(parentSelector)
                val children = splitByComma(processed)
                val out = ArrayList<String>(parents.size * children.size)
                for (p in parents) for (c in children) out += replaceAmpersandInPart(c, p)
                out.joinToString(", ")
            }

            var final = expanded
            for ((ph, orig) in placeholders) final = final.replace(ph, orig)
            return final
        }

        private fun splitByComma(s: String): List<String> {
            return RE_COMMA_SPLIT.split(s).filter { it.isNotEmpty() }
        }

        internal fun replaceAmpersandInPart(childPart: String, parentPart: String): String {
            if ('&' !in childPart) return childPart
            val result = StringBuilder(childPart.length + parentPart.length)
            val n = childPart.length
            var i = 0
            while (i < n) {
                if (childPart[i] == '&') {
                    result.append(parentPart); i++
                } else {
                    result.append(childPart[i]); i++
                }
            }
            return result.toString()
        }
    }
}
