package com.pan.dashstyle

import java.awt.Color
import kotlin.math.roundToInt

/**
 * CSS 阴影（box-shadow / text-shadow）解析与预览元数据 —— 纯逻辑层（不依赖 IDE SDK）。
 *
 * `box-shadow: inset 2px 4px 6px 8px rgba(0,0,0,.5), 1px 1px 2px #000;`
 * 解析为若干层 [Layer]，每层含 inset / offset / blur / spread / color。
 * 无法解析的声明（含 var() / calc() 等动态值）返回该层为 null 或空列表，绝不抛异常。
 */
object ShadowResolver {

    /** 单层阴影。 */
    data class Layer(
        val inset: Boolean,
        val offsetX: Double,
        val offsetY: Double,
        val blur: Double,
        val spread: Double,
        /** 解析出的颜色；null 表示未指定（默认 currentColor，预览用中性色）。 */
        val color: Color?
    ) {
        val hasBlur: Boolean get() = blur > 0
    }

    /** 解析 box-shadow/text-shadow 值；非法或空返回空列表。 */
    fun parse(value: String?): List<Layer> {
        val t = value?.trim() ?: return emptyList()
        if (t.isEmpty() || t.equals("none", ignoreCase = true)) return emptyList()
        return splitTopLevel(t, ',')
            .mapNotNull { parseLayer(it) }
    }

    private fun parseLayer(s: String): Layer? {
        val tokens = splitTokens(s)
        if (tokens.isEmpty()) return null
        var inset = false
        val lengths = ArrayList<Double>(4)
        var color: Color? = null
        for (tok in tokens) {
            when {
                tok.equals("inset", ignoreCase = true) -> inset = true
                else -> {
                    val len = parseLengthPx(tok)
                    if (len != null) {
                        if (lengths.size <= 3) lengths.add(len)
                        continue
                    }
                    val c = CssColorParser.parse(tok)
                    if (c != null) color = c
                    else return null // 出现无法理解的 token（var/calc 等）→ 该层不可预览
                }
            }
        }
        if (lengths.size < 2) return null // 至少需要 offset-x 与 offset-y
        return Layer(
            inset = inset,
            offsetX = lengths[0],
            offsetY = lengths[1],
            blur = lengths.getOrNull(2) ?: 0.0,
            spread = lengths.getOrNull(3) ?: 0.0,
            color = color
        )
    }

    /** 单个长度 token → px；纯数字按 px，带单位复用 [CssUnitAssistant.toPx]。 */
    private fun parseLengthPx(s: String): Double? {
        val t = s.trim()
        if (t.matches(Regex("""[+-]?\d+(\.\d+)?"""))) return t.toDouble()
        val len = CssUnitAssistant.parseLength(t) ?: return null
        return CssUnitAssistant.toPx(len)
    }

    /** 按 [sep] 切分，忽略括号（如 rgba()/calc()）内的分隔符。 */
    private fun splitTopLevel(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in s) {
            when {
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { depth--; sb.append(c) }
                c == sep && depth == 0 -> { out.add(sb.toString().trim()); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out
    }

    /** 按空白切分，忽略括号内部（如 `calc(100% - 20px)` 保留为整体）。 */
    private fun splitTokens(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in s) {
            when {
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { depth--; sb.append(c) }
                c.isWhitespace() && depth == 0 -> { if (sb.isNotBlank()) { out.add(sb.toString()); sb.setLength(0) } }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out
    }
}

/**
 * CSS 颜色解析 —— 纯逻辑层。
 * 支持 `#rgb`/`#rrggbb`/`#rrggbbaa`、`rgb()/rgba()`（数值或百分比）、
 * 以及 java.awt.Color 暴露的命名色（red/white/…）与 `transparent`。
 */
object CssColorParser {

    fun parse(s: String?): Color? {
        val t = s?.trim() ?: return null
        return try {
            when {
                t.startsWith("#") -> parseHex(t)
                t.startsWith("rgb", ignoreCase = true) -> parseRgb(t)
                t.startsWith("hsl", ignoreCase = true) -> parseHsl(t)
                else -> parseNamed(t)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseHex(t: String): Color? {
        val hex = t.substring(1)
        return when (hex.length) {
            3 -> {
                val r = hex[0].toString().repeat(2).toInt(16)
                val g = hex[1].toString().repeat(2).toInt(16)
                val b = hex[2].toString().repeat(2).toInt(16)
                Color(r, g, b)
            }
            4 -> {
                val r = hex[0].toString().repeat(2).toInt(16)
                val g = hex[1].toString().repeat(2).toInt(16)
                val b = hex[2].toString().repeat(2).toInt(16)
                val a = hex[3].toString().repeat(2).toInt(16)
                Color(r, g, b, a)
            }
            6 -> {
                val r = hex.substring(0, 2).toInt(16)
                val g = hex.substring(2, 4).toInt(16)
                val b = hex.substring(4, 6).toInt(16)
                Color(r, g, b)
            }
            8 -> {
                val r = hex.substring(0, 2).toInt(16)
                val g = hex.substring(2, 4).toInt(16)
                val b = hex.substring(4, 6).toInt(16)
                val a = hex.substring(6, 8).toInt(16)
                Color(r, g, b, a)
            }
            else -> null
        }
    }

    private fun parseRgb(t: String): Color? {
        val m = Regex("""rgba?\(([^)]+)\)""", RegexOption.IGNORE_CASE).find(t) ?: return null
        val parts = m.groupValues[1].split(Regex(""",\s*""")).map { it.trim() }
        if (parts.size < 3) return null
        fun chan(s: String): Int {
            if (s.endsWith('%')) return (s.dropLast(1).toDoubleOrNull()?.let { it * 255.0 / 100.0 })?.let { Math.round(it).toInt() }?.coerceIn(0, 255) ?: 0
            return s.toDoubleOrNull()?.toInt()?.coerceIn(0, 255) ?: 0
        }
        val r = chan(parts[0]); val g = chan(parts[1]); val b = chan(parts[2])
        var a = 255
        if (parts.size >= 4) {
            val raw = parts[3]
            a = if (raw.endsWith('%')) (raw.dropLast(1).toDoubleOrNull()?.let { it * 255.0 / 100.0 })?.let { Math.round(it).toInt() }?.coerceIn(0, 255) ?: 255
            else (raw.toDoubleOrNull()?.times(255))?.let { Math.round(it).toInt() }?.coerceIn(0, 255) ?: 255
        }
        return Color(r, g, b, a)
    }

    private fun parseHsl(t: String): Color? {
        val m = Regex("""hsla?\(([^)]+)\)""", RegexOption.IGNORE_CASE).find(t) ?: return null
        val parts = m.groupValues[1].split(Regex(""",\s*""")).map { it.trim() }
        if (parts.size < 3) return null
        val h = parts[0].toDoubleOrNull() ?: return null
        val sPct = parts[1].removeSuffix("%").toDoubleOrNull() ?: return null
        val lPct = parts[2].removeSuffix("%").toDoubleOrNull() ?: return null
        var a = 255
        if (parts.size >= 4) {
            val raw = parts[3]
            a = (raw.removeSuffix("%").toDoubleOrNull()?.let { if (raw.endsWith('%')) it * 255.0 / 100.0 else it * 255 })?.let { Math.round(it).toInt() }?.coerceIn(0, 255) ?: 255
        }
        val c = hslToRgb(h % 360, sPct / 100.0, lPct / 100.0)
        return Color(c[0], c[1], c[2], a)
    }

    /** hsl → [r,g,b]，h∈[0,360)，s/l∈[0,1]。 */
    private fun hslToRgb(h: Double, s: Double, l: Double): IntArray {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val hp = h / 60.0
        val x = c * (1 - kotlin.math.abs(hp % 2 - 1))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0.0)
            hp < 2 -> Triple(x, c, 0.0)
            hp < 3 -> Triple(0.0, c, x)
            hp < 4 -> Triple(0.0, x, c)
            hp < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2
        return intArrayOf(
            ((r1 + m) * 255).roundToInt().coerceIn(0, 255),
            ((g1 + m) * 255).roundToInt().coerceIn(0, 255),
            ((b1 + m) * 255).roundToInt().coerceIn(0, 255)
        )
    }

    private fun parseNamed(t: String): Color? {
        if (t.equals("transparent", ignoreCase = true)) return Color(0, 0, 0, 0)
        val field = try {
            java.awt.Color::class.java.getField(t.uppercase())
        } catch (_: NoSuchFieldException) {
            return null
        }
        return field.get(null) as? Color
    }
}