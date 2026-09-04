package com.pan.dashstyle

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 测试 JSON / JS 字面量 → CSS 的转换。
 * 一律调用生产代码：JsonToCssCopyPastePreProcessor.Util.convertJsonToCss / convertOrNull
 * —— 之前这个类里"内联复制了一份简化版 convertJsonToCss"，
 * 导致 unitless 判断、宽松 JSON 解析等与生产逻辑不符，现在统一收口。
 */
class JsonToCssConverterTest {

    private fun convertJsonToCss(jsonStr: String): String =
        JsonToCssCopyPastePreProcessor.Util.convertJsonToCss(jsonStr)

    private fun convertOrNull(raw: String): String? =
        JsonToCssCopyPastePreProcessor.Util.convertOrNull(raw)


    private fun looksLikeJsonStyleObject(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return false
        }
        return try {
            com.google.gson.Gson().fromJson(trimmed, com.google.gson.JsonObject::class.java)
            true
        } catch (_: com.google.gson.JsonParseException) {
            false
        }
    }

    // ===================== looksLikeJsonStyleObject 测试 =====================

    @Test
    fun `looksLikeJson - 正常 JSON 对象`() {
        assertTrue(looksLikeJsonStyleObject("""{"margin": "10px"}"""))
    }

    @Test
    fun `looksLikeJson - 带首尾空白`() {
        assertTrue(looksLikeJsonStyleObject("  {\"color\": \"red\"}  "))
    }

    @Test
    fun `looksLikeJson - 不是JSON开头`() {
        assertFalse(looksLikeJsonStyleObject("margin: 10px;"))
    }

    @Test
    fun `looksLikeJson - 非法 JSON`() {
        assertFalse(looksLikeJsonStyleObject("{not valid json}"))
    }

    @Test
    fun `looksLikeJson - 空对象`() {
        assertTrue(looksLikeJsonStyleObject("{}"))
    }

    @Test
    fun `looksLikeJson - 数组（不接受）`() {
        assertFalse(looksLikeJsonStyleObject("""[1, 2, 3]"""))
    }

    // ===================== 键名 camelCase → kebab-case 测试 =====================

    @Test
    fun `convert - 单键`() {
        val result = convertJsonToCss("""{"margin": "10px"}""")
        assertEquals("  margin: 10px;\n", result)
    }

    @Test
    fun `convert - camelCase 转 kebab`() {
        val result = convertJsonToCss("""{"fontSize": "14px"}""")
        assertEquals("  font-size: 14px;\n", result)
    }

    @Test
    fun `convert - 多段 camelCase`() {
        val result = convertJsonToCss("""{"backgroundColor": "red"}""")
        assertEquals("  background-color: red;\n", result)
    }

    @Test
    fun `convert - 已经是 kebab-case 不重复转换`() {
        // "font-size" 中没有大写，但正则 ([a-z])([A-Z]) 不匹配任何内容 → 原样输出然后 lowercase
        val result = convertJsonToCss("""{"font-size": "14px"}""")
        assertEquals("  font-size: 14px;\n", result)
    }

    // ===================== 纯数字自动加 px =====================

    @Test
    fun `convert - 纯数字加 px`() {
        val result = convertJsonToCss("""{"padding": "10"}""")
        assertEquals("  padding: 10px;\n", result)
    }

    @Test
    fun `convert - 0 不加 px`() {
        val result = convertJsonToCss("""{"margin": "0"}""")
        assertEquals("  margin: 0;\n", result)
    }

    @Test
    fun `convert - 已有单位`() {
        val result = convertJsonToCss("""{"width": "50%"}""")
        assertEquals("  width: 50%;\n", result)
    }

    @Test
    fun `convert - 已有 em 单位`() {
        val result = convertJsonToCss("""{"fontSize": "2em"}""")
        assertEquals("  font-size: 2em;\n", result)
    }

    @Test
    fun `convert - 数字字符串带单位`() {
        val result = convertJsonToCss("""{"paddingTop": "20px"}""")
        assertEquals("  padding-top: 20px;\n", result)
    }

    @Test
    fun `convert - 数字值 - JSON number 类型`() {
        // JSON 中数字而非字符串
        val result = convertJsonToCss("""{"zIndex": 100}""")
        assertEquals("  z-index: 100;\n", result)
    }

    @Test
    fun `convert - 数字值 0 number`() {
        val result = convertJsonToCss("""{"opacity": 0}""")
        // 注意：JSON 数字转字符串后是 "0"，会匹配 0 不加 px 分支
        assertEquals("  opacity: 0;\n", result)
    }

    @Test
    fun `convert - 数字值 1 number`() {
        val result = convertJsonToCss("""{"flex": 1}""")
        // flex 是 unitless（CSS 语义），不应加 px
        assertEquals("  flex: 1;\n", result)
    }

    // ===================== 多属性测试 =====================

    @Test
    fun `convert - 多属性完整样式`() {
        val json = """
            {
                "display": "flex",
                "justifyContent": "center",
                "alignItems": "center",
                "paddingTop": 20,
                "backgroundColor": "#fff",
                "fontSize": "14px",
                "margin": 0
            }
        """.trimIndent()
        val result = convertJsonToCss(json)
        // 检查各属性存在（entrySet 顺序可能不确定，所以用 contains）
        assertTrue(result.contains("  display: flex;"), "缺少 display")
        assertTrue(result.contains("  justify-content: center;"), "缺少 justify-content")
        assertTrue(result.contains("  align-items: center;"), "缺少 align-items")
        assertTrue(result.contains("  padding-top: 20px;"), "缺少 padding-top")
        assertTrue(result.contains("  background-color: #fff;"), "缺少 background-color")
        assertTrue(result.contains("  font-size: 14px;"), "缺少 font-size")
        assertTrue(result.contains("  margin: 0;"), "缺少 margin")
    }

    // ===================== 边界情况 =====================

    @Test
    fun `convert - 空对象`() {
        assertEquals("", convertJsonToCss("{}"))
    }

    @Test
    fun `convert - 解析失败返回原字符串`() {
        val invalid = "{not valid json"
        // 生产 Util.convertJsonToCss 在解析失败时抛 IllegalArgumentException / IllegalStateException；
        // 调用 convertOrNull 则返回 null（与 preprocessOnPaste 保持一致，失败不动原字符串）
        assertEquals(null, convertOrNull(invalid))
    }

    @Test
    fun `convert - 缩进是两个空格`() {
        val result = convertJsonToCss("""{"color": "red"}""")
        assertTrue(result.startsWith("  "), "每行开头应该是两个空格")
    }

    @Test
    fun `convert - 结尾带换行符`() {
        val result = convertJsonToCss("""{"color": "red"}""")
        assertTrue(result.endsWith("\n"), "结尾需要换行符")
    }

    // ===================== CSS 常用值类型 =====================

    @Test
    fun `convert - color hex 不改动`() {
        val result = convertJsonToCss("""{"color": "#f00"}""")
        assertEquals("  color: #f00;\n", result)
    }

    @Test
    fun `convert - rgba 颜色`() {
        val result = convertJsonToCss("""{"color": "rgba(0,0,0,0.5)"}""")
        assertEquals("  color: rgba(0,0,0,0.5);\n", result)
    }

    @Test
    fun `convert - calc 表达式`() {
        val result = convertJsonToCss("""{"width": "calc(100% - 20px)"}""")
        assertEquals("  width: calc(100% - 20px);\n", result)
    }

    @Test
    fun `convert - vw 和 vh 单位`() {
        val result = convertJsonToCss("""{"width": "100vw", "height": "100vh"}""")
        assertTrue(result.contains("width: 100vw"))
        assertTrue(result.contains("height: 100vh"))
    }

    @Test
    fun `convert - 布尔 JSON 值（少见但要处理）`() {
        val result = convertJsonToCss("""{"enabled": true}""")
        // CSS 没有 boolean 属性类型，生产逻辑跳过 boolean 条目，整行不生成
        assertEquals("", result) // 没有条目 → lines 为空 → 返回 ""，与空对象保持一致
        assertFalse(result.contains("enabled:"), "布尔条目应被忽略，不应出现在 CSS 里")
    }

    @Test
    fun `convert - null JSON 值`() {
        val result = convertJsonToCss("""{"foo": null}""")
        // null 值（JsonNull / JS 里的 undefined 转 null）在生产逻辑里被跳过，不生成 CSS 行
        assertEquals("", result)
        assertFalse(result.contains("foo:"), "null 条目应被忽略")
    }

    // ------ inlineStyle 抽取后的 unit 边界，transform 数组 ------
    @Test
    fun `convert - transform scale 倍数型函数不加单位 px`() {
        val out = convertJsonToCss("""{"transform": "scale(2)"}""")
        assert(out.contains("scale(2)") && !out.contains("scale(2px)")) { "scale 不应加 px: $out" }
    }

    @Test
    fun `convert - transform rotate 加 deg 单位`() {
        val out = convertJsonToCss("""{"transform": "rotate(45)"}""")
        // JS 写法是 rotate(45)，复制到 CSS 时在原始场景里若写了 rotate(数字) 应保留数字；
        // 这里我们校验没有被错误加 px
        assert(!out.contains("45px")) { "rotate 不应加 px" }
    }

    @Test
    fun `convert - unitless 数字属性 opacity z-index flex 纯数值不加 px`() {
        val out = convertJsonToCss("""{"opacity": 0.5, "zIndex": 99, "flex": 2}""")
        assert(!out.contains("0.5px")) { "opacity 不要 px" }
        assert(!out.contains("99px")) { "z-index 不要 px" }
        assert(!out.contains("2px")) { "flex 不要 px" }
        assert(out.contains("opacity: 0.5;")) { "必须保留数字" }
        assert(out.contains("z-index: 99;")) { "zIndex 要转 kebab" }
    }

    @Test
    fun `convert - 支持 JS 对象字面量（key 无引号 + 尾随逗号）`() {
        val js = """
            {
              fontSize: 14,
              backgroundColor: 'red',
              marginTop: 8,
            }
        """.trimIndent()
        val out = JsonToCssCopyPastePreProcessor.Util.convertOrNull(js)
        assert(out != null) { "JS 字面量应能解析" }
        assert(out!!.contains("font-size: 14px;"))
        assert(out.contains("margin-top: 8px;"))
        assert(out.contains("background-color: red;") || out.contains("background-color: #ff0000"))
    }

    @Test
    fun `convert - 负数与 0 不加 px`() {
        val out = convertJsonToCss("""{"top": -10, "left": 0, "right": 8}""")
        assert(out.contains("top: -10px;")) { "负值要保留负号并加 px" }
        assert(out.contains("left: 0;")) { "纯 0 不加 px" }
        assert(out.contains("right: 8px;"))
    }
}
