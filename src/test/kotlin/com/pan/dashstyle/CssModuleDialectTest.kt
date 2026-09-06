package com.pan.dashstyle

import com.pan.dashstyle.support.CssModuleFileResolver
import com.pan.dashstyle.support.StyleDialect

import com.intellij.openapi.application.AccessToken
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path

/**
 * 样式方言探测 + module 文件自动创建的回归测试。
 *
 * 覆盖两个历史 bug：
 *  1. 提取意图找不到目标 CSS Module 时直接报错，没有按项目风格
 *     （less / sass / scss / 原生 css）自动创建 Xxx.module.xxx —— [CssModuleFileResolver.ensureSameNameModuleFile]；
 *  2. 批量迁移在目标文件缺失时 VFS 变更（createChildData / rename）不在写动作内执行，
 *     AssertionError 被 runCatching 吞掉导致静默失败 —— [CssModuleFileResolver.createModuleFileInWriteAction]。
 *
 * 文件布局约定：addFileToProject 不带目录前缀时落在项目源根（与 DashStyleIntegrationTest #1 相同），
 * FilenameIndex / 同目录投票都能命中；census 用例把样式文件放进 styles/ 子目录，避免触发同目录投票。
 */
@RunWith(JUnit4::class)
class CssModuleDialectTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String =
        Path.of("src/test/testData").toAbsolutePath().toString()

    private var errorProcessorToken: AccessToken? = null

    override fun setUp() {
        super.setUp()
        errorProcessorToken = VueSandboxNoiseFilter.install()
    }

    override fun tearDown() {
        try {
            errorProcessorToken?.finish()
        } finally {
            super.tearDown()
        }
    }

    private fun addTsProbe(): PsiFile = myFixture.addFileToProject(
        "Qux.tsx",
        "import React from 'react';\n" +
            "export function Qux() { return <div className=\"legacy\">q</div>; }\n"
    )

    // ========================================================================
    // StyleDialect 名称/扩展名映射
    // ========================================================================

    @Test
    fun testStyleDialectFromModuleName() {
        assertEquals(StyleDialect.CSS, StyleDialect.fromModuleName("App.module.css"))
        assertEquals(StyleDialect.SCSS, StyleDialect.fromModuleName("App.module.SCSS"))
        assertEquals(StyleDialect.SASS, StyleDialect.fromModuleName("a.b.module.sass"))
        assertEquals(StyleDialect.LESS, StyleDialect.fromModuleName("App.module.less"))
        assertNull(StyleDialect.fromModuleName("App.module.styl"))
        assertNull(StyleDialect.fromModuleName("App.css"))
        assertNull(StyleDialect.fromModuleName("App.tsx"))
    }

    @Test
    fun testStyleDialectFromExt() {
        assertEquals(StyleDialect.CSS, StyleDialect.fromFileExt("css"))
        assertEquals(StyleDialect.SCSS, StyleDialect.fromFileExt("SCSS"))
        assertEquals(StyleDialect.SASS, StyleDialect.fromFileExt("sass"))
        assertEquals(StyleDialect.LESS, StyleDialect.fromFileExt("less"))
        assertNull(StyleDialect.fromFileExt("styl"))
        assertNull(StyleDialect.fromFileExt(null))
        assertTrue(StyleDialect.SASS.indentedSyntax)
        assertTrue(!StyleDialect.SCSS.indentedSyntax)
    }

    // ========================================================================
    // detectProjectDialect 探测级联
    // ========================================================================

    @Test
    fun testDialectDefaultsToCssInEmptyProject() {
        val probe = addTsProbe()
        assertEquals(StyleDialect.CSS, CssModuleFileResolver.detectProjectDialect(project, probe.virtualFile))
    }

    @Test
    fun testDialectPrefersSameDirModuleVote() {
        // 同目录 1 个 module.less（权重 3）压过其他目录 2 个 module.scss
        val probe = addTsProbe()
        myFixture.addFileToProject("Other.module.less", ".a { color: red; }\n")
        myFixture.addFileToProject("styles/x.module.scss", ".a { color: red; }\n")
        myFixture.addFileToProject("styles/y.module.scss", ".a { color: red; }\n")
        assertEquals(StyleDialect.LESS, CssModuleFileResolver.detectProjectDialect(project, probe.virtualFile))
    }

    @Test
    fun testDialectCensusAcrossProject() {
        // 没有同目录 module → 全项目统计：scss module ×3（9 分）> less plain ×3（3 分）
        val probe = addTsProbe()
        myFixture.addFileToProject("styles/a.module.scss", ".a { color: red; }\n")
        myFixture.addFileToProject("styles/b.module.scss", ".b { color: red; }\n")
        myFixture.addFileToProject("styles/c.module.scss", ".c { color: red; }\n")
        myFixture.addFileToProject("styles/d.less", ".d { color: red; }\n")
        myFixture.addFileToProject("styles/e.less", ".e { color: red; }\n")
        myFixture.addFileToProject("styles/f.less", ".f { color: red; }\n")
        assertEquals(StyleDialect.SCSS, CssModuleFileResolver.detectProjectDialect(project, probe.virtualFile))
    }

    @Test
    fun testDialectFromPackageJsonSassDependency() {
        val probe = addTsProbe()
        myFixture.addFileToProject(
            "package.json",
            """{"name":"t","devDependencies":{"react":"^18.2.0","sass":"^1.69.0"}}"""
        )
        assertEquals(StyleDialect.SCSS, CssModuleFileResolver.detectProjectDialect(project, probe.virtualFile))
    }

    @Test
    fun testDialectFromPackageJsonLessDependency() {
        val probe = addTsProbe()
        myFixture.addFileToProject(
            "package.json",
            """{"name":"t","devDependencies":{"react":"^18.2.0","less":"^4.2.0"}}"""
        )
        assertEquals(StyleDialect.LESS, CssModuleFileResolver.detectProjectDialect(project, probe.virtualFile))
    }

    // ========================================================================
    // createModuleFile 重载 + 写动作包装
    // ========================================================================

    @Test
    fun testCreateModuleFileOverloads() {
        val probe = addTsProbe()
        val parent = probe.virtualFile!!.parent
        WriteCommandAction.runWriteCommandAction(project) {
            assertEquals("Foo.module.less", CssModuleFileResolver.createModuleFile(parent, "Foo", "less")?.name)
            assertEquals("Bar.module.css", CssModuleFileResolver.createModuleFile(parent, "Bar", null)?.name)
            assertEquals("Baz.module.scss", CssModuleFileResolver.createModuleFile(parent, "Baz", StyleDialect.SCSS)?.name)
        }
    }

    @Test
    fun testCreateModuleFileInWriteActionFromPlainContext() {
        // 回归：不先持有写动作直接调用（模拟 actionPerformed 场景），
        // 旧的裸 createChildData 会因 AssertionError 被 runCatching 吞掉而返回 null
        val probe = addTsProbe()
        val parent = probe.virtualFile!!.parent
        val created = CssModuleFileResolver.createModuleFileInWriteAction(project, parent, "Waldo", StyleDialect.SASS)
        assertEquals("Waldo.module.sass", created?.name)
        assertEquals("Waldo.module.sass", parent.findChild("Waldo.module.sass")?.name)
    }

    // ========================================================================
    // ensureSameNameModuleFile：找不到目标时按方言自动创建 + 补 import
    // ========================================================================

    @Test
    fun testEnsureCreatesNativeModuleCssAndImport() {
        val probe = addTsProbe()
        val (vf, binding) = CssModuleFileResolver.ensureSameNameModuleFile(project, probe)!!

        assertEquals("Qux.module.css", vf.name)
        assertEquals("styles", binding)
        assertEquals("Qux.module.css", probe.virtualFile!!.parent.findChild("Qux.module.css")?.name)
        assertTrue(
            "应生成 import 语句，实际：\n${probe.text}",
            probe.text.contains("import styles from './Qux.module.css'")
        )
    }

    @Test
    fun testEnsureReusesExistingSameNameLessModule() {
        val probe = addTsProbe()
        myFixture.addFileToProject("Qux.module.less", ".a { color: red; }\n")

        val (vf, binding) = CssModuleFileResolver.ensureSameNameModuleFile(project, probe)!!

        // 复用已有 Qux.module.less，而不是另建 .module.css
        assertEquals("Qux.module.less", vf.name)
        assertEquals("styles", binding)
        assertNull(probe.virtualFile!!.parent.findChild("Qux.module.css"))
        assertTrue(
            "应补上缺失的 import，实际：\n${probe.text}",
            probe.text.contains("import styles from './Qux.module.less'")
        )
    }

    @Test
    fun testEnsureCreatesScssModuleFromSameDirVote() {
        val probe = addTsProbe()
        myFixture.addFileToProject("Other.module.scss", ".a { color: red; }\n")

        val (vf, _) = CssModuleFileResolver.ensureSameNameModuleFile(project, probe)!!

        assertEquals("Qux.module.scss", vf.name)
        assertTrue(probe.text.contains("import styles from './Qux.module.scss'"))
    }

    @Test
    fun testEnsureCreatesSassModuleFromSameDirVote() {
        val probe = addTsProbe()
        myFixture.addFileToProject("Other.module.sass", ".a\n  color: red\n")

        val (vf, _) = CssModuleFileResolver.ensureSameNameModuleFile(project, probe)!!

        assertEquals("Qux.module.sass", vf.name)
        assertTrue(probe.text.contains("import styles from './Qux.module.sass'"))
    }
}
