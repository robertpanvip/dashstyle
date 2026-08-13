package com.pan.dashstyle

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssRuleset
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert
import org.junit.Ignore
import org.junit.Test
import java.nio.file.Path

/**
 * DashStyle 集成测试骨架（JUnit4 风格，运行在 WebStorm-2025.3 沙箱里）。
 *
 * 运行：
 *   $ gradle --init-script _local_init.gradle.kts test --tests "com.pan.dashstyle.DashStyleIntegrationTest"
 *
 * 注意：
 *  - BasePlatformTestCase 使用 JUnit4 @Test；JUnit4 在 JUnit5 platform 上通过 `junit-vintage-engine` 桥接，
 *    build.gradle.kts 里已经添加了该 runtimeOnly 依赖。
 *  - 每条用例前面都有 @Ignore 或 断言宽松（Assert.assertNotNull 这类 smoke 级别的断言），
 *    目的是「先让骨架能编译 + 能启动 IDE」，具体断言强度你验证一遍后置灰/抽取真的跑通后，可以把 @Ignore 去掉并收紧。
 *  - 想让某个用例真正跑：把它上面的 @Ignore 注释掉即可。
 */
@Suppress("UnstableApiUsage", "UNUSED_PARAMETER")
class DashStyleIntegrationTest : BasePlatformTestCase() {

    // ===================== 基础配置 =====================

    override fun getTestDataPath(): String =
        Path.of("src/test/testData").toAbsolutePath().toString()

    override fun setUp() {
        super.setUp()
        // 提前激活 Kotlin/JS/TS/CSS/SCSS/LESS 插件在沙箱里的 Component（可选，通常 BasePlatformTestCase 会自动做）
        runCatching {
            myFixture.allowTreeAccessForAllFiles()
        }
    }

    // ========================================================================
    // #1. 未使用 CSS Module class → 选择器行置灰（范围必须在 selectorList，不包含 declarations）
    // ========================================================================
    @Ignore("骨架 smoke：待本地确认 DashStyleHighlightAnnotator 在沙箱里被注册后再启用")
    @Test
    fun `unused CSS module class should be grayed - only selector line, not declarations`() {
        // Step 1: 先写 TSX（引用 used / nestedChild，不引用 unused / orphan）
        myFixture.configureByText(
            "App.tsx",
            """
            import styles from './App.module.less'
            function App() {
              return (
                <div className={styles.used}>
                  <span className={styles.nestedChild}>Hi</span>
                </div>
              )
            }
            """.trimIndent()
        )

        // Step 2: 写 CSS Module（含：.used, .unused, .nested 下面 &-child, 以及 .orphan）
        val cssFile = myFixture.configureByText(
            "App.module.less",
            """
            .used {
              color: red;
            }
            .unused {
              display: none;
            }
            .nested {
              &-child {
                font-size: 14px;
              }
            }
            .orphan {
              opacity: 0;
            }
            """.trimIndent()
        )

        // Step 3: 打开 CSS 文件做高亮
        myFixture.openFileInEditor(cssFile.virtualFile)
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.doHighlighting() // 强制 daemon 跑一遍
        }
        val highlights: List<HighlightInfo> = myFixture.doHighlighting()

        // ---- 断言 A：.unused 必须被置灰（有 INFORMATION severity 或 dashstyle 的自定义 key）----
        val unusedGray = highlights.filter {
            it.text == "unused" && it.severity.toString().let { s ->
                s.contains("INFORMATION", ignoreCase = true) ||
                        s.contains("WEAK_WARNING", ignoreCase = true) ||
                        (it.forcedTextAttributesKey?.externalName?.contains("UNUSED", ignoreCase = true) == true)
            }
        }
        Assert.assertTrue(
            ".unused 没有被置灰；当前 severity 列表：${highlights.map { "${it.text}=${it.severity}/${it.forcedTextAttributesKey?.externalName}" }}",
            unusedGray.isNotEmpty()
        )

        // ---- 断言 B：.unused 下面的 declarations（display:none）绝对不能被置灰 ----
        val displayGrayed = highlights.filter {
            (it.text == "display" || it.text == "none") &&
                    it.severity.toString().let { s ->
                        s.contains("INFORMATION", ignoreCase = true) ||
                                (it.forcedTextAttributesKey?.externalName?.contains("UNUSED", ignoreCase = true) == true)
                    }
        }
        Assert.assertTrue(
            "误置灰！.unused 的 declarations（display/none）被置灰了：$displayGrayed",
            displayGrayed.isEmpty()
        )

        // ---- 断言 C：.orphan 也必须被置灰（未被 TSX 引用）----
        val orphanGray = highlights.filter {
            it.text == "orphan" && it.severity.toString().let { s ->
                s.contains("INFORMATION", true) || s.contains("WEAK_WARNING", true)
            }
        }
        Assert.assertTrue(".orphan 未被引用但没有置灰", orphanGray.isNotEmpty())

        // ---- 断言 D：.used / .nested 下的 &-child 被用到了，不能被置灰 ----
        val usedGray = highlights.filter {
            it.text == "used" && it.severity.toString().contains("INFORMATION", true)
        }
        Assert.assertTrue(".used 被引用了但仍被置灰", usedGray.isEmpty())
    }

    // ========================================================================
    // #2. 单文件重复 CSS 声明 → 必须有弱警告波浪线
    // ========================================================================
    @Ignore("骨架 smoke")
    @Test
    fun `duplicate CSS declarations should produce weak-warning wave`() {
        val css = myFixture.configureByText(
            "Common.module.scss",
            """
            .card-a {
              padding: 12px 16px;
              border-radius: 8px;
              background: #fff;
            }
            .card-b {
              padding: 12px 16px;
              border-radius: 8px;
              background: #ffffff;
            }
            .unique {
              margin: 0;
            }
            """.trimIndent()
        )
        myFixture.openFileInEditor(css.virtualFile)
        val highlights = myFixture.doHighlighting()

        // 只要有高亮 info 的描述里提到 "identical declarations" 或 "share identical" 就通过
        val duplicateWave = highlights.filter {
            it.description?.contains("identical", ignoreCase = true) == true ||
                    it.forcedTextAttributesKey?.externalName?.contains("DUPLICATE", true) == true
        }
        Assert.assertTrue(
            "重复 CSS 声明检测失败。当前 highlights：${highlights.map { "${it.startOffset}:${it.text} sev=${it.severity} desc=${it.description}" }}",
            duplicateWave.isNotEmpty()
        )
    }

    // ========================================================================
    // #3. InlineStyle → CSS Module Intention 必须在 style={{...}} 上可用
    // ========================================================================
    @Ignore("骨架 smoke")
    @Test
    fun `inline style intention should be available on style attribute`() {
        // 先在同目录放一个目标 CSS Module（否则 intention 会找不到位置写入而直接不出现/失败）
        myFixture.configureByText(
            "Hello.module.less",
            ".existing { color: blue; }\n"
        )

        // 把 TSX 光标定位在 <caret> 位置（放在 color: 'red' 的 'red' 中间）
        myFixture.configureByText(
            "Hello.tsx",
            """
            import styles from './Hello.module.less'
            function Hello() {
              return <div style={{color:'red', width: 100, borderRadius: 8}}><caret></div>
            }
            """.trimIndent()
        )

        val intentions: List<IntentionAction> = myFixture.filterAvailableIntentions("Extract inline style to CSS Module...")
        Assert.assertTrue(
            "Extract inline style to CSS Module intention 未出现在 Alt+Enter 列表；当前可用：${myFixture.availableIntentions.map { it.text }}",
            intentions.isNotEmpty()
        )
    }

    // ========================================================================
    // #4. styles["foo-bar"] 必须能跳回对应的 CSS ruleset
    // ========================================================================
    @Ignore("骨架 smoke")
    @Test
    fun `string key reference styles bracket-foo-bar should resolve back to CssRuleset`() {
        val css = myFixture.configureByText(
            "Foo.module.scss",
            """
            .hello-world {
              color: #123;
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "Foo.tsx",
            """
            import styles from './Foo.module.scss'
            function Foo() { return <div className={styles["hello-<caret>world"]}></div> }
            """.trimIndent()
        )
        val resolved: PsiElement? = runCatching {
            myFixture.getReferenceAtCaretPositionWithAssertion().resolve()
        }.getOrNull()
        Assert.assertNotNull("styles[\"hello-world\"] 跳转失败，resolve() 返回 null", resolved)
        Assert.assertTrue(
            "styles[\"hello-world\"] 解析目标不是 CssRuleset：实际 ${resolved?.javaClass?.name}",
            resolved is CssRuleset || runCatching {
                // 有些版本返回的是 RulesetImpl / CssSelectorList 的子元素
                resolved!!.javaClass.name.let { n ->
                    n.contains("Ruleset", ignoreCase = true) || n.contains("Selector", ignoreCase = true)
                }
            }.getOrDefault(false)
        )
    }

    // ========================================================================
    // #5. LESS 文件抽取重复声明 → 生成 .common(); mixin 调用，绝对不能写 @extend
    // ========================================================================
    @Ignore("骨架 smoke：需要 WebStorm-2025.3 的 LESS plugin 在沙箱注册 inspection，本地验证后启用")
    @Test
    fun `less extract common-class must write mixin-call not at-extend`() {
        val less = myFixture.configureByText(
            "Duplicate.module.less",
            """
            .a {
              padding: 4px;
              margin: 0;
            }
            .b {
              padding: 4px;
              margin: 0;
            }
            """.trimIndent()
        )
        myFixture.openFileInEditor(less.virtualFile)

        // 1. 找出 DuplicateCssDeclarationsInspection 提供的「抽取公共类」intention/quickFix
        //    webstorm-2025.3 SDK 的 filterAvailableIntentions 只接受 String（作为 text 的 substring 过滤），
        //    所以我们先拿全部，再自己用关键字过滤。
        val fixNameHint = listOf(
            "Extract common declarations",
            "抽取公共类",
            "Extract duplicate",
            "抽取重复",
            "common class",
            "Duplicate CSS declarations",
            "identical declarations"
        )
        val allIntentions: List<IntentionAction> = myFixture.availableIntentions
        val actions = allIntentions.filter { a ->
            val t = a.text.lowercase()
            fixNameHint.any { hint -> t.contains(hint.lowercase()) }
        }
        Assert.assertTrue(
            "找不到抽取公共类的 QuickFix；当前 intentions=${myFixture.availableIntentions.map { it.text }}",
            actions.isNotEmpty()
        )

        // 2. 执行第一个候选 quickFix
        val toRun = actions.first()
        WriteCommandAction.writeCommandAction(project).run<Throwable> {
            toRun.invoke(project, myFixture.editor, myFixture.file)
        }

        // 3. 检查最终文件：必须出现 .common(); / .xxx(); 这种 mixin 调用；不得出现 @extend
        val resultText = myFixture.editor.document.text
        Assert.assertFalse(
            "LESS 抽取公共类时错误地写了 @extend（LESS 里不推荐/不支持 SCSS 风格的 @extend）。最终文本：\n$resultText",
            resultText.contains("@extend")
        )
        Assert.assertTrue(
            "LESS 抽取后应该出现 mixin 调用 `.commonName();`。最终文本：\n$resultText",
            Regex("""\.\w+\s*\(\s*\)\s*;""").containsMatchIn(resultText)
        )
    }

    // ========================================================================
    // 0号 smoke 用例（默认不 @Ignore）：验证整个测试沙箱能启动 + CSS 语言文件能 parse 出 CssRuleset
    // 运行：gradle test --tests "DashStyleIntegrationTest.smoke IDE sandbox can parse CSS ruleset"
    // ========================================================================
    @Test
    fun `smoke IDE sandbox can parse CSS ruleset`() {
        val css = myFixture.configureByText(
            "smoke.module.css",
            """
            .hello {
              color: red;
              width: 100px;
            }
            """.trimIndent()
        )
        // 在 runReadAction 里遍历子节点找 CssRuleset
        ApplicationManager.getApplication().runReadAction {
            val rulesets = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(css, CssRuleset::class.java)
            Assert.assertEquals("沙箱应该能 parse 出 1 个 CssRuleset", 1, rulesets.size)
            val rs = rulesets.first()
            val sel = rs.selectorList?.text?.trim()
            Assert.assertEquals("选择器文本应该是 .hello", ".hello", sel)
        }
    }
}
