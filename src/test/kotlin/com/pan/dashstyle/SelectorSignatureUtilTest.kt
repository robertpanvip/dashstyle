package com.pan.dashstyle

import com.pan.dashstyle.support.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 纯 Kotlin 单元测试（无 IntelliJ Platform 环境）——
 * 按功能补充此前未覆盖的选择器类名提取 / 全局块剥离、声明签名归一化逻辑。
 */
class SelectorSignatureUtilTest {

    // ========================================================================
    // A. CssSelectorUtil.extractClassNames —— 从展开后的选择器提取类名
    // ========================================================================

    @Test
    fun `extractClassNames - 单个类`() {
        assertEquals(listOf("foo"), CssSelectorUtil.extractClassNames(".foo"))
    }

    @Test
    fun `extractClassNames - 后代选择器取全部类名`() {
        assertEquals(
            listOf("parent", "child"),
            CssSelectorUtil.extractClassNames(".parent .child")
        )
    }

    @Test
    fun `extractClassNames - 伪类伪元素不影响类名收集`() {
        assertEquals(
            listOf("btn", "primary"),
            CssSelectorUtil.extractClassNames(".btn.primary:hover")
        )
        assertEquals(
            listOf("card"),
            CssSelectorUtil.extractClassNames(".card::before")
        )
    }

    @Test
    fun `extractClassNames - 逗号分隔选择器收集全部`() {
        assertEquals(
            listOf("a", "b", "c"),
            CssSelectorUtil.extractClassNames(".a, .b, .c")
        )
    }

    @Test
    fun `extractClassNames - BEM 连字符与下划线类名完整保留`() {
        assertEquals(
            listOf("block__elem--modifier"),
            CssSelectorUtil.extractClassNames(".block__elem--modifier")
        )
    }

    @Test
    fun `extractClassNames - 属性选择器仅取类名`() {
        assertEquals(
            listOf("btn", "icon"),
            CssSelectorUtil.extractClassNames(".btn[aria-hidden=true] .icon")
        )
    }

    @Test
    fun `extractClassNames - 空选择器返回空`() {
        assertTrue(CssSelectorUtil.extractClassNames("").isEmpty())
        assertTrue(CssSelectorUtil.extractClassNames("div").isEmpty(), "元素选择器无类名")
    }

    @Test
    fun `extractClassNames - 全局块被剥离后再收集`() {
        // :global(.foo) 是全局作用域，不作为 CSS Module 局部类收集
        assertEquals(
            listOf("local"),
            CssSelectorUtil.extractClassNames(":global(.foo) .local")
        )
    }

    @Test
    fun `extractClassNames - 全局作用域段被剥离，逗号后类仍保留`() {
        // 无括号的 :global 段贪婪吃到逗号为止；逗号后的 .keep 是局部类，保留
        assertEquals(
            listOf("keep"),
            CssSelectorUtil.extractClassNames(":global .a, .keep")
        )
    }

    @Test
    fun `extractClassNames - 类选择器必须成单词边界`() {
        // .foo-bar 不应误拆成 .foo
        assertEquals(
            listOf("foo-bar"),
            CssSelectorUtil.extractClassNames(".foo-bar")
        )
    }

    // ========================================================================
    // B. CssSelectorUtil.stripGlobalBlocks —— 剥离 :global/:local 修饰
    // ========================================================================

    @Test
    fun `stripGlobalBlocks - 无 global 原样返回`() {
        val raw = ".a, .b:hover"
        assertEquals(raw, CssSelectorUtil.stripGlobalBlocks(raw))
    }

    @Test
    fun `stripGlobalBlocks - 括号式 global 块被替换为空`() {
        // 括号式 :global(.a) 块被替换成一个空格，原块后原有空格保留 → 双空格
        assertEquals(
            "  .b",
            CssSelectorUtil.stripGlobalBlocks(":global(.a) .b")
        )
    }

    @Test
    fun `stripGlobalBlocks - 无括号的 global 作用域段剥离到逗号为止`() {
        // 无括号的 :global 段（贪婪到逗号）被移除，逗号及之后保留
        assertEquals(
            ", .keep",
            CssSelectorUtil.stripGlobalBlocks(":global .a .b, .keep")
        )
    }

    @Test
    fun `stripGlobalBlocks - local 作用域保留（局部作用域）`() {
        val raw = ":local(.foo) .bar"
        assertEquals(raw, CssSelectorUtil.stripGlobalBlocks(raw))
    }

    // ========================================================================
    // C. DeclarationSignatureUtil.normalizeValue —— 单值归一化
    // ========================================================================

    @Test
    fun `normalizeValue - hex3 展开为 hex6`() {
        assertEquals("#ff0000", DeclarationSignatureUtil.normalizeValue("#f00"))
        assertEquals("#aabbcc", DeclarationSignatureUtil.normalizeValue("#abc"))
    }

    @Test
    fun `normalizeValue - hex3 不误伤 hex6`() {
        assertEquals("#ff0000", DeclarationSignatureUtil.normalizeValue("#ff0000"))
        // 4 位 hex 不是目标（仅 3 位展开），但 6 位保持原样
        assertEquals("#ffffff", DeclarationSignatureUtil.normalizeValue("#ffffff"))
    }

    @Test
    fun `normalizeValue - 空白压缩为单个空格`() {
        // 任意连续空白压缩为一个空格
        assertEquals("1px solid red", DeclarationSignatureUtil.normalizeValue("  1px   solid  red"))
    }

    @Test
    fun `normalizeValue - 去尾随逗号`() {
        assertEquals("rgba(0,0,0,0.5)", DeclarationSignatureUtil.normalizeValue("rgba(0,0,0,0.5),"))
    }

    @Test
    fun `normalizeValue - 转小写`() {
        assertEquals("red", DeclarationSignatureUtil.normalizeValue("RED"))
        assertEquals("#ff0000", DeclarationSignatureUtil.normalizeValue("#FF0000"))
    }

    // ========================================================================
    // D. DeclarationSignatureUtil.normalizeDeclaration —— 声明 prop:value 归一化
    // ========================================================================

    @Test
    fun `normalizeDeclaration - trim 属性名并小写`() {
        assertEquals("color:red", DeclarationSignatureUtil.normalizeDeclaration("  Color ", "red"))
    }

    @Test
    fun `normalizeDeclaration - 值做 hex 与大小写归一`() {
        assertEquals("border-color:#ff0000", DeclarationSignatureUtil.normalizeDeclaration("border-color", "#F00"))
    }

    @Test
    fun `normalizeDeclaration - 属性与值间固定冒号`() {
        assertEquals("margin:0", DeclarationSignatureUtil.normalizeDeclaration("margin", " 0 "))
    }
}