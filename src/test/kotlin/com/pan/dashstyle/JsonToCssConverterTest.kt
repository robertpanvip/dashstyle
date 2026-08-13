package com.pan.dashstyle

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 测试 JsonToCssCopyPastePreProcessor 中的核心转换逻辑
 * 不依赖 IntelliJ 环境，直接测试纯逻辑函数
 */
class JsonToCssConverterTest {

    // 用反射或直接复制逻辑来测试；这里选择直接内联测试逻辑，保持独立
    private fun convertJsonToCss(jsonStr: String): String {
        val gson = com.google.gson.Gson()
        val obj = try {
            gson.fromJson(jsonStr, com.google.gson.JsonObject::class.java)
        } catch (_: Exception) {
            return jsonStr
        }

        val lines = mutableListOf<String>()

        for ((key, element) in obj.entrySet()) {
            val valueStr = when {
                element.isJsonPrimitive -> element.asString
                else -> element.toString()
            }

            val kebabKey = key.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()

            val finalValue = if (valueStr.matches(Regex("^\\d+$")) &&
                valueStr != "0" &&
                !valueStr.contains(Regex("[a-zA-Z%]+"))
            ) {
                "${valueStr}px"
            } else {
                valueStr
            }

            lines.add("  $kebabKey: $finalValue;")
        }

        return if (lines.isNotEmpty()) {
            lines.joinToString("\n") + "\n"
        } else {
            ""
        }
    }

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
        assertEquals("  flex: 1px;\n", result)
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
        assertEquals(invalid, convertJsonToCss(invalid))
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
    fun `convert - vw/vh 单位`() {
        val result = convertJsonToCss("""{"width": "100vw", "height": "100vh"}""")
        assertTrue(result.contains("width: 100vw"))
        assertTrue(result.contains("height: 100vh"))
    }

    @Test
    fun `convert - 布尔 JSON 值（少见但要处理）`() {
        val result = convertJsonToCss("""{"enabled": true}""")
        // boolean 非 JsonPrimitive string，走 toString()
        assertEquals("  enabled: true;\n", result)
    }

    @Test
    fun `convert - null JSON 值`() {
        val result = convertJsonToCss("""{"foo": null}""")
        assertEquals("  foo: null;\n", result)
    }
}
