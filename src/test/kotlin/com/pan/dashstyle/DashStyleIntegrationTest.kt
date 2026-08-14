package com.pan.dashstyle

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssDeclaration
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

        // ---- 断言 A：.unused 必须被置灰（有 INFORMATION severity 或 LIKE_UNUSED_SYMBOL 对应问题描述）
        // 真实 getter：getText() / getSeverity() / getDescription() / getToolTip() 都存在（嗅探确认）。
        val unusedGray = highlights.filter { h ->
            val matchText = h.text?.contains("unused") == true ||
                    (h.description?.contains(".unused", ignoreCase = true) == true) ||
                    (h.toolTip?.contains(".unused", ignoreCase = true) == true)
            val matchSeverity = h.severity.toString().let { s ->
                s.contains("INFORMATION", ignoreCase = true) ||
                        s.contains("LIKE_UNUSED_SYMBOL", ignoreCase = true) ||
                        s.contains("INFO", ignoreCase = true)
            }
            matchText && matchSeverity
        }
        Assert.assertTrue(
            ".unused 没有被置灰；当前高亮：${
                highlights.map {
                    "{text=${it.text}, sev=${it.severity}, desc=${it.description}, tip=${it.toolTip}}"
                }
            }",
            unusedGray.isNotEmpty()
        )

        // ---- 断言 B：.unused 下面的 declarations（display:none）绝对不能被置灰 ----
        val displayGrayed = highlights.filter { h ->
            val t = h.text
            (t == "display" || t == "none") && (
                    h.severity.toString().contains("INFORMATION", true) ||
                            h.description?.contains("is not used", true) == true ||
                            h.toolTip?.contains("is not used", true) == true
                    )
        }
        Assert.assertTrue(
            "误置灰！.unused 的 declarations（display/none）被带上了未使用的 gray info：$displayGrayed",
            displayGrayed.isEmpty()
        )

        // ---- 断言 C：.orphan 也必须被置灰（未被 TSX 引用）----
        val orphanGray = highlights.filter { h ->
            (h.text == "orphan" || h.description?.contains(".orphan") == true || h.toolTip?.contains(".orphan") == true)
                    && (h.severity.toString().let { s ->
                s.contains("INFORMATION", true) || s.contains("INFO", true)
            })
        }
        Assert.assertTrue(".orphan 未被引用但没有置灰：$orphanGray", orphanGray.isNotEmpty())

        // ---- 断言 D：.used / .nested 下的 &-child 被用到了，不能被置灰 ----
        val usedGray = highlights.filter { h ->
            (h.text == "used") && h.severity.toString().contains("INFORMATION", true)
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
        // 真实 getter：getDescription() / getToolTip() / getSeverity()
        val duplicateWave = highlights.filter { h ->
            val same = (h.description?.contains("identical", ignoreCase = true) == true) ||
                    (h.toolTip?.contains("identical", ignoreCase = true) == true) ||
                    (h.description?.contains("shared", ignoreCase = true) == true)
            val sevOk = h.severity.toString().let { s ->
                s.contains("WEAK", true) || s.contains("WARNING", true) ||
                        s.contains("GENERIC_ERROR_OR_WARNING", true) || s.contains("ERROR_OR_WARNING", true)
            }
            same && sevOk
        }
        Assert.assertTrue(
            "重复 CSS 声明检测失败。当前 highlights：${
                highlights.map {
                    "{start=${it.startOffset},end=${it.endOffset},text=${it.text},sev=${it.severity},desc=${it.description},tip=${it.toolTip}"
                }
            }",
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
        //    根据嗅探结果，myFixture.filterAvailableIntentions(String) 存在（参数为文本子串过滤）；
        //    多跑几个子串，命中其中一个即可。
        val filterHints = listOf(
            "Extract",
            "common class",
            "identical declarations",
            "shared declarations"
        )
        val allIntentions: List<IntentionAction> = myFixture.availableIntentions
        val actions = filterHints.asSequence().mapNotNull { hint ->
            runCatching { myFixture.filterAvailableIntentions(hint) }.getOrNull()
        }.flatten().toMutableList()
        // 兜底：自己用关键字再扫一遍 availableIntentions，保证覆盖最大可能
        val fixNameHint = listOf(
            "Extract", "抽取公共", "common class", "Extract common", "declarations into a new common class"
        )
        for (a in allIntentions) {
            val t = a.text.lowercase()
            if (fixNameHint.any { hint -> t.contains(hint.lowercase()) }) actions += a
        }
        Assert.assertTrue(
            "找不到抽取公共类的 QuickFix；当前 intentions=${allIntentions.map { it.text }}",
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

    // ========================================================================
    // 0.1 号 smoke（默认不 @Ignore）：「这就是你说的『在沙箱里嗅探真实类型再静态绑定』最终闭环验证」
    //   沙箱加载 DashStyle 插件后：
    //     a) plugin.xml 里声明的 DashStyle.UnusedCssClass.* / DashStyle.DuplicateCss.* inspection shortName
    //        必须真实出现在 InspectionProfile 里；
    //     b) UnusedCssModuleClassInspection / DuplicateCssDeclarationsInspection 两个类必须能被
    //        沙箱 PluginClassLoader 加载 & 实例化（真实环境报的就是 "Cannot create class"）。
    //   如果这条用例在沙箱里 PASS，基本等价于"你本地 WS-2026.2 真实 IDE 也不会再报 Cannot create class / shortName not unique"。
    // ========================================================================
    @Test
    fun `smoke DashStyle inspections and annotator classes must be loadable in IDE sandbox`() {
        // ---- A) shortName 是否都在 profile 里（证明 plugin.xml <localInspection> 没冲突且被沙箱读到） ----
        val expectedShortNames = listOf(
            "DashStyle.UnusedCssClass.Css",
            "DashStyle.UnusedCssClass.Scss",
            "DashStyle.UnusedCssClass.Less",
            "DashStyle.UnusedCssClass.Any",
            "DashStyle.DuplicateCss.Css",
            "DashStyle.DuplicateCss.Scss",
            "DashStyle.DuplicateCss.Less",
            "DashStyle.DuplicateCss.Any"
        )
        ApplicationManager.getApplication().runReadAction {
            val profile: Any? = runCatching {
                // com.intellij.codeInspection.InspectionProfileManager 在不同 WS 版本里包路径
                // 可能在 internal / analysis-impl 里；直接按类名字符串反射 getInstance(project).currentProfile
                val mgrCls = (Thread.currentThread().contextClassLoader ?: javaClass.classLoader)
                    .loadClass("com.intellij.codeInspection.InspectionProfileManager")
                val getInstance = mgrCls.methods.firstOrNull { m ->
                    m.name == "getInstance" && m.parameterCount == 1 &&
                        runCatching { m.parameterTypes[0] == Project::class.java }.getOrDefault(false)
                } ?: mgrCls.methods.firstOrNull { m -> m.name == "getInstance" && m.parameterCount == 0 }
                getInstance?.isAccessible = true
                val mgr = getInstance?.invoke(null, project) ?: getInstance?.invoke(null)
                val curProfile = mgrCls.methods.firstOrNull { it.name == "getCurrentProfile" && it.parameterCount == 0 }
                    ?.apply { isAccessible = true }?.invoke(mgr)
                curProfile
            }.getOrNull()
            val registeredShortNames = HashSet<String>()
            if (profile != null) {
                runCatching {
                    val method = profile.javaClass.methods.firstOrNull { m ->
                        (m.name == "getInspectionTools" || m.name == "getAllInspectionTools") &&
                            m.parameterTypes.size == 1 && m.parameterTypes[0].isAssignableFrom(Project::class.java)
                    }
                    method?.isAccessible = true
                    val tools = method?.invoke(profile, project) as? Iterable<*> ?: emptyList<Any>()
                    for (t in tools) {
                        val sn = t?.javaClass?.methods?.firstOrNull { it.name == "getShortName" && it.parameterCount == 0 }
                            ?.apply { isAccessible = true }?.invoke(t)?.toString() ?: continue
                        registeredShortNames += sn
                    }
                }
            }
            for (sn in expectedShortNames) {
                Assert.assertTrue(
                    "plugin.xml 注册的 shortName $sn 没在沙箱 InspectionProfile 里注册（实际：$registeredShortNames）",
                    sn in registeredShortNames
                )
            }
        }

        // ---- B) 关键类能不能被沙箱 ClassLoader 实例化（你之前报的 Cannot create class 就是这一关过不去） ----
        val mustLoad = listOf(
            "com.pan.dashstyle.DashStyleHighlightAnnotator",
            "com.pan.dashstyle.StaticGlobalHighlightVisitor",
            "com.pan.dashstyle.CssPreprocessorTranspileIntention",
            "com.pan.dashstyle.UnusedCssModuleClassInspection",
            "com.pan.dashstyle.DuplicateCssDeclarationsInspection",
            "com.pan.dashstyle.DashStyleDocumentationProvider",
            "com.pan.dashstyle.InlineStyleToCssModuleIntention",
            "com.pan.dashstyle.ExtractColorsAction"
        )
        val cl = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        for (cn in mustLoad) {
            val cls = runCatching { Class.forName(cn, true, cl) }.getOrNull()
            Assert.assertNotNull("关键类 $cn 无法加载，真实环境里大概率也会报 Cannot create class", cls)
            // 无参构造实例化（IntentionAction / Inspection / Annotator / HighlightVisitor 都是要求无参构造的）
            val hasNoArgCtor = cls!!.declaredConstructors.any { it.parameterCount == 0 }
            if (hasNoArgCtor) {
                runCatching {
                    cls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
                }.onFailure { t ->
                    Assert.fail("关键类 $cn 能 load 但无参构造失败，真实环境 100% 会报 Cannot create class：${t.message}")
                }
            }
        }

        // ---- C) DuplicateCssDeclarationsInspection 的 companion normalize 能跑（签名和我们静态绑的一致） ----
        val cssFile = myFixture.configureByText("t.css", ".a{padding:0; margin: 0;}")
        val firstDecl: CssDeclaration? = ApplicationManager.getApplication().runReadAction<CssDeclaration?> {
            val ruleset = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(cssFile, CssRuleset::class.java)
                .firstOrNull()
            val decls = ruleset?.block
                ?.let { b ->
                    com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(b, CssDeclaration::class.java).toList()
                }
            decls?.firstOrNull()
        }
        val sigSameOk = runCatching {
            DuplicateCssDeclarationsInspection.normalizeSignatureStatic(listOfNotNull(firstDecl))
        }.isSuccess // 不崩就行
        Assert.assertTrue("DuplicateCss normalizer 静态绑定签名匹配", sigSameOk)

        // ---- D) UnusedCssModuleClassInspection.shortName 在沙箱里返回 XML 注册的那个（不是 Kotlin 默认类名） ----
        val unusedShort = UnusedCssModuleClassInspection().shortName
        Assert.assertTrue(
            "UnusedCssModuleClassInspection.shortName=$unusedShort 应该是 plugin.xml 注册的 DashStyle.UnusedCssClass.* 其中之一",
            unusedShort in expectedShortNames
        )
    }
}
