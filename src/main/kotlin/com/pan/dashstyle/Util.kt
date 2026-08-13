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

        // ================================================================
        // 颜色提取与归一化（支持 HEX3 / HEX6 / HEX8 / rgb(a) / hsl(a)）
        // ================================================================
        private val RE_HEX8 = Regex("""#([0-9a-fA-F]{8})\b""")
        private val RE_HEX6 = Regex("""#([0-9a-fA-F]{6})\b""")
        private val RE_HEX3 = Regex("""#([0-9a-fA-F]{3})\b""")
        private val RE_RGBA = Regex("""rgba?\(\s*([^)]*)\)""", RegexOption.IGNORE_CASE)
        private val RE_HSLA = Regex("""hsla?\(\s*([^)]*)\)""", RegexOption.IGNORE_CASE)
        /** 常见的 148 CSS 命名颜色（小写），避免把 transparent / currentcolor 当作命名颜色处理 */
        private val NAMED_COLORS: Set<String> = setOf(
            "aliceblue","antiquewhite","aqua","aquamarine","azure","beige","bisque","black","blanchedalmond","blue",
            "blueviolet","brown","burlywood","cadetblue","chartreuse","chocolate","coral","cornflowerblue","cornsilk",
            "crimson","cyan","darkblue","darkcyan","darkgoldenrod","darkgray","darkgreen","darkgrey","darkkhaki",
            "darkmagenta","darkolivegreen","darkorange","darkorchid","darkred","darksalmon","darkseagreen","darkslateblue",
            "darkslategray","darkslategrey","darkturquoise","darkviolet","deeppink","deepskyblue","dimgray","dimgrey",
            "dodgerblue","firebrick","floralwhite","forestgreen","fuchsia","gainsboro","ghostwhite","gold","goldenrod",
            "gray","green","greenyellow","grey","honeydew","hotpink","indianred","indigo","ivory","khaki","lavender",
            "lavenderblush","lawngreen","lemonchiffon","lightblue","lightcoral","lightcyan","lightgoldenrodyellow",
            "lightgray","lightgreen","lightgrey","lightpink","lightsalmon","lightseagreen","lightskyblue","lightslategray",
            "lightslategrey","lightsteelblue","lightyellow","lime","limegreen","linen","magenta","maroon",
            "mediumaquamarine","mediumblue","mediumorchid","mediumpurple","mediumseagreen","mediumslateblue",
            "mediumspringgreen","mediumturquoise","mediumvioletred","midnightblue","mintcream","mistyrose","moccasin",
            "navajowhite","navy","oldlace","olive","olivedrab","orange","orangered","orchid","palegoldenrod",
            "palegreen","paleturquoise","palevioletred","papayawhip","peachpuff","peru","pink","plum","powderblue",
            "purple","rebeccapurple","red","rosybrown","royalblue","saddlebrown","salmon","sandybrown","seagreen",
            "seashell","sienna","silver","skyblue","slateblue","slategray","slategrey","snow","springgreen","steelblue",
            "tan","teal","thistle","tomato","turquoise","violet","wheat","white","whitesmoke","yellow","yellowgreen"
        )

        /** 归一化颜色到"规范形态"（hex6 #rrggbb / hex8 #rrggbbaa / rgba() / hsla()），用于等值分组比较。 */
        fun normalizeColor(raw: String): String? {
            val t = raw.trim().lowercase()
            if (t.isEmpty()) return null
            // HEX8 #rrggbbaa (含 alpha)
            RE_HEX8.find(t)?.let { m ->
                val v = m.groupValues[1]
                val (r, g, b, a) = listOf(v.substring(0,2), v.substring(2,4), v.substring(4,6), v.substring(6,8))
                // alpha=ff 就降到 hex6
                return if (a == "ff") "#$r$g$b" else "#$r$g$b$a"
            }
            // HEX6 #rrggbb
            RE_HEX6.find(t)?.let { m -> return "#${m.groupValues[1]}" }
            // HEX3 #rgb → expand
            RE_HEX3.find(t)?.let { m ->
                val v = m.groupValues[1]
                return "#${v[0]}${v[0]}${v[1]}${v[1]}${v[2]}${v[2]}"
            }
            // rgb(a) → 归一化空格/逗号/alpha
            RE_RGBA.find(t)?.let { m ->
                val args = m.groupValues[1].split(Regex("""[,/\s]+""")).filter { it.isNotBlank() }
                return when (args.size) {
                    3 -> "rgb(${args[0]},${args[1]},${args[2]})"
                    4 -> {
                        val a = normalizeAlpha(args[3])
                        if (a == "1") "rgb(${args[0]},${args[1]},${args[2]})"
                        else "rgba(${args[0]},${args[1]},${args[2]},$a)"
                    }
                    else -> null
                }
            }
            // hsl(a)
            RE_HSLA.find(t)?.let { m ->
                val args = m.groupValues[1].split(Regex("""[,/\s]+""")).filter { it.isNotBlank() }
                return when (args.size) {
                    3 -> "hsl(${args[0]},${args[1]},${args[2]})"
                    4 -> {
                        val a = normalizeAlpha(args[3])
                        if (a == "1") "hsl(${args[0]},${args[1]},${args[2]})"
                        else "hsla(${args[0]},${args[1]},${args[2]},$a)"
                    }
                    else -> null
                }
            }
            // named color
            if (NAMED_COLORS.contains(t)) return t
            return null
        }

        private fun normalizeAlpha(a: String): String {
            val s = a.trimEnd('%')
            val d = s.toDoubleOrNull() ?: return a
            if (a.endsWith('%')) return (d / 100.0).toString().trimEnd('0').trimEnd('.')
            return d.toString().trimEnd('0').trimEnd('.').ifEmpty { "0" }
        }

        /** 在任意文本中扫描所有颜色 token 及其 range（按顺序，可重复）。返回 List<Pair<原始文本, 归一化值>> */
        fun scanColorsInText(text: String): List<Triple<String, String, IntRange>> {
            val out = mutableListOf<Triple<String, String, IntRange>>()
            val patterns = listOf(RE_HEX8, RE_HEX6, RE_HEX3, RE_RGBA, RE_HSLA)
            val consumed = BooleanArray(text.length)
            for (p in patterns) {
                for (m in p.findAll(text)) {
                    val r = m.range
                    if (r.any { consumed[it] }) continue
                    val normalized = normalizeColor(m.value) ?: continue
                    for (i in r) consumed[i] = true
                    out += Triple(m.value, normalized, r)
                }
            }
            // named color：前后都是非字母数字下划线才匹配
            val reNamed = Regex("""(?<![\w-])(?:${NAMED_COLORS.joinToString("|")})(?![\w-])""", RegexOption.IGNORE_CASE)
            for (m in reNamed.findAll(text)) {
                val r = m.range
                if (r.any { consumed[it] }) continue
                val normalized = normalizeColor(m.value) ?: continue
                for (i in r) consumed[i] = true
                out += Triple(m.value, normalized, r)
            }
            out.sortBy { it.third.first }
            return out
        }

        /** 按归一化颜色给一组候选名，语义优先（primary/secondary/accent 等），否则 --color-1/2/3。 */
        fun suggestColorVarName(normalized: String, existingNames: Set<String>, index: Int): String {
            // 启发式：基于 RGB 近似映射到常见语义色（蓝=primary, 灰=neutral/text-xxx, 红=error/danger, 绿=success, 黄=warning, 紫=accent）
            val base = deriveSemanticHint(normalized)
            var candidate = if (base.isNotEmpty()) "--color-$base" else "--color-${index + 1}"
            var i = 2
            while (candidate in existingNames) {
                candidate = if (base.isNotEmpty()) "--color-$base-$i" else "--color-${index + i}"
                i++
            }
            return candidate
        }

        private fun deriveSemanticHint(norm: String): String {
            // 转成 #rrggbb (若 hsl/rgb 无法直接映射，则返回空)
            val hex = when {
                norm.startsWith("#") && norm.length >= 7 -> norm.substring(1, 7)
                norm == "white" -> "ffffff"
                norm == "black" -> "000000"
                norm == "red" -> "ff0000"
                norm == "green" -> "008000"
                norm == "blue" -> "0000ff"
                norm == "yellow" -> "ffff00"
                norm == "purple" -> "800080"
                else -> null
            } ?: return ""
            val r = hex.substring(0,2).toInt(16)
            val g = hex.substring(2,4).toInt(16)
            val b = hex.substring(4,6).toInt(16)
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val diff = max - min
            val sum = r + g + b
            // 灰度
            if (diff < 20) {
                return when {
                    sum < 60 -> "dark"
                    sum < 180 -> "text-dark"
                    sum < 360 -> "muted"
                    sum < 600 -> "neutral"
                    else -> "bg-light"
                }
            }
            // 主色分量
            return when {
                b > r && b > g -> "primary" // 蓝
                r > g && r > b && sum > 500 -> "accent" // 粉橙
                r > g && r > b -> "danger"  // 红
                g > r && g > b -> "success" // 绿
                r > b && g > b -> "warning" // 黄
                r == g && r > b -> "warning"
                else -> ""
            }
        }
    }
}
