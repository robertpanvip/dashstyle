package com.pan.dashstyle

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pan.dashstyle.inspection.DuplicateCssDeclarationsInspection
import com.pan.dashstyle.inspection.UnusedCssModuleClassInspection
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path

/**
 * DashStyleHighlightAnnotator 直接测试：
 *   A) 未使用 CSS Module class 置灰 — 范围应严格夹紧到 selectorList
 *   B) 重复 declarations 黄线 — 范围应只含直接 declaration（排除嵌套 ruleset 子块）
 *
 * 与 DashStyleIntegrationTest 的间接测试互补：这里聚焦 HighlightInfo 的
 * forcedTextAttributesKey 和 range 正确性，而不是业务行为。
 */
@RunWith(JUnit4::class)
@Suppress("UnstableApiUsage")
class DashStyleHighlightAnnotatorTest : BasePlatformTestCase() {

    private var noise: com.intellij.openapi.application.AccessToken? = null

    override fun getTestDataPath(): String =
        Path.of("src/test/testData").toAbsolutePath().toString()

    override fun setUp() {
        super.setUp()
        noise = VueSandboxNoiseFilter.install()
        myFixture.enableInspections(
            UnusedCssModuleClassInspection(),
            DuplicateCssDeclarationsInspection()
        )
    }

    override fun tearDown() {
        try { noise?.finish() } finally { super.tearDown() }
    }

    // ==================== 工具 ====================

    private fun highlights(): List<HighlightInfo> {
        var list: List<HighlightInfo> = emptyList()
        ApplicationManager.getApplication().invokeAndWait {
            list = myFixture.doHighlighting()
        }
        return list
    }

    private fun attrKey(h: HighlightInfo): String? =
        runCatching { h.forcedTextAttributesKey?.externalName }.getOrNull()

    /** 置灰高亮（DashStyleHighlightAnnotator.annotateUnused 产生） */
    private fun grayHighlights(): List<HighlightInfo> =
        highlights().filter { attrKey(it) == "DASHSTYLE_UNUSED_CSS_CLASS" }

    /** 黄线高亮（DashStyleHighlightAnnotator.annotateDuplicate 产生） */
    private fun duplicateHighlights(): List<HighlightInfo> =
        highlights().filter { attrKey(it) == "DASHSTYLE_DUPLICATE_CSS" }

    /** 置灰高亮里包含的类名（从 selectorList 文本提取） */
    private fun grayedClassNames(): Set<String> =
        grayHighlights().flatMap { h ->
            Regex("""\.([_a-zA-Z][_a-zA-Z0-9-]*)""").findAll(h.text.orEmpty()).map { it.groupValues[1] }
        }.toSet()

    // ========================================================
    // A) 置灰范围：应严格夹紧到 selectorList，不覆盖 declarations
    // ========================================================

    @Test
    fun `置灰 — 未使用 class 的 selectorList 被画灰`() {
        myFixture.addFileToProject("App.tsx", """
            import styles from './App.module.css'
            export function App() { return <div className={styles.used}>Hi</div> }
        """.trimIndent())

        val css = myFixture.addFileToProject("App.module.css", """
            .used { padding: 4px; }
            .unused { display: none; }
        """.trimIndent())
        myFixture.openFileInEditor(css.virtualFile!!)

        Assert.assertTrue("unused 应被置灰", "unused" in grayedClassNames())
        Assert.assertFalse("used 不应被置灰", "used" in grayedClassNames())
    }

    @Test
    fun `置灰 — 范围不覆盖 declarations（关键断言）`() {
        myFixture.addFileToProject("App.tsx", """
            import styles from './App.module.css'
            export function App() { return <div className={styles.used}>Hi</div> }
        """.trimIndent())

        val css = myFixture.addFileToProject("App.module.css", """
            .used { padding: 4px; }
            .unused {
              display: none;
              color: red;
              font-size: 14px;
            }
        """.trimIndent())
        myFixture.openFileInEditor(css.virtualFile!!)

        val unusedGray = grayHighlights().firstOrNull { "unused" in it.text.orEmpty() }
            ?: run {
                Assert.fail("unused 应该被置灰")
                return
            }

        // 关键断言：置灰高亮的范围里不应出现 declaration 的属性名
        val grayedText = unusedGray.text.orEmpty()
        val forbiddenInGray = listOf("display", "none", "color", "red", "font-size", "padding", "margin")
        for (kw in forbiddenInGray) {
            Assert.assertFalse(
                "置灰范围不应包含 declaration 关键字 [$kw]；当前置灰文本=[$grayedText]",
                grayedText.contains(kw)
            )
        }

        // 范围应刚好就是 selectorList
        Assert.assertTrue(
            "置灰范围应刚好包含 selectorList '.unused'；当前=[$grayedText]",
            grayedText.contains(".unused") || grayedText.contains(".unused ")
        )
    }

    @Test
    fun `置灰 — LESS-SCSS 嵌套 ruleset 正确处理（子 ruleset 也独立判断）`() {
        myFixture.addFileToProject("App.tsx", """
            import styles from './App.module.scss'
            export function App() { return <div className={styles.parent}>Hi</div> }
        """.trimIndent())

        val scss = myFixture.addFileToProject("App.module.scss", """
            .parent {
              padding: 4px;
              .child-used { color: red; }
              .child-unused { color: blue; }
            }
        """.trimIndent())
        myFixture.openFileInEditor(scss.virtualFile!!)

        val grayed = grayedClassNames()
        // parent 被引用 → 不灰；child-used / child-unused 都没被引用 → 都灰
        Assert.assertFalse("parent 不应被置灰", "parent" in grayed)
        Assert.assertTrue("child-used 也没被引用，应被置灰", "child-used" in grayed)
        Assert.assertTrue("child-unused 应被置灰", "child-unused" in grayed)
    }

    @Test
    fun `置灰 — 有动态引用时整个文件跳过置灰（保守策略）`() {
        myFixture.addFileToProject("App.tsx", """
            import styles from './App.module.css'
            const cls = Math.random() > 0.5 ? 'a' : 'b'
            export function App() { return <div className={styles[cls]}>Hi</div> }
        """.trimIndent())

        val css = myFixture.addFileToProject("App.module.css", """
            .a { padding: 4px; }
            .b { margin: 0; }
            .c { display: none; }
        """.trimIndent())
        myFixture.openFileInEditor(css.virtualFile!!)

        // hasDynamic=true 时 snapshot 会提前返回，所有类都不置灰（保守）
        val grayed = grayedClassNames()
        Assert.assertTrue("有动态引用时所有类都不应置灰（保守策略），但灰了=$grayed", grayed.isEmpty())
    }

    // ========================================================
    // B) 重复声明黄线：范围应只含直接 declaration
    // ========================================================

    @Test
    fun `黄线 — 两条相同 ruleset 都被画波浪下划线`() {
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

        val dups = duplicateHighlights()
        Assert.assertTrue("应有重复黄线", dups.isNotEmpty())
        Assert.assertEquals("两条 ruleset 各一个黄线", 2, dups.size)
    }

    @Test
    fun `黄线 — 范围不覆盖选择器文本（关键断言）`() {
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

        for (h in duplicateHighlights()) {
            val t = h.text?.trim() ?: continue
            // 黄线范围里不应包含选择器或大括号
            Assert.assertFalse(
                "黄线不应覆盖选择器或大括号；当前=[$t]",
                t.startsWith(".") || t == "{" || t == "}"
            )
            // 应该只包含 declaration 的属性/值
            Assert.assertTrue(
                "黄线应覆盖 declaration 关键字；当前=[$t]",
                t.contains("padding") || t.contains("margin") || t.contains("border")
            )
        }
    }

    @Test
    fun `黄线 — 嵌套 SCSS 里黄线不覆盖子 ruleset 的块`() {
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

        val dups = duplicateHighlights()
        Assert.assertTrue("嵌套 SCSS 里两个相同子 ruleset 应被黄线标记", dups.isNotEmpty())

        for (h in dups) {
            val t = h.text?.trim() ?: continue
            // 黄线范围不能包含子 ruleset 的选择器文本（如 .parent、.a、.b）
            Assert.assertFalse(
                "黄线不应覆盖嵌套 ruleset 的选择器文本；当前=[$t]",
                Regex("""\.parent|\.a|\.b""").containsMatchIn(t)
            )
        }
    }

    @Test
    fun `黄线 — MIN_SHARED=3 边界：2 条相同不报`() {
        myFixture.configureByText("T.module.css", """
            .a { padding: 4px; margin: 0; }
            .b { padding: 4px; margin: 0; }
        """.trimIndent())

        Assert.assertTrue("2 条相同不应报黄线（MIN_SHARED=3）", duplicateHighlights().isEmpty())
    }

    // ========================================================
    // C) 置灰 + 黄线共存（同一文件）
    // ========================================================

    @Test
    fun `共存 — 同一文件里未使用 class 被置灰 + 重复声明被画黄线`() {
        myFixture.addFileToProject("App.tsx", """
            import styles from './App.module.css'
            export function App() { return <div className={styles.used}>Hi</div> }
        """.trimIndent())

        val css = myFixture.addFileToProject("App.module.css", """
            .used { padding: 4px; }
            .dup-a { padding: 4px; margin: 0; border-radius: 4px; }
            .dup-b { padding: 4px; margin: 0; border-radius: 4px; }
        """.trimIndent())
        myFixture.openFileInEditor(css.virtualFile!!)

        // 置灰：dup-a / dup-b 都没被引用 → 应被置灰
        // 黄线：dup-a 和 dup-b 声明完全相同 → 应画黄线
        Assert.assertTrue("dup-a 应被置灰", "dup-a" in grayedClassNames())
        Assert.assertTrue("dup-b 应被置灰", "dup-b" in grayedClassNames())
        Assert.assertFalse("used 不应被置灰", "used" in grayedClassNames())
        Assert.assertEquals("两条重复 ruleset 各有一个黄线", 2, duplicateHighlights().size)
    }
}
