package com.pan.dashstyle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pan.dashstyle.support.DeclarationSignatureUtil
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path

/**
 * DeclarationSignatureUtil PSI 集成测试：构造真实 CssRuleset，验证 computeSignature 的行为。
 *
 * 与 DeclarationSignatureUtilTest（纯函数单元测试）互补：
 *   - 单元测试覆盖 normalizeValue 的值边界（hex/空白/逗号/小写）
 *   - PSI 测试覆盖 computeSignature 的 PSI 遍历边界（嵌套 ruleset 不被算、声明顺序、空 block）
 *
 * 运行：
 *   gradle test --tests "com.pan.dashstyle.DeclarationSignatureUtilPsiTest"
 */
@RunWith(JUnit4::class)
@Suppress("UnstableApiUsage")
class DeclarationSignatureUtilPsiTest : BasePlatformTestCase() {

    private var noise: com.intellij.openapi.application.AccessToken? = null

    override fun getTestDataPath(): String =
        Path.of("src/test/testData").toAbsolutePath().toString()

    override fun setUp() {
        super.setUp()
        noise = VueSandboxNoiseFilter.install()
    }

    override fun tearDown() {
        try { noise?.finish() } finally { super.tearDown() }
    }

    /** 把 CSS 文本配进沙箱，返回第一个 CssRuleset（顶层）。 */
    private fun firstRuleset(text: String): CssRuleset {
        val file = myFixture.configureByText("T.module.css", text)
        return PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java).first()
    }

    private fun computeSignature(ruleset: CssRuleset): String? =
        DeclarationSignatureUtil.computeSignature(ruleset)

    // ==================== 基础签名行为 ====================

    @Test
    fun `基础 — 两条声明按排序拼签名`() {
        val rs = firstRuleset("""
            .a {
              padding: 4px;
              margin: 0;
            }
        """.trimIndent())

        val sig = computeSignature(rs)
        Assert.assertNotNull("签名不应为 null", sig)
        Assert.assertEquals("margin:0|padding:4px", sig)  // 按 prop 排序 margin 在 padding 前
    }

    @Test
    fun `基础 — 声明顺序不同签名相同`() {
        val file = myFixture.configureByText("T.module.css", """
            .a { padding: 4px; margin: 0; }
            .b { margin: 0; padding: 4px; }
        """.trimIndent())
        val rulesets = PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)

        val sigA = computeSignature(rulesets[0])
        val sigB = computeSignature(rulesets[1])

        Assert.assertEquals("声明顺序不同但内容相同，签名应相同", sigA, sigB)
    }

    @Test
    fun `基础 — 值不同签名不同`() {
        val file = myFixture.configureByText("T.module.css", """
            .a { padding: 4px; }
            .b { padding: 8px; }
        """.trimIndent())
        val rulesets = PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)

        val sigA = computeSignature(rulesets[0])
        val sigB = computeSignature(rulesets[1])

        Assert.assertNotEquals("值不同签名应不同", sigA, sigB)
    }

    // ==================== 归一化行为（端到端） ====================

    @Test
    fun `端到端 — hex3 归一化使不同写法签名相同`() {
        val file = myFixture.configureByText("T.module.css", """
            .a { color: #f00; }
            .b { color: #FF0000; }
        """.trimIndent())
        val rulesets = PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)

        Assert.assertEquals("hex3 #f00 应归一为 #ff0000，签名相同",
            computeSignature(rulesets[0]), computeSignature(rulesets[1]))
    }

    @Test
    fun `端到端 — 空白归一化使不同写法签名相同`() {
        val file = myFixture.configureByText("T.module.css", """
            .a { margin:  4px   8px; }
            .b { margin: 4px 8px; }
        """.trimIndent())
        val rulesets = PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)

        Assert.assertEquals("内部多空白压缩后签名相同",
            computeSignature(rulesets[0]), computeSignature(rulesets[1]))
    }

    @Test
    fun `端到端 — prop 大小写归一`() {
        val file = myFixture.configureByText("T.module.css", """
            .a { PADDING: 4px; }
            .b { padding: 4px; }
        """.trimIndent())
        val rulesets = PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)

        Assert.assertEquals("prop 名 PADDING 应归一为 padding",
            computeSignature(rulesets[0]), computeSignature(rulesets[1]))
    }

    // ==================== 空 block / 边界 ====================

    @Test
    fun `空 block 返回 null`() {
        val rs = firstRuleset(".empty { }")
        Assert.assertNull("空 block 签名应为 null", computeSignature(rs))
    }

    @Test
    fun `带 CSS 变量的声明正常归一`() {
        val rs = firstRuleset("""
            .a {
              color: var(--my-color);
              padding: 4px;
            }
        """.trimIndent())

        val sig = computeSignature(rs)
        Assert.assertNotNull(sig)
        // var(--my-color) 里的 prop/name 保持原样（仅 lowercase），应排在 color 后面、padding 前面
        Assert.assertEquals("color:var(--my-color)|padding:4px", sig)
    }

    // ==================== 嵌套 ruleset 不被算入父签名 ====================
    // DuplicateCssDeclarationsInspection.computeSignature 用 PsiTreeUtil.findChildrenOfType
    // 会递归到嵌套的 CssDeclaration——需要确认这是否是期望行为。

    @Test
    fun `嵌套 SCSS — 父 ruleset 签名只含自身直接声明（不含子 ruleset 的声明）`() {
        val file = myFixture.configureByText("T.module.scss", """
            .parent {
              padding: 4px;
              .child {
                margin: 0;
                color: red;
              }
            }
        """.trimIndent())

        val rulesets = PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)
        // SCSS 嵌套解析后会有 2 个 CssRuleset：.parent 和 .parent .child
        Assert.assertTrue("应至少有 2 个 ruleset（父 + 子），实际 ${rulesets.size}", rulesets.size >= 2)

        val parentSig = computeSignature(rulesets[0])  // .parent
        val childSig = computeSignature(rulesets[1])   // .parent .child

        // 父签名应该只有 padding:4px，不应含子的 margin/color
        Assert.assertEquals("父 ruleset 签名应只含自身直接声明",
            "padding:4px", parentSig)
        Assert.assertEquals("子 ruleset 签名应含自身两条声明（排序后）",
            "color:red|margin:0", childSig)
    }

    // ==================== computeSignatureList ====================

    @Test
    fun `computeSignatureList 返回排序后的声明列表`() {
        val rs = firstRuleset("""
            .a {
              z-index: 10;
              padding: 4px;
              margin: 0;
            }
        """.trimIndent())

        val list = DeclarationSignatureUtil.computeSignatureList(rs)
        Assert.assertEquals(3, list.size)
        Assert.assertEquals(listOf("margin:0", "padding:4px", "z-index:10"), list)
    }
}
