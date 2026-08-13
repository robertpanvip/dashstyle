package com.pan.dashstyle

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RawText
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlTag
import org.jetbrains.annotations.NotNull

class JsonToCssCopyPastePreProcessor : CopyPastePreProcessor {

    // 公开入口：Intention / Inspection 复用同一套转换规则
    object Util {
        private val threadLocalGson = ThreadLocal.withInitial { Gson() }
        private val sharedProcessor = JsonToCssCopyPastePreProcessor()

        /**
         * 接受任何 "像 style 对象" 的文本：
         *   - `{ color: 'red', fontSize: 12 }`
         *   - `style={{ color: "red" }}`
         *   - 严格 JSON
         * 返回格式化的 CSS 声明块（每一行 `  property: value;`），失败抛异常。
         */
        @JvmStatic
        fun convertJsonToCss(raw: String): String {
            val normalized = sharedProcessor.normalizePastedStyleExpression(raw)
                ?: throw IllegalArgumentException(
                    "Not a recognized style object (expected {k:v} literal or style={...})."
                )
            return sharedProcessor.convertInlineStyleToCss(normalized)
                ?: throw IllegalStateException("Failed to parse style JSON after normalization.")
        }

        /** 仅作宽松解析，不抛错：失败返回 null */
        @JvmStatic
        fun convertOrNull(raw: String): String? = runCatching { convertJsonToCss(raw) }.getOrNull()
    }

    private val gson = Util.threadLocalGson.get()

    override fun preprocessOnCopy(
        file: PsiFile?,
        startOffsets: IntArray?,
        endOffsets: IntArray?,
        text: String?
    ): String? = null

    @NotNull
    override fun preprocessOnPaste(
        project: Project?,
        file: PsiFile?,
        editor: Editor?,
        text: String?,
        rawText: RawText?
    ): String {
        if (file == null || editor == null || text == null) {
            return text ?: ""
        }

        if (!isSupportedContext(editor, file)) {
            return text
        }

        val normalized = normalizePastedStyleExpression(text) ?: return text
        return convertInlineStyleToCss(normalized) ?: text
    }

    private val supportedExtensions = setOf(".css", ".less", ".scss", ".styl", ".sass")

    private fun isSupportedContext(editor: Editor, file: PsiFile): Boolean {
        val virtualFile = file.virtualFile ?: return false
        val fileName = virtualFile.name.lowercase()

        if (supportedExtensions.any { fileName.endsWith(it) }) {
            return true
        }

        if (fileName.endsWith(".vue") || fileName.endsWith(".svelte") || fileName.endsWith(".astro")) {
            val offset = editor.caretModel.offset
            var elementAtCaret: com.intellij.psi.PsiElement? = file.findElementAt(offset) ?: return false
            while (elementAtCaret != null) {
                if (elementAtCaret is XmlTag) {
                    val tagName = elementAtCaret.name.lowercase()
                    if (tagName == "style") return true
                    if (tagName == "template" || tagName == "script") return false
                }
                elementAtCaret = elementAtCaret.parent
            }
        }
        return false
    }

    // ----------------------------------------------------------------
    // 修复 #1: 从完整的 React style={{...}} 或 JS 对象字面量 {foo: 1, 'bar': 2}
    // 中提取出可解析的 "宽松 JSON" 字符串，然后转成严格 JSON
    // ----------------------------------------------------------------
    internal fun normalizePastedStyleExpression(raw: String): String? {
        // 1) 去掉前后空白 + 常见 "style={{ ... }}" / "style={...}" 包装
        val t = raw.trim()
        // 先尝试匹配 style={{ x }}
        val doubleBrace = Regex("""^\s*[a-zA-Z_$][\w$]*\s*=\s*\{\{\s*([\s\S]*)\s*\}\}\s*$""")
        val singleBrace = Regex("""^\s*[a-zA-Z_$][\w$]*\s*=\s*\{\s*([\s\S]*)\s*\}\s*$""")
        val core = when {
            doubleBrace.matches(t) -> doubleBrace.find(t)!!.groupValues[1]
            singleBrace.matches(t) -> singleBrace.find(t)!!.groupValues[1]
            t.startsWith("{") && t.endsWith("}") -> t.substring(1, t.length - 1)
            else -> return null // 不是大括号结构，直接放弃
        }.trim()

        // 2) 如果本身就是严格 JSON，快速返回
        val strictCandidate = "{$core}"
        if (looksLikeStrictJson(strictCandidate)) {
            return strictCandidate
        }

        // 3) 宽松 JS 对象字面量 → 严格 JSON
        //    - 移除行内注释 /*...*/ 和 //...
        //    - 移除尾随逗号
        //    - 给未加引号的 key 加双引号
        //    - 单引号字符串 → 双引号
        return try {
            val relaxedToStrict = jsLiteralToStrictJson("{$core}")
            if (looksLikeStrictJson(relaxedToStrict)) relaxedToStrict else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun looksLikeStrictJson(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
        return try {
            gson.fromJson(trimmed, JsonObject::class.java)
            true
        } catch (_: JsonParseException) {
            false
        }
    }

    /**
     * 将 JS 对象字面量（key 可不带引号、单引号、有注释和尾随逗号）转成合法 JSON 字符串。
     * 基于字符扫描 + 状态机，避免正则对嵌套结构的误处理。
     */
    internal fun jsLiteralToStrictJson(js: String): String {
        val sb = StringBuilder(js.length + 16)
        var i = 0
        val n = js.length
        while (i < n) {
            val ch = js[i]
            when {
                // 去掉单行注释
                ch == '/' && js.getOrNull(i + 1) == '/' -> {
                    i += 2
                    while (i < n && js[i] != '\n') i++
                }
                // 去掉块注释
                ch == '/' && js.getOrNull(i + 1) == '*' -> {
                    i += 2
                    while (i < n - 1 && !(js[i] == '*' && js[i + 1] == '/')) i++
                    i += 2
                }
                // 字符串：双引号 → 原样
                ch == '"' -> {
                    sb.append(ch); i++
                    while (i < n) {
                        val c = js[i]; sb.append(c)
                        if (c == '\\' && i + 1 < n) { sb.append(js[i + 1]); i += 2; continue }
                        i++
                        if (c == '"') break
                    }
                }
                // 字符串：单引号 → 改成双引号，内部的 \" " 做转义翻转
                ch == '\'' -> {
                    sb.append('"'); i++
                    while (i < n) {
                        val c = js[i]
                        when {
                            c == '\\' && i + 1 < n -> {
                                val nxt = js[i + 1]
                                // JS 里的 \' → JSON 里不需要 \，直接输出 '
                                if (nxt == '\'') sb.append('\'') else { sb.append('\\'); sb.append(nxt) }
                                i += 2
                            }
                            c == '"' -> { sb.append('\\'); sb.append('"'); i++ }
                            c == '\'' -> { sb.append('"'); i++; break }
                            else -> { sb.append(c); i++ }
                        }
                    }
                }
                // 未加引号的对象 key：形如  foo:  /  ["foo"]:  （我们只处理简单标识符 case）
                isIdentifierStart(ch) -> {
                    val start = i
                    while (i < n && isIdentifierPart(js[i])) i++
                    val id = js.substring(start, i)
                    // 跳过空白，看后面是不是冒号或]
                    var k = i
                    while (k < n && js[k].isWhitespace()) k++
                    if (js.getOrNull(k) == ':' || (js.getOrNull(k) == ']' && js.getOrNull(start - 1) == '[')) {
                        // 这是一个 key，包双引号
                        sb.append('"').append(id).append('"')
                    } else {
                        // 可能是 true/false/null/undefined/数字，原样输出 (undefined 转 null 不在这处理)
                        sb.append(id)
                    }
                }
                // 尾随逗号（在 ] } 前的逗号）
                ch == ',' -> {
                    var k = i + 1
                    while (k < n && js[k].isWhitespace()) k++
                    val nx = js.getOrNull(k)
                    if (nx == ']' || nx == '}') {
                        i++ // 跳过这个逗号
                    } else {
                        sb.append(ch); i++
                    }
                }
                ch.isWhitespace() -> { sb.append(ch); i++ }
                else -> { sb.append(ch); i++ }
            }
        }
        // 收尾替换： undefined → null
        return sb.toString().replace(Regex("\\bundefined\\b"), "null")
    }

    private fun isIdentifierStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isIdentifierPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    // ----------------------------------------------------------------
    // 修复 #2: CSS 转换主体 - 处理 unitless、负数、数组简写、transform 数组对象等
    // ----------------------------------------------------------------

    /** 不需要加 px 单位的 CSS 属性 (camelCase 和 kebab-case 两种形式都列，以防 key 没转换) */
    private val UNITLESS = setOf(
        "flex", "flex-grow", "flex-grow-shrink", "flex-shrink", "flex-basis",
        "order", "z-index", "opacity", "font-weight", "line-height",
        "column-count", "columns", "grid-row-start", "grid-row-end",
        "grid-column-start", "grid-column-end", "grid-row", "grid-column",
        "grid-area", "grid-row-gap", "grid-column-gap", "grid-gap", "gap",
        "aspect-ratio", "animation-iteration-count", "orphans", "widows",
        "tab-size", "transform:rotate" // 占位，实际不在这里用
    )
    private val UNITLESS_CAMEL = setOf(
        "flex", "flexGrow", "flexShrink", "flexBasis",
        "order", "zIndex", "opacity", "fontWeight", "lineHeight",
        "columnCount", "columns", "gridRowStart", "gridRowEnd",
        "gridColumnStart", "gridColumnEnd", "gridRow", "gridColumn",
        "gridArea", "gridRowGap", "gridColumnGap", "gridGap", "gap",
        "aspectRatio", "animationIterationCount", "orphans", "widows", "tabSize"
    )

    private fun isUnitless(key: String, kebabKey: String): Boolean {
        return key in UNITLESS_CAMEL || kebabKey in UNITLESS
                || kebabKey.startsWith("animation-iteration") || kebabKey.startsWith("border-image-outset")
    }

    /** 数组简写属性 - 按空格展开，每项应用单位规则 */
    private val SHORTHAND_ARRAY = setOf(
        "padding", "margin", "border-radius", "border-width", "border-style", "border-color",
        "gap", "grid-gap", "grid-row-gap", "grid-column-gap",
        "inset" // inset: top right bottom left
    )

    private val SHORTHAND_ARRAY_CAMEL = setOf(
        "padding", "margin", "borderRadius", "borderWidth", "borderStyle", "borderColor",
        "gap", "gridGap", "gridRowGap", "gridColumnGap", "inset"
    )

    /** React 的 transform: [{ translateX: 10, rotateY: "45deg" }] */
    private val TRANSFORM_FUNCTIONS = setOf(
        "translateX", "translateY", "translateZ",
        "scale", "scaleX", "scaleY", "scaleZ", "scale3d",
        "rotate", "rotateX", "rotateY", "rotateZ",
        "skew", "skewX", "skewY", "perspective", "matrix", "matrix3d",
        "translate3d", "rotate3d"
    )
    /** 数值为「倍数/比例」的 transform 函数 - 不加任何单位 */
    private val TRANSFORM_UNITLESS_FUNCS = setOf(
        "scale", "scaleX", "scaleY", "scaleZ", "scale3d", "matrix", "matrix3d"
    )
    /** 数值为「角度」的 transform 函数 - 加 deg 单位 */
    private val TRANSFORM_ANGLE_FUNCS = setOf(
        "rotate", "rotateX", "rotateY", "rotateZ", "skew", "skewX", "skewY"
    )
    // translateX / translateY / translateZ / translate3d / perspective 默认加 px

    internal fun convertInlineStyleToCss(jsonStr: String): String? {
        val obj = try {
            gson.fromJson(jsonStr, JsonObject::class.java)
        } catch (_: Exception) {
            return null
        }

        val lines = mutableListOf<String>()

        for ((key, element) in obj.entrySet()) {
            val kebabKey = camelToKebabStable(key)
            val valueCss = formatCssValue(key, kebabKey, element) ?: continue
            lines.add("  $kebabKey: $valueCss;")
        }

        return if (lines.isNotEmpty()) lines.joinToString("\n") + "\n" else ""
    }

    /** 稳定的 camel→kebab，处理已有 '-' 的输入不重复插 '-' */
    private fun camelToKebabStable(name: String): String = buildString {
        var prevLow = false
        for (ch in name) {
            if (ch == '-' || ch == '_') {
                append('-'); prevLow = false; continue
            }
            if (ch.isUpperCase()) {
                if (prevLow) append('-')
                append(ch.lowercaseChar())
                prevLow = false
            } else {
                append(ch)
                prevLow = ch.isLowerCase() || ch.isDigit()
            }
        }
    }.trimStart('-')

    /** 根据 key 类型 + JsonElement 输出合法 CSS 值字符串 */
    private fun formatCssValue(origKey: String, kebabKey: String, el: JsonElement): String? {
        if (el.isJsonNull) return null

        // null / undefined → 跳过 (已在 jsLiteralToStrictJson 把 undefined 转 null)
        if (el is JsonNull) return null

        if (el is JsonPrimitive) {
            val raw = if (el.isBoolean) {
                // CSS 没有 bool 值属性，除非是特殊属性（但都很少），跳过
                return null
            } else if (el.isNumber) {
                el.asNumber.toString()
            } else {
                el.asString
            }
            return formatPrimitiveValue(origKey, kebabKey, raw)
        }

        if (el is JsonArray) {
            return when {
                // transform: [{ translateX: 10 }] 格式
                kebabKey == "transform" && el.all { it.isJsonObject } ->
                    formatTransformArray(el)

                // 简写属性数组 padding: [10,20,30,40]
                origKey in SHORTHAND_ARRAY_CAMEL || kebabKey in SHORTHAND_ARRAY ->
                    formatShorthandArray(origKey, kebabKey, el)

                // 其它数组：空格拼接
                else -> el.mapNotNull { formatCssValue(origKey, kebabKey, it) }.joinToString(" ").ifBlank { null }
            }
        }

        if (el is JsonObject) {
            // 对 transform 之外的嵌套对象，暂不支持，输出注释方便用户改
            return "/* unsupported object value - please expand manually: ${el.toString().take(48)} */"
        }

        return null
    }

    private fun formatPrimitiveValue(origKey: String, kebabKey: String, v: String): String? {
        var value = v
        if (value.isBlank()) return null

        // font-family: 含空格的多字字体名 → 加引号
        if (kebabKey == "font-family" && value.contains(' ') && !value.startsWith('\'') && !value.startsWith('"')) {
            value = "\"$value\""
        }

        // 数值：整数/负整数/小数（不含 e/E）→ 判断是否加 px
        val numeric = Regex("""^-?\d+(\.\d+)?$""")
        if (numeric.matches(value)) {
            // 0 / unitless 属性不加单位
            if (value == "0") return "0"
            if (isUnitless(origKey, kebabKey)) return value
            // 小数且是 line-height / opacity 等已经在 unitless，这里剩下的小数都加 px
            return "${value}px"
        }

        return value
    }

    private fun formatShorthandArray(origKey: String, kebabKey: String, arr: JsonArray): String? {
        return arr.mapNotNull {
            when {
                it.isJsonPrimitive -> {
                    val raw = if (it.isNumber) it.asNumber.toString() else it.asString
                    formatPrimitiveValue(origKey, kebabKey, raw)
                }
                else -> null
            }
        }.joinToString(" ").ifBlank { null }
    }

    private fun formatTransformArray(arr: JsonArray): String? {
        val parts = mutableListOf<String>()
        for (item in arr) {
            if (item !is JsonObject) continue
            for ((k, v) in item.entrySet()) {
                val func = k
                if (func !in TRANSFORM_FUNCTIONS) continue
                val arg = when {
                    v.isJsonPrimitive -> {
                        val raw = if (v.isNumber) v.asNumber.toString() else v.asString
                        // translate/rotate 类：非角度、无单位的数字加 px（角度 deg 单位的不要）
                        addDefaultUnitToTransformArg(func, raw)
                    }
                    else -> v.toString()
                }
                parts += "$func($arg)"
            }
        }
        return parts.joinToString(" ").ifBlank { null }
    }

    private fun addDefaultUnitToTransformArg(func: String, raw: String): String {
        val num = Regex("""^-?\d+(\.\d+)?$""")
        if (!num.matches(raw)) return raw
        return when {
            func in TRANSFORM_UNITLESS_FUNCS -> raw // scale/matrix 是倍数，不加单位
            func in TRANSFORM_ANGLE_FUNCS -> "${raw}deg" // rotate/skew 是角度
            else -> "${raw}px" // translate/perspective 等加 px
        }
    }
}
