package com.pan.dashstyle

import com.pan.dashstyle.support.DeclarationSignatureUtil
import org.junit.Assert
import org.junit.jupiter.api.Test

/**
 * DeclarationSignatureUtil 纯函数单元测试（不依赖 PSI，秒级）。
 *
 * normalizeValue 是核心：hex3→hex6、空白压缩、去尾逗号、lowercase。
 * 这些边界回归一旦被打破，整个「重复 CSS 检测」就会误报/漏报。
 *
 * 运行：
 *   gradle test --tests "com.pan.dashstyle.DeclarationSignatureUtilTest"
 */
class DeclarationSignatureUtilTest {

    // ==================== hex3 → hex6 ====================

    @Test
    fun `hex3 小写转 hex6`() {
        Assert.assertEquals("#ff0000", DeclarationSignatureUtil.normalizeValue("#f00"))
    }

    @Test
    fun `hex3 大写转 hex6 后统一小写`() {
        Assert.assertEquals("#aabbcc", DeclarationSignatureUtil.normalizeValue("#ABC"))
    }

    @Test
    fun `hex3 混合大小写`() {
        Assert.assertEquals("#1a2b3c", DeclarationSignatureUtil.normalizeValue("#1A2B3C"))
    }

    @Test
    fun `hex3 边界色 #fff 转 #ffffff`() {
        Assert.assertEquals("#ffffff", DeclarationSignatureUtil.normalizeValue("#fff"))
    }

    @Test
    fun `hex6 原值不变（仅 lowercase）`() {
        Assert.assertEquals("#ff0000", DeclarationSignatureUtil.normalizeValue("#FF0000"))
    }

    @Test
    fun `非 hex 格式的 3 字符字符串不碰`() {
        // #abc 是 hex3，但 abcd（4 字符 hex6）里的 abc 不应被二次替换
        Assert.assertEquals("#abcdef", DeclarationSignatureUtil.normalizeValue("#ABCDEF"))
    }

    @Test
    fun `hex3 在 rgb 函数参数里不被误匹配`() {
        // rgb() 里的数字不是 hex 颜色
        Assert.assertEquals("rgb(255, 0, 0)",
            DeclarationSignatureUtil.normalizeValue("rgb(255, 0, 0)"))
    }

    @Test
    fun `多个 hex3 在同一值里都被替换`() {
        Assert.assertEquals("linear-gradient(#ff0000, #00ff00)",
            DeclarationSignatureUtil.normalizeValue("linear-gradient(#f00, #0f0)"))
    }

    // ==================== 空白压缩 ====================

    @Test
    fun `首尾空白 trim`() {
        Assert.assertEquals("12px", DeclarationSignatureUtil.normalizeValue("  12px  "))
    }

    @Test
    fun `内部多空白压缩为单空格`() {
        Assert.assertEquals("12px 8px", DeclarationSignatureUtil.normalizeValue("12px   8px"))
    }

    @Test
    fun `calc 表达式里的空白压缩`() {
        Assert.assertEquals("calc(100% - 20px)",
            DeclarationSignatureUtil.normalizeValue("calc( 100%  -  20px )"))
    }

    @Test
    fun `逗号分隔多值里的空白压缩`() {
        Assert.assertEquals("rgba(255, 0, 0, 0.5)",
            DeclarationSignatureUtil.normalizeValue("rgba(255 , 0 , 0 , 0.5)"))
    }

    // ==================== 尾随逗号移除 ====================

    @Test
    fun `尾随逗号被移除`() {
        Assert.assertEquals("12px", DeclarationSignatureUtil.normalizeValue("12px,"))
    }

    @Test
    fun `内部逗号不被移除`() {
        Assert.assertEquals("12px, 8px", DeclarationSignatureUtil.normalizeValue("12px, 8px"))
    }

    // ==================== lowercase ====================

    @Test
    fun `全大写颜色名转小写`() {
        Assert.assertEquals("red", DeclarationSignatureUtil.normalizeValue("RED"))
    }

    @Test
    fun `大写 CSS 关键字转小写`() {
        Assert.assertEquals("flex", DeclarationSignatureUtil.normalizeValue("FLEX"))
    }

    @Test
    fun `URL 里的路径保持原样（仅 lowercase）`() {
        Assert.assertEquals("url(image.png)", DeclarationSignatureUtil.normalizeValue("URL(IMAGE.PNG)"))
    }

    // ==================== CSS 变量 ====================

    @Test
    fun `CSS 变量保持原样（仅 lowercase + 空白压缩）`() {
        Assert.assertEquals("var(--my-color)",
            DeclarationSignatureUtil.normalizeValue("VAR( --MY-COLOR )"))
    }

    // ==================== normalizeDeclaration ====================

    @Test
    fun `prop + value 归一化`() {
        Assert.assertEquals("padding:12px",
            DeclarationSignatureUtil.normalizeDeclaration(" PADDING ", " 12PX "))
    }

    @Test
    fun `border 简写归一化`() {
        Assert.assertEquals("border:1px solid #ff0000",
            DeclarationSignatureUtil.normalizeDeclaration("BORDER", "1px SOLID #f00"))
    }

    @Test
    fun `带 !important 的声明归一化`() {
        // normalizeValue 不去掉 !important（它是值的一部分）
        Assert.assertEquals("color:red !important",
            DeclarationSignatureUtil.normalizeDeclaration("color", "RED !IMPORTANT"))
    }

    // ==================== 特殊边界 ====================

    @Test
    fun `空字符串归一化仍为空`() {
        Assert.assertEquals("", DeclarationSignatureUtil.normalizeValue(""))
    }

    @Test
    fun `纯空白归一化为空`() {
        Assert.assertEquals("", DeclarationSignatureUtil.normalizeValue("   "))
    }

    @Test
    fun `0 值保持原样`() {
        Assert.assertEquals("0", DeclarationSignatureUtil.normalizeValue("0"))
    }

    @Test
    fun `inherit initial unset 关键字原样小写`() {
        Assert.assertEquals("inherit", DeclarationSignatureUtil.normalizeValue("INHERIT"))
        Assert.assertEquals("initial", DeclarationSignatureUtil.normalizeValue("Initial"))
        Assert.assertEquals("unset", DeclarationSignatureUtil.normalizeValue("Unset"))
    }

    @Test
    fun `emoji 或特殊字符不被损坏`() {
        // 理论上 CSS 值里不会有 emoji，但测试边界情况
        Assert.assertEquals("content:🚀",
            DeclarationSignatureUtil.normalizeDeclaration("content", "🚀"))
    }

    // ==================== 等价签名（说明 computeSignature 排序后才等价） ====================

    @Test
    fun `两条声明 prop value 相同但顺序不同 — 需要排序才等价`() {
        val a = DeclarationSignatureUtil.normalizeDeclaration("padding", "4px") + "|" +
                DeclarationSignatureUtil.normalizeDeclaration("margin", "0")
        val b = DeclarationSignatureUtil.normalizeDeclaration("margin", "0") + "|" +
                DeclarationSignatureUtil.normalizeDeclaration("padding", "4px")

        // 直接拼接顺序不同 → 不等
        Assert.assertNotEquals(a, b)

        // 排序后才等价（computeSignature 内部会做排序）
        val sortedA = a.split("|").sorted().joinToString("|")
        val sortedB = b.split("|").sorted().joinToString("|")
        Assert.assertEquals(sortedA, sortedB)
    }
}
