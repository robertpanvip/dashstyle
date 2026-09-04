package com.pan.dashstyle.support

/**
 * 颜色提取与归一化（支持 HEX3 / HEX6 / HEX8 / rgb(a) / hsl(a)）。
 * 从 Util.kt 拆出，职责聚焦于颜色处理。
 */
object ColorUtil {

    private val RE_HEX8 = Regex("""#([0-9a-fA-F]{8})\b""")
    private val RE_HEX6 = Regex("""#([0-9a-fA-F]{6})\b""")
    private val RE_HEX3 = Regex("""#([0-9a-fA-F]{3})\b""")
    private val RE_RGB = Regex("""rgb\(\s*([^)]*)\)""", RegexOption.IGNORE_CASE)
    private val RE_RGBA = Regex("""rgba\(\s*([^)]*)\)""", RegexOption.IGNORE_CASE)
    private val RE_HSL = Regex("""hsl\(\s*([^)]*)\)""", RegexOption.IGNORE_CASE)
    private val RE_HSLA = Regex("""hsla\(\s*([^)]*)\)""", RegexOption.IGNORE_CASE)
    private val RE_SPLIT_COLOR_ARGS = Regex("""[,/\s]+""")
    private val RE_WORD_TOKEN = Regex("""[A-Za-z][A-Za-z0-9-]*""")
    private val COLOR_STRUCT_PATTERNS: List<Regex> = listOf(RE_HEX8, RE_HEX6, RE_HEX3, RE_RGBA, RE_RGB, RE_HSLA, RE_HSL)

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

    private val NAMED_TO_HEX6: Map<String, String> = mapOf(
        "white" to "ffffff", "black" to "000000", "red" to "ff0000", "green" to "008000",
        "blue" to "0000ff", "yellow" to "ffff00", "purple" to "800080", "gray" to "808080",
        "grey" to "808080", "orange" to "ffa500", "pink" to "ffc0cb", "cyan" to "00ffff",
        "magenta" to "ff00ff", "lime" to "00ff00", "maroon" to "800000", "navy" to "000080",
        "olive" to "808000", "teal" to "008080", "silver" to "c0c0c0", "aqua" to "00ffff",
        "fuchsia" to "ff00ff"
    )

    fun normalizeColor(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val lower = t.lowercase()
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

    private fun normalizeAlpha(a: String): String {
        val s = a.trimEnd('%')
        val d = s.toDoubleOrNull() ?: return a
        if (a.endsWith('%')) return (d / 100.0).toString().trimEnd('0').trimEnd('.')
        return d.toString().trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    fun scanColorsInText(text: String): List<Triple<String, String, IntRange>> {
        val n = text.length
        val out = ArrayList<Triple<String, String, IntRange>>(32)
        val consumed = BooleanArray(n)
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
        RE_WORD_TOKEN.findAll(text).forEach { m ->
            val r = m.range; val s = r.first; val e = r.last
            if (e >= n) return@forEach
            if (isAnyConsumed(consumed, s, e)) return@forEach
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

    private fun isAnyConsumed(consumed: BooleanArray, start: Int, end: Int): Boolean {
        var i = start
        while (i <= end) { if (consumed[i]) return true; i++ }
        return false
    }

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
            r > b && g > b && Math.abs(r - g) <= Math.max(r, g) * 0.55 -> "warning"
            r > g && r > b && sum > 500 -> "accent"
            r > g && r > b -> "danger"
            g > r && g > b -> "success"
            r == g && r > b -> "warning"
            else -> ""
        }
    }
}
