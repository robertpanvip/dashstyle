package com.pan.dashstyle.support

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.annotator.*

import com.intellij.openapi.util.Key
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * CSS 选择器展开与类名提取。
 * 从 Util.kt 拆出，职责聚焦于 selector 文本处理。
 */
object CssSelectorUtil {

    private val RE_LESS_INTERP = Regex("""@\{([^}]+)\}""")
    private val RE_SASS_INTERP = Regex("""#\{\s*(\$[^}]+?)\s*\}""")
    private val RE_SASS_ATROOT = Regex("""@at-root\s+(?:\([^)]*\)\s+)?""")
    private val RE_CLASS_SELECTOR = Regex("""\.([a-zA-Z_][a-zA-Z0-9_-]*)""")
    private val RE_SASS_PLACEHOLDER = Regex("""%[a-zA-Z_][\w-]*""")
    private val RE_COMMA_SPLIT = Regex(""",\s*""")
    private val RE_GLOBAL_BLOCK = Regex(""":[a-zA-Z_-]*global\s*\([^)]*\)""", RegexOption.IGNORE_CASE)
    private val RE_GLOBAL_SCOPE_SEG = Regex("""\s*:global(?![a-zA-Z0-9_(-])[^,]*""", RegexOption.IGNORE_CASE)

    private val EXPAND_SELECTOR_KEY: Key<CachedValue<String>> =
        Key.create("dashstyle.expanded.selector.v2")

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

    private fun normalizeSelector(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (s.contains("@at-root")) s = RE_SASS_ATROOT.replace(s, "")
        if (s.contains('%')) s = RE_SASS_PLACEHOLDER.replace(s) { m -> "__P_${m.value.drop(1)}__" }
        return s
    }

    fun extractClassNames(expandedSelector: String): List<String> {
        if (expandedSelector.isEmpty()) return emptyList()
        var s = stripGlobalBlocks(expandedSelector)
        if ("__P_" in s) s = s.replace(Regex("""__P_([A-Za-z0-9_-]+?)__"""), "%$1")
        return RE_CLASS_SELECTOR.findAll(s).map { it.groupValues[1] }.toList()
    }

    fun stripGlobalBlocks(selectorText: String): String {
        if (!selectorText.contains(":global", ignoreCase = true) &&
            !selectorText.contains(":local", ignoreCase = true)) return selectorText
        var s = RE_GLOBAL_BLOCK.replace(selectorText, " ")
        s = RE_GLOBAL_SCOPE_SEG.replace(s, "")
        return s
    }

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
