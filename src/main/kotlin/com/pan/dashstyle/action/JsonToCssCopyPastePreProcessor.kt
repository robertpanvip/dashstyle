package com.pan.dashstyle.action

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

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

/**
 * 顶层常量池（绕过 IntelliJ Platform instrumentCode 的影响）。
 *
 * **背景**：
 * IntelliJ Platform Gradle 插件的 `instrumentCode` 任务会对实现了 IDE 扩展点（如 `CopyPastePreProcessor`）
 * 的类写字节码（主要是 `@NotNull/@Nullable` 参数检查）。如果把 `UNITLESS`/`SHORTHAND_ARRAY` 等纯数据 Set
 * 写成「CopyPastePreProcessor 子类的 companion object 成员」，在某些 worker（单独跑
 * `JsonToCssConverterTest` 时）里会观测到「companion `<clinit>` 尚未来得及跑完 → set 返回空 →
 * `isUnitless` 全 false → 所有数字都被加 px」的诡异行为（与 DebugFailFast3 单独跑能全绿形成鲜明对比）。
 *
 * 把这些纯函数/常量池拆出来作为**顶层 object**，JVM 加载时不依附于任何 IDE 扩展点子类，
 * 不管 IDE 怎么重写扩展类字节码，这里都不会受影响。
 */
internal object JsonToCssConversionTables {
    /**
     * 不需要加 px 单位的 CSS 属性（camelCase 和 kebab-case 两份）。
     *
     * 与 React 官方 react-dom 的 `isUnitlessNumber`（CSSProperty.js）保持一致：
     * 之前错误地把 gap / grid-gap / grid-row-gap / grid-column-gap / flex-basis 放进了
     * unitless 名单 —— React 渲染 `style={{ gap: 8 }}` 时输出的是 `gap: 8px`，
     * 纯数字的 `gap: 8` 在 CSS 里是非法值，复制/提取到 CSS Module 时必须补 px。
     */
    private val UNITLESS = setOf(
        "animation-iteration-count", "aspect-ratio", "border-image-outset", "border-image-slice",
        "border-image-width", "box-flex", "box-flex-group", "box-ordinal-group", "column-count",
        "columns", "flex", "flex-grow", "flex-positive", "flex-shrink", "flex-negative", "flex-order",
        "grid-area", "grid-row", "grid-row-start", "grid-row-end", "grid-column", "grid-column-start",
        "grid-column-end", "font-weight", "line-clamp", "line-height", "opacity", "order", "orphans",
        "scale", "tab-size", "widows", "z-index", "zoom",
        "fill-opacity", "flood-opacity", "stop-opacity", "stroke-dasharray", "stroke-dashoffset",
        "stroke-miterlimit", "stroke-opacity", "stroke-width"
    )
    private val UNITLESS_CAMEL = setOf(
        "animationIterationCount", "aspectRatio", "borderImageOutset", "borderImageSlice",
        "borderImageWidth", "boxFlex", "boxFlexGroup", "boxOrdinalGroup", "columnCount",
        "columns", "flex", "flexGrow", "flexPositive", "flexShrink", "flexNegative", "flexOrder",
        "gridArea", "gridRow", "gridRowStart", "gridRowEnd", "gridColumn", "gridColumnStart",
        "gridColumnEnd", "fontWeight", "lineClamp", "lineHeight", "opacity", "order", "orphans",
        "scale", "tabSize", "widows", "zIndex", "zoom",
        "fillOpacity", "floodOpacity", "stopOpacity", "strokeDasharray", "strokeDashoffset",
        "strokeMiterlimit", "strokeOpacity", "strokeWidth"
    )

    fun isUnitless(key: String, kebabKey: String): Boolean =
        key in UNITLESS_CAMEL
            || kebabKey in UNITLESS
            || kebabKey.startsWith("animation-iteration")
            || kebabKey.startsWith("border-image-outset")

    val SHORTHAND_ARRAY: Set<String> = setOf(
        "padding", "margin", "border-radius", "border-width", "border-style", "border-color",
        "gap", "grid-gap", "grid-row-gap", "grid-column-gap", "inset"
    )
    val SHORTHAND_ARRAY_CAMEL: Set<String> = setOf(
        "padding", "margin", "borderRadius", "borderWidth", "borderStyle", "borderColor",
        "gap", "gridGap", "gridRowGap", "gridColumnGap", "inset"
    )

    val TRANSFORM_FUNCTIONS: Set<String> = setOf(
        "translateX", "translateY", "translateZ",
        "scale", "scaleX", "scaleY", "scaleZ", "scale3d",
        "rotate", "rotateX", "rotateY", "rotateZ",
        "skew", "skewX", "skewY", "perspective", "matrix", "matrix3d",
        "translate3d", "rotate3d"
    )
    val TRANSFORM_UNITLESS_FUNCS: Set<String> = setOf(
        "scale", "scaleX", "scaleY", "scaleZ", "scale3d", "matrix", "matrix3d"
    )
    val TRANSFORM_ANGLE_FUNCS: Set<String> = setOf(
        "rotate", "rotateX", "rotateY", "rotateZ", "skew", "skewX", "skewY"
    )

    /** Angular ngStyle 键单位后缀：`'font-size.px'` / `'width.%'` 键尾的 `.单位` */
    private val NG_STYLE_UNIT_SUFFIX = Regex("""\.([A-Za-z%]+)$""")

    // ----------------------------------------------------------------
    // 纯函数核心：normalize + convert 两步
    // 独立成顶层 object，不触碰 CopyPastePreProcessor（IDE 扩展点类）
    // 从而规避 IntelliJ Platform `instrumentCode` / PluginClassLoader 导致的
    // "扩展子类加载时其 companion / 成员常量未初始化" 问题。
    // ----------------------------------------------------------------

    /**
     * 从完整的 React style={{...}} 或 JS 对象字面量中提取可解析的"严格 JSON" 字符串。
     * 与 normalizePastedStyleExpression 等价（纯函数，不依赖 IDE 类）。
     *
     * 另支持框架绑定属性前缀（1.3.2）：
     *   - Vue:     `:style="{...}"` / `v-bind:style="{...}"`（值可带或不带外层引号）
     *   - Angular: `[style]="{...}"` / `[ngStyle]="{...}"`
     * `[style.width.px]="12"` 这类单值绑定不是对象形态，不在此列（返回 null，不误转换）。
     */
    @JvmStatic
    fun normalizeStyleExpression(raw: String): String? {
        val frameworkStyleAttr = Regex(
            """^\s*(?:v-bind\s*:\s*style|:style|\[\s*(?:ngStyle|style)\s*\])\s*=\s*(["']?)(\{[\s\S]*\})\1\s*$"""
        )
        val t = frameworkStyleAttr.find(raw)?.groupValues?.get(2)?.trim() ?: raw.trim()
        val doubleBrace = Regex("""^\s*[a-zA-Z_$][\w$]*\s*=\s*\{\{\s*([\s\S]*)\s*\}\}\s*$""")
        val singleBrace = Regex("""^\s*[a-zA-Z_$][\w$]*\s*=\s*\{\s*([\s\S]*)\s*\}\s*$""")
        val core = when {
            doubleBrace.matches(t) -> doubleBrace.find(t)!!.groupValues[1]
            singleBrace.matches(t) -> singleBrace.find(t)!!.groupValues[1]
            t.startsWith("{") && t.endsWith("}") -> t.substring(1, t.length - 1)
            else -> return null
        }.trim()

        val strictCandidate = "{$core}"
        val gson = threadLocalGsonForJson.get()
        if (looksLikeStrictJsonInternal(gson, strictCandidate)) return strictCandidate

        return try {
            val relaxedToStrict = jsLiteralToStrictJson("{$core}")
            if (looksLikeStrictJsonInternal(gson, relaxedToStrict)) relaxedToStrict else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 纯函数：接受 JSON 字符串 → 输出 CSS 声明块（`  property: value;\n` 多行）。
     * 不触碰任何 IDE 扩展点子类；`Util.convertJsonToCss` / IDE 扩展点 preprocessOnPaste 均走它。
     */
    @JvmStatic
    fun convertJsonStringToCss(jsonStr: String): String? {
        val gson = threadLocalGsonForJson.get()
        val obj = try {
            gson.fromJson(jsonStr, JsonObject::class.java)
        } catch (_: Exception) {
            return null
        }

        val lines = mutableListOf<String>()
        for ((rawKey, element) in obj.entrySet()) {
            // Angular ngStyle 键单位修饰（1.3.2）：'font-size.px': 12 → font-size: 12px；'width.%': 50 → width: 50%
            val unitMatch = NG_STYLE_UNIT_SUFFIX.find(rawKey)
            val key = if (unitMatch != null) rawKey.substring(0, unitMatch.range.first) else rawKey
            val explicitUnit = unitMatch?.groupValues?.get(1)
            val kebabKey = camelToKebabStableInternal(key)
            val valueCss = formatCssValueInternal(key, kebabKey, element, explicitUnit) ?: continue
            lines.add("  $kebabKey: $valueCss;")
        }
        return if (lines.isNotEmpty()) lines.joinToString("\n") + "\n" else ""
    }

    // ---------- private helpers ----------

    private val threadLocalGsonForJson = ThreadLocal.withInitial { Gson() }

    private fun looksLikeStrictJsonInternal(gson: Gson, text: String): Boolean {
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
     * 公开入口：可供插件其它模块复用。
     */
    @JvmStatic
    fun jsLiteralToStrictJson(js: String): String {
        val sb = StringBuilder(js.length + 16)
        var i = 0
        val n = js.length
        while (i < n) {
            val ch = js[i]
            when {
                ch == '/' && js.getOrNull(i + 1) == '/' -> {
                    i += 2
                    while (i < n && js[i] != '\n') i++
                }
                ch == '/' && js.getOrNull(i + 1) == '*' -> {
                    i += 2
                    while (i < n - 1 && !(js[i] == '*' && js[i + 1] == '/')) i++
                    i += 2
                }
                ch == '"' -> {
                    sb.append(ch); i++
                    while (i < n) {
                        val c = js[i]; sb.append(c)
                        if (c == '\\' && i + 1 < n) { sb.append(js[i + 1]); i += 2; continue }
                        i++
                        if (c == '"') break
                    }
                }
                ch == '\'' -> {
                    sb.append('"'); i++
                    while (i < n) {
                        val c = js[i]
                        when {
                            c == '\\' && i + 1 < n -> {
                                val nxt = js[i + 1]
                                if (nxt == '\'') sb.append('\'') else { sb.append('\\'); sb.append(nxt) }
                                i += 2
                            }
                            c == '"' -> { sb.append('\\'); sb.append('"'); i++ }
                            c == '\'' -> { sb.append('"'); i++; break }
                            else -> { sb.append(c); i++ }
                        }
                    }
                }
                isIdentifierStartInternal(ch) -> {
                    val start = i
                    while (i < n && isIdentifierPartInternal(js[i])) i++
                    val id = js.substring(start, i)
                    var k = i
                    while (k < n && js[k].isWhitespace()) k++
                    if (js.getOrNull(k) == ':' || (js.getOrNull(k) == ']' && js.getOrNull(start - 1) == '[')) {
                        sb.append('"').append(id).append('"')
                    } else {
                        sb.append(id)
                    }
                }
                ch == ',' -> {
                    var k = i + 1
                    while (k < n && js[k].isWhitespace()) k++
                    val nx = js.getOrNull(k)
                    if (nx == ']' || nx == '}') i++
                    else { sb.append(ch); i++ }
                }
                ch.isWhitespace() -> { sb.append(ch); i++ }
                else -> { sb.append(ch); i++ }
            }
        }
        return sb.toString().replace(Regex("\\bundefined\\b"), "null")
    }

    private fun isIdentifierStartInternal(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isIdentifierPartInternal(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun camelToKebabStableInternal(name: String): String = buildString {
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

    private fun formatCssValueInternal(origKey: String, kebabKey: String, el: JsonElement, explicitUnit: String? = null): String? {
        if (el.isJsonNull) return null
        if (el is JsonNull) return null

        if (el is JsonPrimitive) {
            val raw = when {
                el.isBoolean -> return null
                el.isNumber -> el.asNumber.toString()
                else -> el.asString
            }
            return formatPrimitiveValueInternal(origKey, kebabKey, raw, explicitUnit)
        }
        if (el is JsonArray) {
            return when {
                kebabKey == "transform" && el.all { it.isJsonObject } -> formatTransformArrayInternal(el)
                origKey in SHORTHAND_ARRAY_CAMEL || kebabKey in SHORTHAND_ARRAY ->
                    formatShorthandArrayInternal(origKey, kebabKey, el, explicitUnit)
                else -> el.mapNotNull { formatCssValueInternal(origKey, kebabKey, it, explicitUnit) }.joinToString(" ").ifBlank { null }
            }
        }
        if (el is JsonObject) {
            return "/* unsupported object value - please expand manually: ${el.toString().take(48)} */"
        }
        return null
    }

    private fun formatPrimitiveValueInternal(origKey: String, kebabKey: String, v: String, explicitUnit: String? = null): String? {
        var value = v
        if (value.isBlank()) return null
        // Gson lenient 会把 JS 的 undefined 读成字符串 "undefined"（先于 jsLiteralToStrictJson
        // 的 \bundefined\b→null 替换路径），这里兜底当作 null 跳过该行，避免产出非法 CSS
        if (value == "undefined") return null
        if (kebabKey == "font-family" && value.contains(' ') && !value.startsWith('\'') && !value.startsWith('"')) {
            value = "\"$value\""
        }
        val numeric = Regex("""^-?\d+(\.\d+)?$""")
        if (numeric.matches(value)) {
            // 显式单位（ngStyle '.px'/'.%' 键修饰）优先于一切默认推断
            if (explicitUnit != null) return "${value}${explicitUnit}"
            if (value == "0") return "0"
            if (isUnitless(origKey, kebabKey)) return value
            return "${value}px"
        }
        return value
    }

    private fun formatShorthandArrayInternal(origKey: String, kebabKey: String, arr: JsonArray, explicitUnit: String? = null): String? {
        return arr.mapNotNull {
            if (it.isJsonPrimitive) {
                val p = it.asJsonPrimitive
                val raw = if (p.isNumber) p.asNumber.toString() else p.asString
                formatPrimitiveValueInternal(origKey, kebabKey, raw, explicitUnit)
            } else null
        }.joinToString(" ").ifBlank { null }
    }

    private fun formatTransformArrayInternal(arr: JsonArray): String? {
        val parts = mutableListOf<String>()
        for (item in arr) {
            if (item !is JsonObject) continue
            for ((k, v) in item.entrySet()) {
                val func = k
                if (func !in TRANSFORM_FUNCTIONS) continue
                val arg = if (v.isJsonPrimitive) {
                    val p = v.asJsonPrimitive
                    val raw = if (p.isNumber) p.asNumber.toString() else p.asString
                    addDefaultUnitToTransformArgInternal(func, raw)
                } else v.toString()
                parts += "$func($arg)"
            }
        }
        return parts.joinToString(" ").ifBlank { null }
    }

    private fun addDefaultUnitToTransformArgInternal(func: String, raw: String): String {
        val num = Regex("""^-?\d+(\.\d+)?$""")
        if (!num.matches(raw)) return raw
        return when {
            func in TRANSFORM_UNITLESS_FUNCS -> raw
            func in TRANSFORM_ANGLE_FUNCS -> "${raw}deg"
            else -> "${raw}px"
        }
    }
}

class JsonToCssCopyPastePreProcessor : CopyPastePreProcessor {

    // 公开入口：Intention / Inspection 复用同一套转换规则
    object Util {
        /**
         * 接受任何 "像 style 对象" 的文本：
         *   - `{ color: 'red', fontSize: 12 }`
         *   - `style={{ color: "red" }}`
         *   - 严格 JSON
         * 返回格式化的 CSS 声明块（每一行 `  property: value;`），失败抛异常。
         *
         * **注意**：这里直接调用顶层 object 的纯函数，不实例化 JsonToCssCopyPastePreProcessor（CopyPastePreProcessor
         *  是 IDE 扩展点类，会被 IntelliJ Platform `instrumentCode` 写字节码、在某些 Gradle test worker 里
         *  可能观测到异常的初始化顺序问题）。单独跑 JsonToCssConverterTest 这种「只用转换逻辑、不用 IDE 沙箱」
         *  的场景时依然可靠。
         */
        @JvmStatic
        fun convertJsonToCss(raw: String): String {
            val normalized = JsonToCssConversionTables.normalizeStyleExpression(raw)
                ?: throw IllegalArgumentException(
                    "Not a recognized style object (expected {k:v} literal or style={...})."
                )
            return JsonToCssConversionTables.convertJsonStringToCss(normalized)
                ?: throw IllegalStateException("Failed to parse style JSON after normalization.")
        }

        /** 仅作宽松解析，不抛错：失败返回 null */
        @JvmStatic
        fun convertOrNull(raw: String): String? = runCatching { convertJsonToCss(raw) }.getOrNull()

        // 为了不改动扩展点里已有的 preprocessOnPaste 调用，保留一个处理器单例
        internal val threadLocalGson = ThreadLocal.withInitial { Gson() }
        internal val sharedProcessor = JsonToCssCopyPastePreProcessor()
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
    internal fun normalizePastedStyleExpression(raw: String): String? =
        JsonToCssConversionTables.normalizeStyleExpression(raw)

    internal fun convertInlineStyleToCss(jsonStr: String): String? =
        JsonToCssConversionTables.convertJsonStringToCss(jsonStr)

    internal fun jsLiteralToStrictJson(js: String): String =
        JsonToCssConversionTables.jsLiteralToStrictJson(js)
}
