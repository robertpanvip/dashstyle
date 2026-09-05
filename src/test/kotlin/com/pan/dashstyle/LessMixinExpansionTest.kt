package com.pan.dashstyle

import com.pan.dashstyle.annotator.DashStyleDocumentationProvider
import com.pan.dashstyle.support.CssSelectorUtil
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * LESS/SCSS mixin 调用展开（悬停预览）测试：
 * `.app-root { .shared-color(); }` 悬停应显示 mixin 定义体里的 `color: red`，
 * 而不是 empty 占位。
 */
@RunWith(JUnit4::class)
@Suppress("UnstableApiUsage")
class LessMixinExpansionTest : BasePlatformTestCase() {

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

    private fun rulesetNamed(file: com.intellij.psi.PsiFile, selector: String): CssRuleset =
        PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)
            .firstOrNull { it.selectorList?.text?.trim() == selector }
            ?: throw AssertionError("未找到 selector 为 $selector 的 ruleset")

    private fun declTexts(file: com.intellij.psi.PsiFile, selector: String): List<String> =
        CssSelectorUtil.collectEffectiveDeclarations(rulesetNamed(file, selector))
            .map { "${it.propertyName}:${it.value?.text}" }

    // ------------------------------------------------------------------
    // LESS：基础场景
    // ------------------------------------------------------------------

    @Test
    fun `mixin 调用展开定义体声明 - 用户原始场景`() {
        val less = myFixture.configureByText(
            "u.module.less",
            ".app-root {\n  .shared-color();\n}\n\n.shared-color {\n  color: red;\n}\n"
        )
        Assert.assertEquals(listOf("color:red"), declTexts(less, ".app-root"))
    }

    @Test
    fun `不带括号的 mixin 调用同样展开`() {
        val less = myFixture.configureByText(
            "u2.module.less",
            ".a { .m1; }\n.m1 { color: red; }\n"
        )
        Assert.assertEquals(listOf("color:red"), declTexts(less, ".a"))
    }

    @Test
    fun `手写声明与 mixin 声明按出现顺序合并`() {
        val less = myFixture.configureByText(
            "u3.module.less",
            ".a { margin: 0; .m(); padding: 1px; }\n.m { color: red; }\n"
        )
        Assert.assertEquals(
            listOf("margin:0", "color:red", "padding:1px"),
            declTexts(less, ".a")
        )
    }

    @Test
    fun `mixin 套 mixin 递归展开`() {
        val less = myFixture.configureByText(
            "u4.module.less",
            ".a { .m1(); }\n.m1 { color: red; .m2(); }\n.m2 { margin: 0; }\n"
        )
        Assert.assertEquals(
            listOf("color:red", "margin:0"),
            declTexts(less, ".a")
        )
    }

    @Test
    fun `循环 mixin 不死循环且输出安全截断`() {
        // m1 调 m2，m2 调回 m1：路径防环应终止，返回 m1 已收集的部分
        val less = myFixture.configureByText(
            "u5.module.less",
            ".a { .m1(); }\n.m1 { color: red; .m2(); }\n.m2 { margin: 0; .m1(); }\n"
        )
        Assert.assertEquals(
            listOf("color:red", "margin:0"),
            declTexts(less, ".a")
        )
    }

    @Test
    fun `未定义 mixin 忽略不抛错`() {
        val less = myFixture.configureByText(
            "u6.module.less",
            ".a { .nope(); margin: 0; }\n"
        )
        Assert.assertEquals(listOf("margin:0"), declTexts(less, ".a"))
    }

    @Test
    fun `同一 mixin 调用两次展开两次`() {
        val less = myFixture.configureByText(
            "u7.module.less",
            ".a { .m(); .m(); }\n.m { color: red; }\n"
        )
        Assert.assertEquals(
            listOf("color:red", "color:red"),
            declTexts(less, ".a")
        )
    }

    @Test
    fun `嵌套 ruleset 不并入 - 防回归`() {
        val less = myFixture.configureByText(
            "u8.module.less",
            ".a { color: red; .child { margin: 0; } }\n"
        )
        Assert.assertEquals(listOf("color:red"), declTexts(less, ".a"))
    }

    @Test
    fun `自环 mixin 不死循环`() {
        val less = myFixture.configureByText(
            "u9.module.less",
            ".a { .m(); }\n.m { color: red; .m(); }\n"
        )
        Assert.assertEquals(listOf("color:red"), declTexts(less, ".a"))
    }

    // ------------------------------------------------------------------
    // SCSS 形态（@include / @mixin）
    // ------------------------------------------------------------------

    @Test
    fun `scss include 展开 mixin 定义体`() {
        // 用 .module.css 载体（CSS 解析器同样产出 CssAtRule 结构）
        val css = myFixture.configureByText(
            "v.module.css",
            ".a { @include shared; margin: 0; }\n@mixin shared { color: red; }\n"
        )
        Assert.assertEquals(
            listOf("color:red", "margin:0"),
            declTexts(css, ".a")
        )
    }

    // ------------------------------------------------------------------
    // 悬停文档链路（Case 1 / Case 2）
    // ------------------------------------------------------------------

    @Test
    fun `悬停 ruleset 文档包含 mixin 展开的声明`() {
        val less = myFixture.configureByText(
            "w.module.less",
            ".app-root {\n  .shared-color();\n}\n\n.shared-color {\n  color: red;\n}\n"
        )
        val rule = rulesetNamed(less, ".app-root")
        val doc = DashStyleDocumentationProvider().generateDoc(rule, null)
        Assert.assertNotNull(doc)
        val html = doc!!
        Assert.assertTrue("文档应含选择器", html.contains(".app-root"))
        // HTML 中 prop/value 分属不同 span，分开断言
        Assert.assertTrue("文档应含 mixin 展开的属性名 color", html.contains(">color<"))
        Assert.assertTrue("文档应含 mixin 展开的值 red", html.contains(">red<"))
        Assert.assertFalse("不应显示 empty 占位", html.contains("/* empty */"))
    }

    @Test
    fun `悬停 JSX 引用文档包含 mixin 展开的声明`() {
        myFixture.addFileToProject(
            "wm.module.less",
            ".app-root {\n  .shared-color();\n}\n\n.shared-color {\n  color: red;\n}\n"
        )
        val tsx = myFixture.addFileToProject(
            "W.tsx",
            "import styles from './wm.module.less'\nconst a = styles.appRoot\n"
        )
        val ref = PsiTreeUtil.findChildrenOfType(tsx, JSReferenceExpression::class.java)
            .firstOrNull { it.text == "styles.appRoot" }
        Assert.assertNotNull("应找到 styles.appRoot 引用", ref)
        val doc = DashStyleDocumentationProvider().generateDoc(ref, ref)
        Assert.assertNotNull("悬停应产出文档", doc)
        val html = doc!!
        Assert.assertTrue("文档应含 mixin 展开的属性名 color", html.contains(">color<"))
        Assert.assertTrue("文档应含 mixin 展开的值 red", html.contains(">red<"))
        Assert.assertFalse("不应显示 empty 占位", html.contains("/* empty */"))
    }
}
