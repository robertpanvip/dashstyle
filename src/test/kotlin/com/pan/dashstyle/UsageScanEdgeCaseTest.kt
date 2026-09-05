package com.pan.dashstyle

import com.pan.dashstyle.support.CssModuleResolver.CssContainer
import com.pan.dashstyle.support.CssModuleUsageScanner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Vue $style 扫描边缘用例（沙箱）：
 * 双引号 bracket、camelCase member 转换、module="foo" 别名、静态 class 属性引用。
 */
@RunWith(JUnit4::class)
@Suppress("UnstableApiUsage")
class UsageScanEdgeCaseTest : BasePlatformTestCase() {

    private fun vueContainer(xml: com.intellij.psi.PsiFile, alias: String = "\$style"): CssContainer.VueStyleTag {
        val modTag = PsiTreeUtil.findChildrenOfType(xml, XmlTag::class.java)
            .firstOrNull { it.name.equals("style", ignoreCase = true) && it.getAttribute("module") != null }
        Assert.assertNotNull("应有 <style module> 标签", modTag)
        return CssContainer.VueStyleTag(modTag!!, alias, xml)
    }

    @Test
    fun `双引号 bracket 访问命中 kebab 类名`() {
        val xml = myFixture.configureByText(
            "AppDq.vue.xml",
            """
            <style module>
            .flex-item { gap: 8px; }
            </style>
            <template>
              <span :class='${'$'}style["flex-item"]'>Item</span>
            </template>
            """.trimIndent()
        )
        ApplicationManager.getApplication().runReadAction<CssContainer.VueStyleTag> { vueContainer(xml) }.let { c ->
            val (used, dynamic) = ApplicationManager.getApplication().runReadAction<Pair<MutableSet<String>, Boolean>> {
                CssModuleUsageScanner.scanUsages(xml, c)
            }
            Assert.assertFalse(dynamic)
            Assert.assertTrue("双引号 bracket 应命中 flex-item", "flex-item" in used)
        }
    }

    @Test
    fun `camelCase member 访问转换为 kebab 后命中`() {
        val xml = myFixture.configureByText(
            "AppCamel.vue.xml",
            """
            <style module>
            .flex-item { gap: 8px; }
            </style>
            <template>
              <div :class="${'$'}style.flexItem">Hello</div>
            </template>
            """.trimIndent()
        )
        val c = ApplicationManager.getApplication().runReadAction<CssContainer.VueStyleTag> { vueContainer(xml) }
        val (used, _) = ApplicationManager.getApplication().runReadAction<Pair<MutableSet<String>, Boolean>> {
            CssModuleUsageScanner.scanUsages(xml, c)
        }
        Assert.assertTrue("flexItem 应归一为 flex-item", "flex-item" in used)
    }

    @Test
    fun `module 别名 foo 的 dollar 引用可被扫描`() {
        val xml = myFixture.configureByText(
            "AppAlias.vue.xml",
            """
            <style module="foo">
            .card { padding: 1px; }
            </style>
            <template>
              <div :class="${'$'}foo.card">Hello</div>
            </template>
            """.trimIndent()
        )
        val c = ApplicationManager.getApplication().runReadAction<CssContainer.VueStyleTag> { vueContainer(xml, "\$foo") }
        val (used, _) = ApplicationManager.getApplication().runReadAction<Pair<MutableSet<String>, Boolean>> {
            CssModuleUsageScanner.scanUsages(xml, c)
        }
        Assert.assertTrue("别名 ${'$'}foo.card 应命中 card", "card" in used)
    }

    @Test
    fun `静态 class 属性中的 dollar 引用同样计入`() {
        val xml = myFixture.configureByText(
            "AppStatic.vue.xml",
            """
            <style module>
            .bold { font-weight: bold; }
            </style>
            <template>
              <div class="${'$'}style.bold">Hello</div>
            </template>
            """.trimIndent()
        )
        val c = ApplicationManager.getApplication().runReadAction<CssContainer.VueStyleTag> { vueContainer(xml) }
        val (used, _) = ApplicationManager.getApplication().runReadAction<Pair<MutableSet<String>, Boolean>> {
            CssModuleUsageScanner.scanUsages(xml, c)
        }
        Assert.assertTrue("静态 class 属性里的 ${'$'}style.bold 应命中 bold", "bold" in used)
    }

    @Test
    fun `模板里无任何引用时 used 为空且非 dynamic`() {
        val xml = myFixture.configureByText(
            "AppNone.vue.xml",
            """
            <style module>
            .a { color: red; }
            </style>
            <template>
              <div class="plain">Hello</div>
            </template>
            """.trimIndent()
        )
        val c = ApplicationManager.getApplication().runReadAction<CssContainer.VueStyleTag> { vueContainer(xml) }
        val (used, dynamic) = ApplicationManager.getApplication().runReadAction<Pair<MutableSet<String>, Boolean>> {
            CssModuleUsageScanner.scanUsages(xml, c)
        }
        Assert.assertTrue(used.isEmpty())
        Assert.assertFalse(dynamic)
    }
}
