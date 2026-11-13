package com.pan.dashstyle

import com.pan.dashstyle.inspection.UnusedCssModuleClassInspection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * LESS mixin 调用 / :extend 引用计入「已使用」的置灰判定测试：
 * `.flex-center` 仅被 `.timeout-setting { .flex-center(); }` 调用（无 JS/Vue 引用）时，
 * 不应被误置灰（用户上报场景）。
 */
@RunWith(JUnit4::class)
@Suppress("UnstableApiUsage")
class LessMixinUnusedDetectionTest : BasePlatformTestCase() {

    private var errorProcessorToken: com.intellij.openapi.application.AccessToken? = null

    override fun setUp() {
        super.setUp()
        // VueLsp 沙箱噪音过滤，见 [VueSandboxNoiseFilter]
        errorProcessorToken = VueSandboxNoiseFilter.install()
    }

    override fun tearDown() {
        try {
            errorProcessorToken?.finish()
        } finally {
            super.tearDown()
        }
    }

    /**
     * 搭建要点：必须造一个真实引用源（import 该 module 的 tsx），
     * 否则 snapshot 的保守策略会把 references 为空标为 hasDynamic 提前返回（used 恒空），
     * 永远走不到 computeFileSnapshot 的内部扫描（见 DashStyleIntegrationTest 同款注释）。
     */
    private fun configureLess(
        text: String,
        jsUsage: String = "export default 1"
    ): com.intellij.psi.PsiFile {
        val less = myFixture.addFileToProject("Flex.module.less", text)
        myFixture.addFileToProject(
            "FlexRef.tsx",
            "import styles from './Flex.module.less'\n$jsUsage\n"
        )
        myFixture.openFileInEditor(less.virtualFile!!)
        return myFixture.file
    }

    private fun snapshotUsed(less: com.intellij.psi.PsiFile): Set<String> =
        UnusedCssModuleClassInspection().snapshotFor(less).used

    /** 端到端：inspection 对 unused class 报的问题描述里包含类名 */
    private fun unusedProblemClassNames(): List<String> {
        myFixture.enableInspections(UnusedCssModuleClassInspection())
        var descs: List<String> = emptyList()
        ApplicationManager.getApplication().invokeAndWait {
            descs = myFixture.doHighlighting().mapNotNull { it.description }
        }
        return descs.filter { it.contains("is not used anywhere") || it.contains("未在任何地方使用") }
            .mapNotNull { d -> Regex("""\.([_a-zA-Z][\w-]*)""").findAll(d).lastOrNull()?.groupValues?.get(1) }
    }

    @Test
    fun `mixin 括号调用计入使用 - 用户原始场景`() {
        val less = configureLess(
            """
            .flex-center {
              display: flex;
              align-items: center;
            }
            .timeout-setting {
              .flex-center();
            }
            .fully-unused {
              color: red;
            }
            """.trimIndent()
        )
        // 沙箱应能解析出 3 个 ruleset（防止 LESS 解析失效导致测试空转）
        ApplicationManager.getApplication().runReadAction {
            Assert.assertEquals(
                3, PsiTreeUtil.findChildrenOfType(less, CssRuleset::class.java).size
            )
        }

        val used = ApplicationManager.getApplication().runReadAction<Set<String>> { snapshotUsed(less) }
        Assert.assertTrue(
            "flex-center 应由 mixin 调用 .flex-center() 计入 used（实际 used=${used.sorted()}）",
            "flex-center" in used
        )
        Assert.assertFalse(
            "真正未使用的 fully-unused 不应进 used（证明管线处于激活态，测试不空转）",
            "fully-unused" in used
        )

        val flagged = unusedProblemClassNames()
        Assert.assertFalse("flex-center 不应被报未使用（实际 flagged=$flagged）", "flex-center" in flagged)
        Assert.assertTrue("fully-unused 应仍被报未使用（实际 flagged=$flagged）", "fully-unused" in flagged)
    }

    @Test
    fun `不带括号的 mixin 调用同样计入`() {
        val less = configureLess(
            """
            .m1 {
              color: red;
            }
            .a {
              .m1;
            }
            .fully-unused {
              color: blue;
            }
            """.trimIndent()
        )
        val used = ApplicationManager.getApplication().runReadAction<Set<String>> { snapshotUsed(less) }
        Assert.assertTrue(
            "无括号形式 .m1; 也应计入 used（实际 used=${used.sorted()}）",
            "m1" in used
        )
        Assert.assertFalse("fully-unused 不应进 used", "fully-unused" in used)

        val flagged = unusedProblemClassNames()
        Assert.assertFalse("m1 不应被报未使用（实际 flagged=$flagged）", "m1" in flagged)
        Assert.assertTrue("fully-unused 应仍被报未使用（实际 flagged=$flagged）", "fully-unused" in flagged)
    }

    @Test
    fun `LESS extend 形式计入使用`() {
        val less = configureLess(
            """
            .base {
              color: red;
            }
            .a:extend(.base) {
              font-weight: bold;
            }
            .fully-unused {
              color: blue;
            }
            """.trimIndent()
        )
        val used = ApplicationManager.getApplication().runReadAction<Set<String>> { snapshotUsed(less) }
        Assert.assertTrue(
            ":extend(.base) 引用的 base 应计入 used（实际 used=${used.sorted()}）",
            "base" in used
        )
        val flagged = unusedProblemClassNames()
        Assert.assertFalse("base 不应被报未使用（实际 flagged=$flagged）", "base" in flagged)
    }

    @Test
    fun `mixin 调用未定义名不误计使用`() {
        // gate：调用点名字必须与文件内定义的类求交集。
        // real 由 JS 引用进 used（证明快照管线激活），ghost 仅以 mixin 调用出现但未定义 → 不进 used。
        val less = configureLess(
            """
            .real {
              color: red;
            }
            .a {
              .ghost();
            }
            """.trimIndent(),
            jsUsage = "const a = styles.real"
        )
        val used = ApplicationManager.getApplication().runReadAction<Set<String>> { snapshotUsed(less) }
        Assert.assertTrue("JS 引用的 real 应进 used（证明管线激活，测试不空转）", "real" in used)
        Assert.assertFalse(
            "未定义的 ghost 不应进 used（实际 used=${used.sorted()}）",
            "ghost" in used
        )
    }

    // ======================== 新增强用例 ========================

    @Test
    fun `跨父级 mixin 调用（嵌套 ruleset 调用顶层 mixin）`() {
        val less = configureLess(
            """
            .flex-center {
              display: flex;
              align-items: center;
            }
            .wrapper {
              .inner {
                .flex-center();
              }
            }
            .fully-unused {
              color: red;
            }
            """.trimIndent()
        )
        val used = ApplicationManager.getApplication().runReadAction<Set<String>> { snapshotUsed(less) }
        Assert.assertTrue(
            "嵌套的 .inner 里调用的 .flex-center 应计入 used（跨父级调用）",
            "flex-center" in used
        )
        Assert.assertFalse("fully-unused 不应进 used", "fully-unused" in used)

        val flagged = unusedProblemClassNames()
        Assert.assertFalse("flex-center 不应被报未使用（嵌套调用场景）", "flex-center" in flagged)
        Assert.assertTrue("fully-unused 应被报未使用", "fully-unused" in flagged)
    }

    @Test
    fun `参数化 mixin 调用（带括号和参数）同样计入`() {
        val less = configureLess(
            """
            .flex-center {
              display: flex;
              align-items: center;
            }
            .a {
              .flex-center();
            }
            .b {
              .flex-center;
            }
            .c {
              .flex-center(red);
            }
            .d {
              .flex-center(#ff0, 8px);
            }
            .fully-unused {
              color: red;
            }
            """.trimIndent()
        )
        val used = ApplicationManager.getApplication().runReadAction<Set<String>> { snapshotUsed(less) }
        Assert.assertTrue(
            "参数化调用 .flex-center(red) 应计入 used（实际 used=${used.sorted()}）",
            "flex-center" in used
        )
        Assert.assertFalse("fully-unused 不应进 used", "fully-unused" in used)

        val flagged = unusedProblemClassNames()
        Assert.assertFalse("flex-center 不应被报未使用（多种调用形式）", "flex-center" in flagged)
    }

    @Test
    fun `LESS extend all 形式（带 all 关键字）计入使用`() {
        val less = configureLess(
            """
            .base {
              color: red;
            }
            .a:extend(.base all) {
              font-weight: bold;
            }
            .b:extend(.base) {
              font-size: 14px;
            }
            .fully-unused {
              color: blue;
            }
            """.trimIndent()
        )
        val used = ApplicationManager.getApplication().runReadAction<Set<String>> { snapshotUsed(less) }
        Assert.assertTrue(
            ":extend(.base all) 的 base 应计入 used（实际 used=${used.sorted()}）",
            "base" in used
        )
        Assert.assertFalse("fully-unused 不应进 used", "fully-unused" in used)

        val flagged = unusedProblemClassNames()
        Assert.assertFalse("base 不应被报未使用（extend all + extend 形式）", "base" in flagged)
    }
}
