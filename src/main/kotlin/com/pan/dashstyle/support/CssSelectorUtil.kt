package com.pan.dashstyle.support

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssAtRule
import com.intellij.psi.css.CssDeclaration
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

    // ------------------------------------------------------------------
    // mixin 展开声明收集（悬停预览用）
    // ------------------------------------------------------------------

    /** LESS mixin 调用形态：.foo; / .foo(); / .foo(a, b); */
    private val RE_LESS_MIXIN_CALL =
        Regex("""^\s*\.[A-Za-z_][\w-]*\s*(\([^)]*\))?\s*;?\s*$""")

    /** SCSS mixin 调用形态：@include foo; / @include foo(a); */
    private val RE_SCSS_MIXIN_CALL =
        Regex("""^\s*@include\s+[A-Za-z_][\w-]*\s*(\([^)]*\))?\s*;?\s*$""", RegexOption.IGNORE_CASE)

    /** 从 @include 调用里取 mixin 名（跳过关键字本身） */
    private val RE_SCSS_CALL_NAME =
        Regex("""@include\s+([A-Za-z_][\w-]*)""", RegexOption.IGNORE_CASE)

    /** 从调用文本里取 mixin 名（.foo → ".foo"，@include foo → "foo"） */
    private val RE_MIXIN_NAME = Regex("""[.@]([A-Za-z_][\w-]*)""")

    /**
     * 悬停预览用的「生效声明」收集：直接手写声明 + LESS/SCSS mixin 调用展开。
     *
     * 背景：CSS Module 里常见 `.app-root { .shared-color(); }`（本插件自己的
     * ExtractDuplicateDeclarationsAsMixinIntention 也会生成这种形态）。mixin 调用
     * 在 PSI 里是叶子节点（LESSMixinInvocation），既不是 CssDeclaration 也不是
     * 嵌套 ruleset，纯 filterIsInstance<CssDeclaration> 会把整块样式漏掉。
     *
     * 语义（刻意保守，宁可少显示也不错显示）：
     * - 只看 block 直接子节点；嵌套 ruleset（后代样式）依旧不并入；
     * - LESS 调用（.foo(); / .foo;）在同文件查 selector 恰为 .foo 的定义 ruleset；
     *   SCSS 调用（@include foo;）查 @mixin foo 的 at-rule；取其直接声明并递归展开；
     * - 防环按调用路径（回溯式 visited），同一 mixin 被调用两次会展开两次；
     * - 找不到定义、或定义体本身解析不了（带参 mixin 的 @var 不做值替换）时，
     *   原样展示定义体声明；跨文件 mixin（@import 引入）不展开。
     */
    fun collectEffectiveDeclarations(ruleset: CssRuleset): List<CssDeclaration> {
        val visited: MutableSet<CssRuleset> =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        return collectFromBlock(ruleset, visited)
    }

    private fun collectFromBlock(
        ruleset: CssRuleset,
        visited: MutableSet<CssRuleset>
    ): List<CssDeclaration> {
        if (!visited.add(ruleset)) return emptyList()
        try {
            val block = ruleset.block ?: return emptyList()
            val out = mutableListOf<CssDeclaration>()
            for (child in block.children) {
                when (child) {
                    is CssDeclaration -> out += child
                    is CssRuleset -> Unit // 嵌套规则属于后代样式，不并入本类预览
                    else -> {
                        val name = mixinCallName(child.text) ?: continue
                        when (val target = findMixinDefinition(ruleset.containingFile, name)) {
                            is CssRuleset -> out += collectFromBlock(target, visited)
                            // SCSS @mixin 定义体（CssAtRule）：取其块内直接声明
                            is CssAtRule -> {
                                val body = target.children
                                    .filterIsInstance<com.intellij.psi.css.CssBlock>()
                                    .firstOrNull()
                                out += body?.children?.filterIsInstance<CssDeclaration>().orEmpty()
                            }
                        }
                    }
                }
            }
            return out
        } finally {
            // 回溯式防环：退出调用路径后移除，同一 mixin 在不同位置调用可各自展开
            visited.remove(ruleset)
        }
    }

    /** 识别 mixin 调用文本并返回归一化名字；非调用形态返回 null。 */
    private fun mixinCallName(text: String): String? {
        if (RE_LESS_MIXIN_CALL.matches(text)) {
            return RE_MIXIN_NAME.find(text)?.value // .foo → ".foo"
        }
        if (!RE_SCSS_MIXIN_CALL.matches(text)) return null
        // SCSS：跳过 @include 关键字取其后的 mixin 名（不带 @，与 @mixin 定义对齐）
        return RE_SCSS_CALL_NAME.find(text)?.groupValues?.get(1)
    }

    /** 同文件查 mixin 定义：LESS 用 selector 精确匹配；SCSS 用 @mixin 名前缀匹配。 */
    private fun findMixinDefinition(file: PsiFile, name: String): PsiElement? {
        if (name.startsWith(".")) {
            return PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)
                .firstOrNull { it.selectorList?.text?.trim() == name }
        }
        val re = Regex("""^@mixin\s+${Regex.escape(name)}\b""", RegexOption.IGNORE_CASE)
        return PsiTreeUtil.findChildrenOfType(file, CssAtRule::class.java)
            .firstOrNull { re.containsMatchIn(it.text) }
    }
}
