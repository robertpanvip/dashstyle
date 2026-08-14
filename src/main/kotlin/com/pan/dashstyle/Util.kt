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
                var nextUp = false
                for ((idx, ch) in name.withIndex()) {
                    if (ch == '-') {
                        // 前导连字符不触发大写（避免把第一个字符变成大写 FooBar → 要 fooBar）
                        if (idx == 0) continue
                        nextUp = true
                        continue
                    }
                    append(if (nextUp) ch.uppercaseChar() else ch)
                    nextUp = false
                }
            }
        }

        fun camelToKebab(name: String): String {
            // 特例 1：全大写字母（ABC / HTTP / ID）→ 每字符间插 '-'，保留原大写
            //   典型期望：ABC → A-B-C；这和"fooBar → foo-bar、HTTPServer → http-server"的普通规则不同。
            if (name.length >= 2 && name.all { it.isLetter() && it.isUpperCase() }) {
                return name.mapIndexed { i, c -> if (i == 0) "$c" else "-$c" }.joinToString("")
            }
            // 特例 2：不含大写 → 原样（已是 kebab-case 或纯小写）
            var hasUpper = false
            for (ch in name) if (ch.isUpperCase()) { hasUpper = true; break }
            if (!hasUpper) return name

            // 普通规则：camelCase / PascalCase → 小写 + 边界 '-'
            //   处理"前一个小写 + 当前大写"或"连续大写 + 当前大写后紧跟小写"（XMLParser → XML-Parser）
            return buildString(name.length + 4) {
                for ((idx, ch) in name.withIndex()) {
                    when {
                        ch.isUpperCase() -> {
                            val boundary = when {
                                idx == 0 -> false
                                !name[idx - 1].isUpperCase() -> true
                                // 连续大写的尾部边界（如 HTTPServer 里 S 的下一个是 e → 在 S 前插 -）
                                idx + 1 < name.length && name[idx + 1].isLowerCase() -> true
                                else -> false
                            }
                            if (boundary) append('-')
                            append(ch.lowercaseChar())
                        }
                        else -> append(ch)
                    }
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
        // 性能优化：所有正则全部顶层预编译；命名颜色不用巨长 alternation，用"扫单词 + Set 查询"。
        // ================================================================
        private val RE_HEX8 = Regex("""#([0-9a-fA-F]{8})\b""")
        private val RE_HEX6 = Regex("""#([0-9a-fA-F]{6})\b""")
        private val RE_HEX3 = Regex("""#([0-9a-fA-F]{3})\b""")
        // 注意：rgb() 与 rgba() 必须分开匹配（不能用 rgba? 一把抓）
        // - rgb() 必须恰好 3 个通道，通道合法则返回 rgb(...)
        // - rgba() 必须恰好 4 个通道，通道合法且 alpha 合法才返回 rgba(...)/rgb(...)
        //   如果调用方写的是 rgba(1,2,3)（缺 alpha）→ 非法，返回 null。
        private val RE_RGB = Regex("""rgb\(\s*([^)]*)\)""", RegexOption.IGNORE_CASE)
        private val RE_RGBA = Regex("""rgba\(\s*([^)]*)\)""", RegexOption.IGNORE_CASE)
        private val RE_HSL = Regex("""hsl\(\s*([^)]*)\)""", RegexOption.IGNORE_CASE)
        private val RE_HSLA = Regex("""hsla\(\s*([^)]*)\)""", RegexOption.IGNORE_CASE)
        private val RE_SPLIT_COLOR_ARGS = Regex("""[,/\s]+""")
        /** 扫任意 ASCII 单词 token；再通过 NAMED_COLORS.contains(lower) 过滤，避免构造 148 分支 alternation 正则 */
        private val RE_WORD_TOKEN = Regex("""[A-Za-z][A-Za-z0-9-]*""")
        /** 5 种结构型颜色的固定扫描顺序（HEX8 → HEX6 → HEX3 → RGBA → RGB → HSLA → HSL）
         *  注意：RGBA/HSLA 必须排在 RGB/HSL **前面**，否则 `rgba(1,2,3,0.5)` 会被 RGB 正则错误截断为 `rgb(1,2,3,` 之类的异常值。*/
        private val COLOR_STRUCT_PATTERNS: List<Regex> = listOf(RE_HEX8, RE_HEX6, RE_HEX3, RE_RGBA, RE_RGB, RE_HSLA, RE_HSL)

        /** 常见的 148 CSS 命名颜色（小写） */
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
        /** 命名颜色 → 6 位 hex，避免语义名推断时 when 匹配失败 */
        private val NAMED_TO_HEX6: Map<String, String> = mapOf(
            "white" to "ffffff", "black" to "000000", "red" to "ff0000", "green" to "008000",
            "blue" to "0000ff", "yellow" to "ffff00", "purple" to "800080", "gray" to "808080",
            "grey" to "808080", "orange" to "ffa500", "pink" to "ffc0cb", "cyan" to "00ffff",
            "magenta" to "ff00ff", "lime" to "00ff00", "maroon" to "800000", "navy" to "000080",
            "olive" to "808000", "teal" to "008080", "silver" to "c0c0c0", "aqua" to "00ffff",
            "fuchsia" to "ff00ff"
        )

        /** 归一化颜色到"规范形态"（hex6 #rrggbb / hex8 #rrggbbaa / rgba() / hsla()），用于等值分组比较。 */
        fun normalizeColor(raw: String): String? {
            val t = raw.trim()
            if (t.isEmpty()) return null
            val lower = t.lowercase()
            // HEX8 #rrggbbaa (含 alpha) — 严格全串匹配
            if (lower.startsWith('#')) {
                val hexBody = lower.substring(1)
                when {
                    hexBody.length == 8 && hexBody.all { it in '0'..'9' || it in 'a'..'f' } -> {
                        val r = hexBody.substring(0, 2); val g = hexBody.substring(2, 4)
                        val b = hexBody.substring(4, 6); val a = hexBody.substring(6, 8)
                        return if (a == "ff") "#$r$g$b" else "#$r$g$b$a"
                    }
                    hexBody.length == 6 && hexBody.all { it in '0'..'9' || it in 'a'..'f' } -> {
                        return "#$hexBody"
                    }
                    hexBody.length == 3 && hexBody.all { it in '0'..'9' || it in 'a'..'f' } -> {
                        return "#${hexBody[0]}${hexBody[0]}${hexBody[1]}${hexBody[1]}${hexBody[2]}${hexBody[2]}"
                    }
                    else -> return null
                }
            }

            // rgb(a) 严格按函数名分：rgba 必须 4 参数，rgb 必须 3 参数；参数越界直接非法。
            val rgbaMatch = RE_RGBA.matchEntire(t)
            if (rgbaMatch != null) {
                val args = RE_SPLIT_COLOR_ARGS.split(rgbaMatch.groupValues[1]).filter { it.isNotBlank() }.map { it.trim() }
                if (args.size != 4) return null
                if (!isValidRgbChannel(args[0]) || !isValidRgbChannel(args[1]) || !isValidRgbChannel(args[2])) return null
                val a = normalizeAlpha(args[3])
                return if (a == "1") "rgb(${args[0]},${args[1]},${args[2]})"
                else "rgba(${args[0]},${args[1]},${args[2]},$a)"
            }
            val rgbMatch = RE_RGB.matchEntire(t)
            if (rgbMatch != null) {
                val args = RE_SPLIT_COLOR_ARGS.split(rgbMatch.groupValues[1]).filter { it.isNotBlank() }.map { it.trim() }
                if (args.size != 3) return null
                if (!isValidRgbChannel(args[0]) || !isValidRgbChannel(args[1]) || !isValidRgbChannel(args[2])) return null
                return "rgb(${args[0]},${args[1]},${args[2]})"
            }

            // hsl(a)：同样严格按函数名分。
            val hslaMatch = RE_HSLA.matchEntire(t)
            if (hslaMatch != null) {
                val args = RE_SPLIT_COLOR_ARGS.split(hslaMatch.groupValues[1]).filter { it.isNotBlank() }.map { it.trim() }
                if (args.size != 4) return null
                val hueNorm = args[0].trimEnd('%')
                if (hueNorm.toDoubleOrNull() == null) return null
                if (!isPercentOrValidRange(args[1], 0.0..100.0)) return null
                if (!isPercentOrValidRange(args[2], 0.0..100.0)) return null
                val a = normalizeAlpha(args[3])
                return if (a == "1") "hsl($hueNorm,${args[1]},${args[2]})"
                else "hsla($hueNorm,${args[1]},${args[2]},$a)"
            }
            val hslMatch = RE_HSL.matchEntire(t)
            if (hslMatch != null) {
                val args = RE_SPLIT_COLOR_ARGS.split(hslMatch.groupValues[1]).filter { it.isNotBlank() }.map { it.trim() }
                if (args.size != 3) return null
                val hueNorm = args[0].trimEnd('%')
                if (hueNorm.toDoubleOrNull() == null) return null
                if (!isPercentOrValidRange(args[1], 0.0..100.0)) return null
                if (!isPercentOrValidRange(args[2], 0.0..100.0)) return null
                return "hsl($hueNorm,${args[1]},${args[2]})"
            }

            // named color
            if (NAMED_COLORS.contains(lower)) return lower
            return null
        }

        private fun isValidRgbChannel(s: String): Boolean {
            val d = s.toIntOrNull() ?: return false
            return d in 0..255
        }

        private fun isPercentOrValidRange(s: String, range: ClosedRange<Double>): Boolean {
            val raw = if (s.endsWith('%')) s.dropLast(1) else s
            val d = raw.toDoubleOrNull() ?: return false
            return d in range
        }

        private fun trimPctKeepRange(s: String): String {
            // 测试期望 hsl/hsla 参数里的 % 百分号保留
            return s.trim()
        }

        private fun normalizeAlpha(a: String): String {
            val s = a.trimEnd('%')
            val d = s.toDoubleOrNull() ?: return a
            if (a.endsWith('%')) return (d / 100.0).toString().trimEnd('0').trimEnd('.')
            return d.toString().trimEnd('0').trimEnd('.').ifEmpty { "0" }
        }

        /** 在任意文本中扫描所有颜色 token 及其 range（按顺序，可重复）。返回 List<Triple<原始文本, 归一化值, 区间>>。
         *  性能：命名颜色不再构造 148 分支 alternation，改为逐单词扫描 + Set 查询 + 边界确认。
         *       结构型颜色按"从长到短/从特殊到一般"顺序扫描 + BooleanArray.fill 批量标记。 */
        fun scanColorsInText(text: String): List<Triple<String, String, IntRange>> {
            val n = text.length
            val out = ArrayList<Triple<String, String, IntRange>>(32)
            val consumed = BooleanArray(n)
            // Phase 1: 结构型颜色（HEX8/HEX6/HEX3/RGBA/HSLA）从长到短扫描
            for (p in COLOR_STRUCT_PATTERNS) {
                p.findAll(text).forEach { m ->
                    val r = m.range
                    val s = r.first; val e = r.last
                    if (s < 0 || e >= n) return@forEach
                    if (isAnyConsumed(consumed, s, e)) return@forEach
                    val normalized = normalizeColor(m.value) ?: return@forEach
                    consumed.fill(true, s, e + 1)
                    out += Triple(m.value, normalized, s..e)
                }
            }
            // Phase 2: 命名颜色 —— 用单词 token 正则扫一遍，再用 Set.contains 匹配；边界用前后字符判断。
            RE_WORD_TOKEN.findAll(text).forEach { m ->
                val r = m.range; val s = r.first; val e = r.last
                if (e >= n) return@forEach
                if (isAnyConsumed(consumed, s, e)) return@forEach
                // 边界校验：前字符必须不是 [\w-]，后字符必须不是 [\w-]
                if (s > 0 && isWordLike(text[s - 1])) return@forEach
                if (e + 1 < n && isWordLike(text[e + 1])) return@forEach
                val word = text.substring(s, e + 1)
                val lower = word.lowercase()
                if (!NAMED_COLORS.contains(lower)) return@forEach
                consumed.fill(true, s, e + 1)
                out += Triple(word, lower, s..e)
            }
            out.sortBy { it.third.first }
            return out
        }

        private fun isWordLike(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '-'

        /** 快路径：判断 [start, end] 区间是否有任意位置已被 consume（避免 r.any { consumed[it] } 内联 lambda 开销） */
        private fun isAnyConsumed(consumed: BooleanArray, start: Int, end: Int): Boolean {
            var i = start
            while (i <= end) { if (consumed[i]) return true; i++ }
            return false
        }

        /** 按归一化颜色给一组候选名，语义优先（primary/secondary/accent 等），否则 --color-1/2/3。 */
        fun suggestColorVarName(normalized: String, existingNames: Set<String>, index: Int): String {
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
            // 转成 #rrggbb
            val hex = when {
                norm.startsWith("#") && norm.length >= 7 -> norm.substring(1, 7)
                else -> NAMED_TO_HEX6[norm]
            } ?: return ""
            val r = hex.substring(0,2).toInt(16)
            val g = hex.substring(2,4).toInt(16)
            val b = hex.substring(4,6).toInt(16)
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val diff = max - min
            val sum = r + g + b
            if (diff < 20) {
                return when {
                    sum < 60 -> "dark"
                    sum < 180 -> "text-dark"
                    sum < 360 -> "muted"
                    sum < 600 -> "neutral"
                    else -> "bg-light"
                }
            }
            return when {
                b > r && b > g -> "primary"
                // 黄/橙/金色：红和绿分量都远高于蓝（两个暖色分量），
                // 且红、绿之间差距在 55% 以内（避免把"深红+深绿但蓝=0"错判为 warning）。
                r > b && g > b && Math.abs(r - g) <= Math.max(r, g) * 0.55 -> "warning"
                r > g && r > b && sum > 500 -> "accent"
                r > g && r > b -> "danger"
                g > r && g > b -> "success"
                r == g && r > b -> "warning"
                else -> ""
            }
        }
    }
}
