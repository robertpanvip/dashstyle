package com.pan.dashstyle

import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pan.dashstyle.action.InlineStyleToCssModuleIntention
import com.pan.dashstyle.support.CssModuleResolver.CssContainer
import com.pan.dashstyle.support.CssModuleUsageScanner
import com.pan.dashstyle.support.Util
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 真实 .vue 文件（沙箱已装 Vue 插件，.vue → VueFileImpl）上的生产路径集成测试。
 *
 * 与 DashStyleIntegrationTest / UsageScanEdgeCaseTest 的 .vue.xml 替身互补：
 * 替身文件名不是 .vue 结尾且无 JS PSI，只能覆盖 scanUsages 的正则 fallback
 * （模板属性值文本扫描）；真实 .vue 里 $style.xxx 会被 Vue 插件注入 JS PSI
 * （JSReferenceExpression / JSIndexedPropertyAccessExpression），从而首次覆盖：
 *  - scanUsages 的 PSI 路径（路径 1/2）
 *  - CssModuleResolver.resolveQualifier 的 Vue fallback 分支
 *    （contextFile.name 以 .vue 结尾且为 XmlFile —— 沙箱装 Vue 插件前该分支不可达）
 *  - Util.findVariableDeclarationByName 的 VueScriptSetupEmbeddedContentImpl 路径
 *  - handleVueTemplateReplacement 在真实 VueFile 上的合并/替换
 */
@RunWith(JUnit4::class)
class RealVueFileIntegrationTest : BasePlatformTestCase() {

    private var noiseToken: com.intellij.openapi.application.AccessToken? = null

    override fun setUp() {
        super.setUp()
        noiseToken = VueSandboxNoiseFilter.install()
    }

    override fun tearDown() {
        try {
            noiseToken?.finish()
        } finally {
            super.tearDown()
        }
    }

    private fun vueContainer(f: PsiFile, alias: String = "\$style"): CssContainer.VueStyleTag {
        val modTag = PsiTreeUtil.findChildrenOfType(f, XmlTag::class.java)
            .firstOrNull { it.name.equals("style", ignoreCase = true) && it.getAttribute("module") != null }
        Assert.assertNotNull("应有 <style module> 标签", modTag)
        return CssContainer.VueStyleTag(modTag!!, alias, f)
    }

    private fun scan(f: PsiFile, c: CssContainer): Pair<MutableSet<String>, Boolean> =
        ApplicationManager.getApplication().runReadAction<Pair<MutableSet<String>, Boolean>> {
            CssModuleUsageScanner.scanUsages(f, c)
        }

    // ========================================================================
    // 1. scanUsages PSI 路径：$style.flex（dot）与 $style['flex-item']（bracket）
    //    均经 JS PSI + resolveQualifier Vue fallback 命中
    // ========================================================================
    @Test
    fun `真实 vue 文件 dot 与 bracket 引用均被识别`() {
        val f = myFixture.configureByText(
            "AppUsage.vue",
            """
            <template>
              <div :class="${'$'}style.flex">
                <span :class="${'$'}style['flex-item']">Item</span>
              </div>
            </template>
            <style module>
            .flex { display: flex; }
            .flex-item { gap: 8px; }
            .unused { opacity: 0; }
            </style>
            """.trimIndent()
        )
        Assert.assertEquals("真实 .vue 应解析为 Vue 语言", "Vue", f.language.id)
        val (used, dynamic) = scan(f, vueContainer(f))
        Assert.assertFalse("hasDynamic 应为 false", dynamic)
        Assert.assertTrue("dot 访问 flex 应命中", "flex" in used)
        Assert.assertTrue("bracket 访问 flex-item 应命中", "flex-item" in used)
        Assert.assertFalse("unused 不应命中", "unused" in used)
    }

    // ========================================================================
    // 2. $style[变量] → hasDynamic = true（PSI 路径：index 非字面量）
    // ========================================================================
    @Test
    fun `真实 vue 文件动态变量引用置 hasDynamic`() {
        val f = myFixture.configureByText(
            "AppDynamic.vue",
            """
            <template>
              <div :class="${'$'}style[className]">Hello</div>
            </template>
            <style module>
            .flex { display: flex; }
            </style>
            """.trimIndent()
        )
        val (_, dynamic) = scan(f, vueContainer(f))
        Assert.assertTrue("\$style[变量] 应置 hasDynamic", dynamic)
    }

    // ========================================================================
    // 3. 无关 $ 变量（$notstyle.bar）不计入 —— resolveQualifier 不再兜底到
    //    任意 style 标签（旧 any-fallback 会把 bar 错误计入第一个 module）
    // ========================================================================
    @Test
    fun `真实 vue 文件无关 dollar 变量不计入使用`() {
        val f = myFixture.configureByText(
            "AppUnrelated.vue",
            """
            <template>
              <div :class="${'$'}notstyle.bar">Unrelated</div>
            </template>
            <style module>
            .bar { color: red; }
            </style>
            """.trimIndent()
        )
        val (used, dynamic) = scan(f, vueContainer(f))
        Assert.assertFalse("无关 ${'$'} 变量不应置 hasDynamic", dynamic)
        Assert.assertFalse("无关 ${'$'} 变量不应计入使用（notstyle 未匹配任何 module）", "bar" in used)
    }

    // ========================================================================
    // 4. module="foo" 具名别名：$foo.card 应命中 card
    // ========================================================================
    @Test
    fun `真实 vue 文件 module 别名引用命中`() {
        val f = myFixture.configureByText(
            "AppAlias.vue",
            """
            <template>
              <div :class="${'$'}foo.card">Card</div>
            </template>
            <style module="foo">
            .card { padding: 1px; }
            </style>
            """.trimIndent()
        )
        val (used, _) = scan(f, vueContainer(f, "\$foo"))
        Assert.assertTrue("别名 ${'$'}foo.card 应命中 card", "card" in used)
    }

    // ========================================================================
    // 5. <script setup> 变量定位：parent 为 VueScriptSetupEmbeddedContentImpl
    //    （该类只在真实 Vue PSI 中存在，.xml 替身不可达）
    // ========================================================================
    @Test
    fun `真实 vue 文件 script setup 变量可被定位`() {
        val f = myFixture.configureByText(
            "AppSetup.vue",
            """
            <template>
              <div/>
            </template>
            <script setup>
            const css = useCssModule()
            </script>
            <style module>
            .box { color: red; }
            </style>
            """.trimIndent()
        )
        val scriptTag = Util.findScriptTag(f)
        Assert.assertNotNull("应能找到 <script> 标签", scriptTag)
        val found = ApplicationManager.getApplication().runReadAction<JSVariable?> {
            Util.findVariableDeclarationByName("css", scriptTag)
        }
        Assert.assertNotNull(
            "script setup 中 const css 应可定位（parent 应为 VueScriptSetupEmbeddedContentImpl）", found)
        val missing = ApplicationManager.getApplication().runReadAction<JSVariable?> {
            Util.findVariableDeclarationByName("notExist", scriptTag)
        }
        Assert.assertNull("未声明的名字不应命中", missing)
    }

    // ========================================================================
    // 6. :style 替换 · 已有 :class → 合并（DashStyleIntegrationTest #27 的
    //    真实 .vue 版）：[dyn, $style.card]，绝不产生第二个 :class
    // ========================================================================
    @Test
    fun `真实 vue 模板 style 属性合并进已有 class 绑定`() {
        val f = myFixture.configureByText(
            "AppMerge.vue",
            """
            <template>
              <div class="s" :class="dyn" :style="{ color: 'red' }">hi</div>
            </template>
            <style module>
            .card { color: red; }
            </style>
            """.trimIndent()
        )
        val styleAttr = PsiTreeUtil.findChildrenOfType(f, XmlAttribute::class.java)
            .firstOrNull { it.name.endsWith(":style") }
        Assert.assertNotNull("找不到 :style 属性", styleAttr)
        val intention = InlineStyleToCssModuleIntention()
        var merged = false
        WriteCommandAction.runWriteCommandAction(project) {
            merged = intention.handleVueTemplateReplacement(project, f, styleAttr!!, "\$style.card")
        }
        Assert.assertTrue("应走 PSI 合并路径（而非 Document 兜底）", merged)
        val t = f.text
        Assert.assertTrue("应合并为数组绑定: $t", t.contains(":class=\"[dyn, \$style.card]\""))
        Assert.assertEquals("只应有一个 :class（重复会 Vue 编译报错）", 1, Regex(""":class=""").findAll(t).count())
        Assert.assertTrue("静态 class 应保留", t.contains("class=\"s\""))
        Assert.assertFalse(":style 应删除", t.contains(":style="))
    }

    // ========================================================================
    // 7. :style 替换 · 无 :class → 新增 :class="$style.card"（#19 机制在
    //    真实 VueFile 上的整链路版本：XmlAttribute dummy 工厂 + replace）
    // ========================================================================
    @Test
    fun `真实 vue 模板 style 属性被整体替换为 class 绑定`() {
        val f = myFixture.configureByText(
            "AppReplace.vue",
            """
            <template>
              <div class="s" :style="{ color: 'red' }">hi</div>
            </template>
            <style module>
            .card { color: red; }
            </style>
            """.trimIndent()
        )
        val styleAttr = PsiTreeUtil.findChildrenOfType(f, XmlAttribute::class.java)
            .firstOrNull { it.name.endsWith(":style") }
        Assert.assertNotNull("找不到 :style 属性", styleAttr)
        val intention = InlineStyleToCssModuleIntention()
        var ok = false
        WriteCommandAction.runWriteCommandAction(project) {
            ok = intention.handleVueTemplateReplacement(project, f, styleAttr!!, "\$style.card")
        }
        Assert.assertTrue("应走 PSI 替换路径", ok)
        val t = f.text
        Assert.assertTrue("应新增 :class 绑定: $t", t.contains(":class=\"\$style.card\""))
        Assert.assertTrue("静态 class 应共存", t.contains("class=\"s\""))
        Assert.assertFalse(":style 应删除", t.contains(":style="))
        Assert.assertEquals(":class 只应有一个", 1, Regex(""":class=""").findAll(t).count())
    }
}
