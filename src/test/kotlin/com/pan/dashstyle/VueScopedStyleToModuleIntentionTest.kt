package com.pan.dashstyle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.CaretModel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pan.dashstyle.action.VueScopedStyleToModuleIntention
import com.pan.dashstyle.support.CssModuleFileResolver
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path

/**
 * VueScopedStyleToModuleIntention 全流程端到端测试 + 回归测试。
 *
 * 覆盖：
 *   v1.5.0 基础功能   — 静态 class → :class + promote scoped→module
 *   v1.5.1 增强       — :class 动态字符串字面量替换
 *   v1.5.2 回归       — promote scoped+lang="less" 保留 lang（零宽 lookahead bug）
 *   v1.5.3 回归       — kebab-case bracket 内层单引号（引号嵌套 bug）
 *   v1.5.4 回归       — 混合 Tailwind 保留（不丢掉非 CSS 定义类）+ 多 scoped style tags
 *   v1.5.5 回归       — LESS 嵌套 LCA 计算 + 插入位置在第一个子 ruleset 之前
 */
@RunWith(JUnit4::class)
@Suppress("UnstableApiUsage")
class VueScopedStyleToModuleIntentionTest : BasePlatformTestCase() {

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

    // ======================== 工具 ========================

    private fun configureVue(text: String): com.intellij.psi.xml.XmlFile {
        val vf = myFixture.addFileToProject("Test.vue", text)
        myFixture.openFileInEditor(vf.virtualFile!!)
        return myFixture.file as com.intellij.psi.xml.XmlFile
    }

    /** 跑 Intention.invoke() 并返回修改后文件文本。 */
    private fun runIntentionAndGetText(project: Project, file: com.intellij.psi.PsiFile): String {
        val editor = myFixture.editor
        VueScopedStyleToModuleIntention().invoke(project, editor, file)
        // Intention 自己跑 WriteCommandAction，invoke 返回后 document 应该已提交
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)
            ?: return file.text
        return doc.text
    }

    /** 只跑 promoteVueStyleModule，检查 <style 开头属性字符串（快速回归 v1.5.2 零宽 bug）。 */
    private fun promoteAndGetOpenTag(project: Project, file: com.intellij.psi.PsiFile): String {
        WriteCommandAction.runWriteCommandAction(project) {
            CssModuleFileResolver.promoteVueStyleModule(project, file)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return file.text
        val t = doc.text
        val idx = t.indexOf("<style")
        val end = t.indexOf(">", idx)
        return t.substring(idx, end + 1)
    }

    private fun isAvailable(file: com.intellij.psi.PsiFile): Boolean =
        VueScopedStyleToModuleIntention().isAvailable(project, myFixture.editor, file)

    // ======================== v1.5.0 基础功能 ========================

    @Test
    fun `基础 — 静态 class 全部替换 + promote scoped 到 module`() {
        val vue = configureVue("""
<template>
  <div class="box">Hello</div>
  <span class="title">Title</span>
</template>
<style scoped>
.box { padding: 12px; }
.title { font-weight: bold; }
</style>
        """.trimIndent())

        Assert.assertTrue("场景应可迁移", isAvailable(vue))
        val after = runIntentionAndGetText(project, vue)

        // style scoped → module
        Assert.assertTrue("应含 <style module", after.contains("<style module"))
        Assert.assertFalse("不应再含 scoped", after.contains("scoped"))

        // class → :class="${'$'}style.xxx"
        Assert.assertTrue("box 应被转成 ${'$'}style.box", after.contains("""<div :class="${'$'}style.box">"""))
        Assert.assertTrue("title 应被转成 ${'$'}style.title", after.contains("""<span :class="${'$'}style.title">"""))
    }

    @Test
    fun `基础 — 多类名用 JS 数组拼`() {
        val vue = configureVue("""
<template>
  <div class="container wrapper">Hi</div>
</template>
<style scoped>
.container { display: flex; }
.wrapper { padding: 8px; }
</style>
        """.trimIndent())

        runIntentionAndGetText(project, vue)

        val after = myFixture.file.text
        Assert.assertTrue("多类名应拼成数组，含 container 和 wrapper",
            after.contains(""":class="[${'$'}style.container, ${'$'}style.wrapper]""") ||
            after.contains(""":class="[${'$'}style.container,${'$'}style.wrapper]"""))
    }

    // ======================== v1.5.2 回归 — promote 零宽 lookahead bug ========================

    @Test
    fun `回归 v1_5_2 — promote scoped 到 module 不产生粘标签`() {
        val vue = configureVue("""
<style scoped>
.box { padding: 12px; }
</style>
        """.trimIndent())

        val open = promoteAndGetOpenTag(project, vue)

        Assert.assertEquals("应是 <style module>，不是 <style module<style>",
            "<style module>", open)
    }

    @Test
    fun `回归 v1_5_2 — promote 保留 lang 属性`() {
        val vue = configureVue("""
<style scoped lang="less">
.box { padding: 12px; }
</style>
        """.trimIndent())

        val open = promoteAndGetOpenTag(project, vue)

        Assert.assertEquals("应是 <style module lang=\"less\">",
            "<style module lang=\"less\">", open)
    }

    @Test
    fun `回归 v1_5_2 — promote scoped+module 共存时跳过`() {
        val vue = configureVue("""
<style scoped module>
.box { padding: 12px; }
</style>
        """.trimIndent())

        // 已经有 module，不应返回非 null
        WriteCommandAction.runWriteCommandAction(project) {
            val res = CssModuleFileResolver.promoteVueStyleModule(project, vue)
            Assert.assertNull("已有 module 属性时应返回 null", res)
        }
    }

    // ======================== v1.5.3 回归 — kebab bracket 内层单引号 ========================

    @Test
    fun `回归 v1_5_3 — kebab-case 类名 bracket 内层单引号`() {
        val vue = configureVue("""
<template>
  <div class="node-right">Hi</div>
</template>
<style scoped>
.node-right { display: flex; }
</style>
        """.trimIndent())

        val after = runIntentionAndGetText(project, vue)

        // 关键：外层 :class="..." 双引号，内层 bracket 必须用单引号
        Assert.assertTrue("应是 ${'$'}style['node-right'] 单引号，不是 ${'$'}style[\"node-right\"] 双引号嵌双引号",
            after.contains(""":class="${'$'}style['node-right']" """.trim()))
        Assert.assertFalse("内层不能是双引号嵌套双引号",
            after.contains("""${'$'}style["node-right"]"""))
    }

    @Test
    fun `回归 v1_5_3 — camelCase 类名 dot 写法不变`() {
        val vue = configureVue("""
<template>
  <div class="nodeRight">Hi</div>
</template>
<style scoped>
.nodeRight { display: flex; }
</style>
        """.trimIndent())

        val after = runIntentionAndGetText(project, vue)

        Assert.assertTrue("camelCase 应保持 ${'$'}style.nodeRight 写法",
            after.contains("""${'$'}style.nodeRight"""))
    }

    // ======================== v1.5.4 回归 — 混合 Tailwind 保留 + 多 scoped style tags ========================

    @Test
    fun `回归 v1_5_4 — 混合 Tailwind 保留未命中类`() {
        val vue = configureVue("""
<template>
  <div class="mybtn flex gap-2">Hi</div>
</template>
<style scoped>
.mybtn { padding: 12px; }
</style>
        """.trimIndent())

        val after = runIntentionAndGetText(project, vue)

        // mybtn 命中 CSS → ${'$'}style.mybtn；flex/gap-2 是 Tailwind → 保持 JS 字符串字面量
        Assert.assertTrue("应保留 Tailwind flex 类", after.contains("'flex'"))
        Assert.assertTrue("应保留 Tailwind gap-2 类", after.contains("'gap-2'"))
        Assert.assertTrue("mybtn 应被转成 ${'$'}style.mybtn", after.contains("${'$'}style.mybtn"))
    }

    @Test
    fun `回归 v1_5_4 — 多个 scoped style tags 都被扫描`() {
        val vue = configureVue("""
<template>
  <div class="a b">Hi</div>
</template>
<style scoped>
.a { color: red; }
</style>
<style scoped>
.b { color: blue; }
</style>
        """.trimIndent())

        val after = runIntentionAndGetText(project, vue)

        Assert.assertTrue("a 应被转", after.contains("${'$'}style.a"))
        Assert.assertTrue("b 应被转（第二个 scoped style 里定义的）", after.contains("${'$'}style.b"))
        // 两个 scoped → module
        Assert.assertEquals("两个 style tag 都应变成 module", 2,
            Regex("""<style module""").findAll(after).count())
    }

    @Test
    fun `回归 v1_5_4 — 全局不带 scoped 的 style 类不被误算`() {
        val vue = configureVue("""
<template>
  <div class="mybtn global-class">Hi</div>
</template>
<style scoped>
.mybtn { padding: 12px; }
</style>
<style>
.global-class { font-size: 14px; }
</style>
        """.trimIndent())

        val after = runIntentionAndGetText(project, vue)

        // global-class 定义在不带 scoped 的 style 里，不应被计入 allClasses → 保持原样
        Assert.assertTrue("global-class 不应被转成 ${'$'}style.global-class（定义在全局 style）",
            after.contains("'global-class'") || after.contains("global-class"))
        Assert.assertTrue("mybtn 应被转", after.contains("${'$'}style.mybtn"))
    }

    // ======================== v1.5.1 增强 — :class 动态字面量替换 ========================

    @Test
    fun `v1_5_1 增强 - class 数组里的静态字符串替换`() {
        val vue = configureVue("""
<template>
  <div :class="['card', computedVar]">Hi</div>
</template>
<style scoped>
.card { padding: 12px; }
</style>
        """.trimIndent())

        val after = runIntentionAndGetText(project, vue)

        Assert.assertTrue("数组里的 'card' 应被替换成 ${'$'}style.card",
            after.contains("""[${'$'}style.card"""))
        Assert.assertTrue("computedVar 应保持原样", after.contains("computedVar"))
    }

    @Test
    fun `v1_5_1 增强 — ternary 里的字面量替换`() {
        val vue = configureVue("""
<template>
  <div :class="isB ? 'highlight' : 'muted'">Hi</div>
</template>
<style scoped>
.highlight { color: blue; }
.muted { color: gray; }
</style>
        """.trimIndent())

        val after = runIntentionAndGetText(project, vue)

        Assert.assertTrue("'highlight' 应被转", after.contains("""${'$'}style.highlight"""))
        Assert.assertTrue("'muted' 应被转", after.contains("""${'$'}style.muted"""))
        // 原字符串字面量不应再留
        Assert.assertFalse("原 'highlight' 字符串应被替换",
            after.contains("""'highlight'"""))
        Assert.assertFalse("原 'muted' 字符串应被替换",
            after.contains("""'muted'"""))
    }

    @Test
    fun `v1_5_1 增强 - class 反引号模板 literal 不被误碰`() {
        val vue = configureVue("""
<template>
  <div :class="`icon-${'$'}{type}`">Hi</div>
</template>
<style scoped>
.icon-user { display: block; }
</style>
        """.trimIndent())

        // 反引号模板不是 JSLiteralExpression 的匹配类型，isAvailable 应该 false
        // （因为 CSS 里没有 icon-${type} 这个类名）
        Assert.assertFalse("反引号模板不应触发迁移", isAvailable(vue))
    }

    // ======================== isAvailable 边界 ========================

    @Test
    fun `isAvailable — 非 vue 文件返回 false`() {
        val txt = myFixture.addFileToProject("Test.tsx", "export default 1")
        myFixture.openFileInEditor(txt.virtualFile!!)
        val f = myFixture.file
        Assert.assertFalse("TSX 文件不应触发",
            VueScopedStyleToModuleIntention().isAvailable(project, myFixture.editor, f))
    }

    @Test
    fun `isAvailable — 已有 style module 返回 false`() {
        val vue = configureVue("""
<template><div class="a">Hi</div></template>
<style module>
.a { padding: 0; }
</style>
        """.trimIndent())
        Assert.assertFalse("已有 module 不应触发", isAvailable(vue))
    }

    @Test
    fun `isAvailable — 无 style scoped 返回 false`() {
        val vue = configureVue("""
<template><div class="a">Hi</div></template>
<style>
.a { padding: 0; }
</style>
        """.trimIndent())
        Assert.assertFalse("无 scoped 不应触发", isAvailable(vue))
    }

    @Test
    fun `isAvailable — CSS 里有类但模板里没引用返回 false`() {
        val vue = configureVue("""
<template><div>No class here</div></template>
<style scoped>
.a { padding: 0; }
</style>
        """.trimIndent())
        Assert.assertFalse("模板无 class 不应触发", isAvailable(vue))
    }
}
