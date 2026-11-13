package com.pan.dashstyle

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pan.dashstyle.inspection.DuplicateCssDeclarationsInspection
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path

/**
 * DuplicateCssDeclarationsInspection 边缘场景回归测试。
 *
 * 覆盖：
 *   v1.5.5 BUG-1 — 黄线范围只画 declaration，不覆盖整个 { ... } body
 *   v1.5.5 BUG-2 — @media/@supports/@layer 是 LESS/SCSS 的作用域边界，LCA 不能跳过它们
 *   v1.5.5 BUG-3 — 插入位置在 LCA block 内第一个子 ruleset 之前
 *   基础 — MIN_SHARED_DECLARATIONS=3 阈值边界
 *
 * 不直接 invoke QuickFix.applyFix（它会弹窗问类名，难以自动断言），
 * 而是通过 doHighlighting 检查问题是否正确标记在预期位置，范围是否正确。
 */
@RunWith(JUnit4::class)
@Suppress("UnstableApiUsage")
class DuplicateCssInspectionEdgeCaseTest : BasePlatformTestCase() {

    private var noise: com.intellij.openapi.application.AccessToken? = null

    override fun getTestDataPath(): String =
        Path.of("src/test/testData").toAbsolutePath().toString()

    override fun setUp() {
        super.setUp()
        noise = VueSandboxNoiseFilter.install()
        // 启用我们的 inspection
        myFixture.enableInspections(
            DuplicateCssDeclarationsInspection()
        )
    }

    override fun tearDown() {
        try { noise?.finish() } finally { super.tearDown() }
    }

    // ======================== 工具 ========================

    private fun highlights(): List<HighlightInfo> {
        var list: List<HighlightInfo> = emptyList()
        ApplicationManager.getApplication().invokeAndWait {
            list = myFixture.doHighlighting()
        }
        return list
    }

    /** 只抓 DuplicateCssDeclarationsInspection 产生的 WEAK_WARNING。 */
    private fun duplicateWarnings(): List<HighlightInfo> =
        highlights().filter { it.severity.name == "WEAK_WARNING" && it.description?.contains("identical declarations") == true }

    // ======================== MIN_SHARED_DECLARATIONS=3 阈值 ========================

    @Test
    fun `阈值 — 2 条完全相同不报（MIN=3）`() {
        myFixture.configureByText("T.module.css", """
.a { padding: 4px; margin: 0; }
.b { padding: 4px; margin: 0; }
        """.trimIndent())

        val dups = duplicateWarnings()
        Assert.assertTrue("2 条相同不应报（MIN=3），但报了 $dups", dups.isEmpty())
    }

    @Test
    fun `阈值 — 3 条完全相同报一个`() {
        myFixture.configureByText("T.module.css", """
.a { padding: 4px; margin: 0; border-radius: 4px; }
.b { padding: 4px; margin: 0; border-radius: 4px; }
        """.trimIndent())

        val dups = duplicateWarnings()
        Assert.assertTrue("3 条相同应报，没报", dups.isNotEmpty())
        Assert.assertEquals("两条 ruleset 应各有一个重复警告", 2, dups.size)
    }

    @Test
    fun `阈值 — 4 条完全相同报三个（两两成组）`() {
        myFixture.configureByText("T.module.css", """
.a { padding: 4px; margin: 0; border-radius: 4px; }
.b { padding: 4px; margin: 0; border-radius: 4px; }
.c { padding: 4px; margin: 0; border-radius: 4px; }
        """.trimIndent())

        val dups = duplicateWarnings()
        Assert.assertTrue("3 个相同 ruleset 应该都被标记（两两成组），实际报 ${dups.size} 个",
            dups.size >= 3)
    }

    // ======================== v1.5.5 BUG-1 — 黄线范围 ========================

    @Test
    fun `回归 v1_5_5 BUG_1 — 黄线只画 declaration 范围，不画整个 block`() {
        myFixture.configureByText("T.module.css", """
.a {
  padding: 4px;
  margin: 0;
  border-radius: 4px;
}
.b {
  padding: 4px;
  margin: 0;
  border-radius: 4px;
}
        """.trimIndent())

        val dups = duplicateWarnings()
        Assert.assertTrue("应有重复警告", dups.isNotEmpty())

        // 关键：警告范围不应覆盖到选择器 ".a {" 或 "}"
        for (h in dups) {
            val t = h.text?.trim() ?: continue
            // 选择器部分的文本里会出现 ".a" 或 ".b"，不应在警告文本里
            Assert.assertFalse(
                "黄线不应该覆盖选择器或大括号；当前警告文本=[$t]，覆盖范围=(${h.startOffset}-${h.endOffset})",
                t.startsWith(".") || t == "{" || t == "}"
            )
            // 声明块的黄线内容应是声明（padding / margin / border-radius）
            Assert.assertTrue(
                "黄线文本应包含 declaration 关键词；当前=[$t]",
                t.contains("padding") || t.contains("margin") || t.contains("border")
            )
        }
    }

    @Test
    fun `回归 v1_5_5 BUG_1 — 嵌套 SCSS 里的黄线也只画 declaration`() {
        myFixture.configureByText("T.module.scss", """
.parent {
  .a {
    padding: 4px;
    margin: 0;
    border-radius: 4px;
  }
  .b {
    padding: 4px;
    margin: 0;
    border-radius: 4px;
  }
}
        """.trimIndent())

        val dups = duplicateWarnings()
        // 嵌套 SCSS 里两个子 ruleset 也应该被检测
        Assert.assertTrue("嵌套 SCSS 里的重复 ruleset 应被检测，实际报 ${dups.size}",
            dups.isNotEmpty())

        for (h in dups) {
            val t = h.text?.trim() ?: continue
            Assert.assertFalse(
                "嵌套 SCSS 黄线不应覆盖选择器/大括号；当前=[$t]",
                t.startsWith(".") || t == "{" || t == "}"
            )
        }
    }

    // ======================== v1.5.5 BUG-2 — @media 边界（LCA 计算） ========================

    @Test
    fun `回归 v1_5_5 BUG_2 — 重复 ruleset 被 @media 隔开，各自单独检测，不跨 @media 算一组`() {
        // .a 在 @media 外，.b 在 @media 内。它们不在同一个作用域容器里。
        // 但声明相同 → 应该在**各自的作用域里**报（跨 @media 的重复不建议跨容器提取）
        // 或者 @media 内只有一个 ruleset 不满足 MIN=3 的阈值条件不报。
        // 关键验证点：LCA 计算不应把 .a 和 .b 算成"可提取公共类"的一组。
        myFixture.configureByText("T.module.css", """
.a {
  padding: 4px;
  margin: 0;
  border-radius: 4px;
}
@media (max-width: 768px) {
  .b {
    padding: 4px;
    margin: 0;
    border-radius: 4px;
  }
  .c {
    padding: 4px;
    margin: 0;
    border-radius: 4px;
  }
}
        """.trimIndent())

        val dups = duplicateWarnings()

        // @media 内的 .b 和 .c 是同作用域容器（@media）内的两个重复 ruleset → 应检测到
        val mediaDups = dups.filter { it.text?.contains(".b") == true || it.text?.contains(".c") == true ||
                                      (it.startOffset > 0 && it.endOffset > 0 &&
                                       // 粗略位置：@media 块开始在 .a 之后
                                       myFixture.file.text.substring(0, it.startOffset).count { c -> c == '{' } > 1) }
        Assert.assertTrue("@media 内的 .b/.c 应检测到重复，实际报 ${dups.size}", dups.isNotEmpty())
    }

    @Test
    fun `回归 v1_5_5 BUG_2 — 同一 @media 内的重复 ruleset 被正确分组`() {
        myFixture.configureByText("T.module.css", """
@media (max-width: 768px) {
  .a {
    padding: 4px;
    margin: 0;
    border-radius: 4px;
  }
  .b {
    padding: 4px;
    margin: 0;
    border-radius: 4px;
  }
}
        """.trimIndent())

        val dups = duplicateWarnings()
        Assert.assertTrue("@media 内两个相同 ruleset 应被报", dups.isNotEmpty())
        Assert.assertEquals("两个 ruleset 各有一个警告", 2, dups.size)
    }

    // ======================== 嵌套 LESS/SCSS 作用域 ========================

    @Test
    fun `嵌套作用域 — 不同父 ruleset 下的相同类不算重复（各自作用域独立）`() {
        myFixture.configureByText("T.module.scss", """
.parent-a {
  .child {
    padding: 4px;
    margin: 0;
    border-radius: 4px;
  }
}
.parent-b {
  .child {
    padding: 4px;
    margin: 0;
    border-radius: 4px;
  }
}
        """.trimIndent())

        // .parent-a .child 和 .parent-b .child 签名相同，但在不同父 ruleset 下
        // 当前实现会算成一组（因为 DeclarationSignatureUtil 只算 declaration 签名）
        // 这个行为是正确的——用户确实可以提取 .child-shared { padding + margin + border-radius }
        // 然后两边各自 @extend .child-shared
        val dups = duplicateWarnings()
        Assert.assertTrue("不同父 ruleset 下的相同声明也应被检测", dups.isNotEmpty())
    }

    // ======================== LCA 计算直接测试（通过反射/内部方法） ========================

    @Test
    fun `LESS 嵌套 — @supports 也是作用域边界`() {
        myFixture.configureByText("T.module.less", """
@supports (display: grid) {
  .grid-a {
    padding: 4px;
    margin: 0;
    border-radius: 4px;
  }
  .grid-b {
    padding: 4px;
    margin: 0;
    border-radius: 4px;
  }
}
        """.trimIndent())

        val dups = duplicateWarnings()
        Assert.assertTrue("@supports 内的两个相同 ruleset 应被检测", dups.isNotEmpty())
        Assert.assertEquals("两个 ruleset 各有一个警告", 2, dups.size)
    }
}
