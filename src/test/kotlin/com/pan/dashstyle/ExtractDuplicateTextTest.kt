package com.pan.dashstyle

import com.pan.dashstyle.action.ExtractDuplicateDeclarationsAsMixinIntention
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * ExtractDuplicateDeclarationsAsMixinIntention 纯文本核心测试（沙箱提供 PsiFile 参数）：
 * 提取阈值（恰好 2 条共享不提取）、selection 限定、@media 跳过、嵌套声明不并入、
 * normalizeDeclsWithPretty 的注释剥离与顺序保持。
 */
@RunWith(JUnit4::class)
@Suppress("UnstableApiUsage")
class ExtractDuplicateTextTest : BasePlatformTestCase() {

    private val THREE = "padding: 10px; color: red; margin: 0;"

    private fun runExtract(source: String, selection: Pair<Int, Int>? = null): String {
        val file = myFixture.addFileToProject("t.less", source)
        return ExtractDuplicateDeclarationsAsMixinIntention()
            .extractDuplicateInText(source, file, file, selection)
    }

    // ===================== 基础提取 ======================

    @Test
    fun `两条相同的三声明规则被提取为共享 mixin`() {
        val source = ".a {\n  $THREE\n}\n.b {\n  $THREE\n}\n"
        val result = runExtract(source)
        // 两处调用（定义处名字后是 " {"，不会被 "; " 计入）
        val occurrences = result.split(".shared-padding-color-margin;").size - 1
        Assert.assertEquals(2, occurrences)
        Assert.assertTrue(
            "文件尾应追加 mixin 定义",
            result.contains(".shared-padding-color-margin {\n    padding: 10px;\n    color: red;\n    margin: 0;\n}")
        )
    }

    @Test
    fun `恰好两条共享声明不提取（阈值为 3）`() {
        val source = ".a { padding: 10px; color: red; }\n.b { padding: 10px; color: red; }\n"
        Assert.assertEquals(source, runExtract(source))
    }

    @Test
    fun `单条规则即使声明再多也不提取`() {
        val source = ".a { $THREE }\n"
        Assert.assertEquals(source, runExtract(source))
    }

    // ===================== isAvailable 阈值边界 ======================

    @Test
    fun `isAvailable 三声明两规则时为 true`() {
        myFixture.configureByText("t.less", ".a { $THREE }\n.b { $THREE }\n")
        val file = myFixture.file
        Assert.assertTrue(
            ExtractDuplicateDeclarationsAsMixinIntention()
                .isAvailable(project, myFixture.editor, file)
        )
    }

    @Test
    fun `isAvailable 恰好两条共享声明时为 false`() {
        myFixture.configureByText("t.less", ".a { padding: 10px; color: red; }\n.b { padding: 10px; color: red; }\n")
        val file = myFixture.file
        Assert.assertFalse(
            ExtractDuplicateDeclarationsAsMixinIntention()
                .isAvailable(project, myFixture.editor, file)
        )
    }

    // ===================== selection 限定 ======================

    @Test
    fun `选区只覆盖后两条规则时首条不参与提取`() {
        val source = ".a { $THREE }\n.b { $THREE }\n.c { $THREE }\n"
        val selStart = source.indexOf(".b")
        val result = runExtract(source, selection = selStart to source.length)
        Assert.assertFalse("选区外的 .a 不应被替换", result.substring(0, selStart).contains(".shared-"))
        // .b / .c 两处调用
        Assert.assertEquals(2, result.split(".shared-padding-color-margin;").size - 1)
    }

    // ===================== @media 与嵌套 ======================

    @Test
    fun `media 内的规则不参与提取`() {
        val source =
            "@media (max-width: 600px) {\n  .a { $THREE }\n}\n.b { $THREE }\n"
        Assert.assertEquals("media 内 skip 后 .b 只剩单条，应原样返回", source, runExtract(source))
    }

    @Test
    fun `嵌套 ruleset 的声明不并入签名`() {
        val source =
            ".a { .inner { $THREE } }\n.b { .inner2 { $THREE } }\n"
        Assert.assertEquals("嵌套声明不构成外层签名，应原样返回", source, runExtract(source))
    }

    @Test
    fun `注释中的花括号不干扰规则解析`() {
        val source =
            "/* } 假括号 { */\n.a { $THREE /* { } */ }\n.b { $THREE }\n"
        val result = runExtract(source)
        Assert.assertEquals(2, result.split(".shared-padding-color-margin;").size - 1)
    }

    // ===================== normalizeDeclsWithPretty ======================

    @Test
    fun `normalizeDeclsWithPretty 剥离注释且签名排序`() {
        val (sign, pretty) = ExtractDuplicateDeclarationsAsMixinIntention()
            .normalizeDeclsWithPretty(" padding: 10px; /* note */ color: #F00; ")
        Assert.assertEquals(listOf("color:#ff0000", "padding:10px"), sign)
        Assert.assertEquals(listOf("padding: 10px", "color: #ff0000"), pretty)
    }

    @Test
    fun `normalizeDeclsWithPretty 空与纯注释体返回空签名`() {
        val intention = ExtractDuplicateDeclarationsAsMixinIntention()
        Assert.assertTrue(intention.normalizeDeclsWithPretty("").first.isEmpty())
        Assert.assertTrue(intention.normalizeDeclsWithPretty("/* only comment */").first.isEmpty())
    }
}
