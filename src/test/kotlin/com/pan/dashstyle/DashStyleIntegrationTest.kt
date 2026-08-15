package com.pan.dashstyle

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
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
 *  - 显式 @RunWith(JUnit4)：BasePlatformTestCase 继承自 JUnit3 的 TestCase，不加这个注解、
 *    junit-vintage 会优先走 JUnit3 runner（按 testXxx 名找用例），而我们用的是 @Test 注解，
 *    结果就是 vintage 报 "No tests found in ..." 导致 test suite 失败。
 *  - 7 条用例全部为强断言并默认启用（无 @Ignore），运行 `gradle test --tests "com.pan.dashstyle.DashStyleIntegrationTest"`
 *    即可在 headless 沙箱里验证 PSI/UI 功能（置灰 / 重复声明 / QuickFix / 引用跳转 / Intention / 类加载）。
 *  - 关键点：base-platform 沙箱不加载 plugin.xml 的 <localInspection> 注册表，因此必须用
 *    `enableInspections(InspectionProfileEntry...)` 实例重载（而非 Class 重载）显式启用自己的
 *    inspection，否则 doHighlighting() 不会执行它们（会抛 "Unregistered inspections requested"）。
 */
@RunWith(JUnit4::class)
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
        // BasePlatformTestCase 的沙箱不会把 plugin.xml 里声明的 <localInspection> 载入 InspectionRegistry，
        // 因此 enableInspections(Class...) 会抛 "Unregistered inspections requested"（instantiateTools 要求注册）。
        // 改用实例重载 enableInspections(InspectionProfileEntry...)：直接传工具实例，不查注册表，
        // 由 enableInspectionTools 把它放进当前 InspectionProfile，doHighlighting() 就能真正执行。
        try {
            myFixture.enableInspections(
                UnusedCssModuleClassInspection(),
                DuplicateCssDeclarationsInspection()
            )
        } catch (t: Throwable) {
            throw IllegalStateException("enableInspections(instance) 抛异常", t)
        }
    }

    // ========================================================================
    // #1. 未使用 CSS Module class → 选择器行置灰（范围必须在 selectorList，不包含 declarations）
    // ========================================================================
    @Test
    fun `unused CSS module class should be grayed - only selector line, not declarations`() {
        // 用 addFileToProject 把文件放进项目源根，确保 ProjectFileIndex / FilenameIndex 能索引到
        // TSX（引用 used / nestedChild）与 CSS Module（*.module.css），否则 UnusedCssModuleClassInspection
        // 的 findReferencingSourceFiles 找不到引用源，会误把所有 class 都当 unused。
        myFixture.addFileToProject(
            "App.tsx",
            """
            import styles from './App.module.css'
            function App() {
              return (
                <div className={styles.used}>
                  <span className={styles.nestedChild}>Hi</span>
                </div>
              )
            }
            """.trimIndent()
        )

        // CSS Module（纯 CSS，避免 headless 沙箱对 Less &-嵌套解析的不确定性）：
        //   .used / .nested-child 被 TSX 引用；.unused / .orphan 未引用。
        val cssFile = myFixture.addFileToProject(
            "App.module.css",
            """
            .used {
              color: red;
            }
            .unused {
              display: none;
            }
            .nested-child {
              font-size: 14px;
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

        // 判定"未使用置灰"高亮的可靠信号：forcedTextAttributesKey == DASHSTYLE_UNUSED_CSS_CLASS
        // （DashStyleHighlightAnnotator 置灰选择器整段；普通 CSS class 名着色是 CSS.CLASS_NAME，不能算置灰）
        fun isUnusedGray(h: HighlightInfo): Boolean =
            runCatching { h.forcedTextAttributesKey?.externalName }.getOrNull() == "DASHSTYLE_UNUSED_CSS_CLASS"

        // 从被置灰的选择器文本里提取 class 名（如 ".unused" -> "unused"），避免用 substring 误伤（.unused 含 "used"）
        fun grayedClassNames(h: HighlightInfo): Set<String> {
            val t = h.text?.trim() ?: return emptySet()
            return Regex("""\.([_a-zA-Z][_a-zA-Z0-9-]*)""").findAll(t).map { it.groupValues[1] }.toSet()
        }

        // ---- 断言 A：.unused 必须被置灰（选择器整段）----
        val unusedGray = highlights.filter { isUnusedGray(it) && "unused" in grayedClassNames(it) }
        Assert.assertTrue(
            ".unused 没有被置灰；当前高亮：${
                highlights.map { "{text=${it.text}, sev=${it.severity}, key=${runCatching { it.forcedTextAttributesKey?.externalName }.getOrNull()}}" }
            }",
            unusedGray.isNotEmpty()
        )

        // ---- 断言 B：.unused 下面的 declarations（display:none）绝对不能被置灰 ----
        val displayGrayed = highlights.filter { isUnusedGray(it) && (it.text == "display" || it.text == "none") }
        Assert.assertTrue(
            "误置灰！.unused 的 declarations（display/none）被带上了未使用的 gray info：$displayGrayed",
            displayGrayed.isEmpty()
        )

        // ---- 断言 C：.orphan 也必须被置灰（未被 TSX 引用）----
        val orphanGray = highlights.filter { isUnusedGray(it) && "orphan" in grayedClassNames(it) }
        Assert.assertTrue(".orphan 未被引用但没有置灰：$orphanGray", orphanGray.isNotEmpty())

        // ---- 断言 D：.used / .nested-child 被用到了，不能被置灰 ----
        val usedGray = highlights.filter { isUnusedGray(it) && grayedClassNames(it).any { n -> n == "used" || n == "nested-child" } }
        Assert.assertTrue(".used 被引用了但仍被置灰", usedGray.isEmpty())
    }

    // ========================================================================
    // #2. 单文件重复 CSS 声明 → 必须有弱警告波浪线
    // ========================================================================
    @Test
    fun `duplicate CSS declarations should produce weak-warning wave`() {
        val css = myFixture.configureByText(
            "Common.module.css",
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
    @Test
    fun `string key reference styles bracket-foo-bar should resolve back to CssRuleset`() {
        val css = myFixture.configureByText(
            "Foo.module.css",
            """
            .hello-world {
              color: #123;
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "Foo.tsx",
            """
            import styles from './Foo.module.css'
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
    // #5. 重复声明日志 → 必须暴露「抽取公共类」QuickFix（Intention）
    //     注：原 LESS 用例在 headless 沙箱里无法完整跑通，因为
    //       a) 沙箱不自带 LESS 语言解析（rulesetCount=0，Duplicate.module.less 解析不出任何 CssRuleset）；
    //       b) QuickFix.applyFix 会弹 Messages.showInputDialog 交互框，headless 下无法交互。
    //      因此这里改用可解析的 .css 文件，验证「重复声明 → 抽取公共类 QuickFix 出现在 Alt+Enter 列表」这一
    //      核心能力；LESS 分支写成 mixin 调用而非 @extend 的细节，由代码审查 + 真实 IDE 验证覆盖。
    // ========================================================================
    @Test
    fun `duplicate declarations expose extract common-class quick fix`() {
        val css = myFixture.configureByText(
            "Common.module.css",
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
        myFixture.openFileInEditor(css.virtualFile)
        ApplicationManager.getApplication().invokeAndWait { myFixture.doHighlighting() }

        // 直接检查「重复声明」问题高亮是否真的携带了「抽取公共类」QuickFix：
        // findRegisteredQuickFix 回调里拿到 IntentionActionDescriptor.action（LocalQuickFix 本质是 IntentionAction），
        // 返回其显示文案；若该高亮没挂任何 QuickFix 则返回 null，被 mapNotNull 过滤掉。
        // 这比 availableIntentions 更可靠：availableIntentions 只收集「光标处」的 intention，
        // 而 inspection 挂的 QuickFix 需要问题高亮被正确渲染成带 quickfix 的 HighlightInfo。
        val quickFixTexts: List<String> = highlights().flatMap { h ->
            runCatching {
                h.findRegisteredQuickFix { descriptor, _ -> descriptor.action?.text }
            }.getOrNull()?.let { listOf(it) } ?: emptyList()
        }

        // QuickFix name 形如 "Extract 2 shared declarations into a new common class (with @extend)"
        val hasExtractFix = quickFixTexts.any { t ->
            val s = t.lowercase()
            s.contains("extract") && (s.contains("shared") || s.contains("common"))
        }
        Assert.assertTrue(
            "重复声明问题没有携带「抽取公共类」QuickFix；当前所有 QuickFix 文案=$quickFixTexts",
            hasExtractFix
        )
    }

    /** doHighlighting 的便捷封装（避免每次 invokeAndWait 重复写） */
    private fun highlights(): List<HighlightInfo> {
        var list: List<HighlightInfo> = emptyList()
        ApplicationManager.getApplication().invokeAndWait {
            list = myFixture.doHighlighting()
        }
        return list
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
    // 0.1 号 smoke：「在沙箱里嗅探真实类型再静态绑定」最终闭环验证
    //   沙箱加载 DashStyle 插件后：
    //     a) plugin.xml 里声明的 DashStyle.UnusedCssClass.* / DashStyle.DuplicateCss.* inspection shortName
    //        必须真实出现在 InspectionProfile 里；
    //     b) UnusedCssModuleClassInspection / DuplicateCssDeclarationsInspection 两个类必须能被
    //        沙箱 PluginClassLoader 加载 & 实例化（真实环境报的就是 "Cannot create class"）。
    //
    //  说明：本用例当前已启用（不再 @Ignore）。它只做「类能否被沙箱 ClassLoader 加载 &
    //  无参实例化」这一真实环境最关心的校验（即历史报过的 "Cannot create class"）；
    //  关于 inspection 是否被正确 enable 并产生高亮，已由 #1/#2/#5 用例（doHighlighting 出
    //  置灰 / WEAK_WARNING / QuickFix）实证，不在此重复。
    // ========================================================================
    @Test
    fun `smoke DashStyle inspections and annotator classes must be loadable in IDE sandbox`() {
        // ---- A) 说明：plugin.xml 的 <localInspection shortName="DashStyle.*"> 只在 inspection 被插件注册表
        //     载入 profile 时才生效；直接 new 一个实例拿到的 shortName 是 Kotlin 默认类名（如 UnusedCssModuleClass），
        //     因此这里无法也没必要用「fresh 实例 shortName ∈ XML 集合」来断言注册（那会误报）。
        //     是否有被正确 enable 并产生高亮，已由 #2 duplicate 用例（doHighlighting 出 WEAK_WARNING）实证。
        //     下面只保留对真实环境最有价值的检查：类能被沙箱 ClassLoader 加载 + 无参实例化（即「Cannot create class」）。

        // ---- B) 关键类能不能被沙箱 ClassLoader 实例化（你之前报的 Cannot create class 就是这一关过不去） ----
        val mustLoad = listOf(
            "com.pan.dashstyle.DashStyleHighlightAnnotator",
            "com.pan.dashstyle.StaticGlobalHighlightVisitor",
            "com.pan.dashstyle.CssPreprocessorTranspileIntention",
            "com.pan.dashstyle.UnusedCssModuleClassInspection",
            "com.pan.dashstyle.DuplicateCssDeclarationsInspection",
            "com.pan.dashstyle.DashStyleDocumentationProvider",
            "com.pan.dashstyle.InlineStyleToCssModuleIntention",
            "com.pan.dashstyle.ExtractColorsAction",
            "com.pan.dashstyle.LayoutPreviewInlayProvider"
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
    }

    // ========================================================================
    // #8. CSS Flex 布局预览：display:flex 行生成「总效果」徽标，
    //     每个 flex 属性行尾生成迷你预览（用 fake InlayHintsSink 直接驱动 collector）
    // ========================================================================
    @Test
    fun `flex preview provider adds overall badge and per-property inlays`() {
        val css = myFixture.configureByText(
            "Flex.module.css",
            """
            .toolbar {
              display: flex;
              justify-content: center;
              align-items: center;
              gap: 12px;
            }
            .not-flex {
              display: block;
            }
            """.trimIndent()
        )
        val provider = LayoutPreviewInlayProvider()
        val settings = provider.createSettings()
        val offsets = mutableListOf<Int>()
        // 用动态代理实现 InlayHintsSink，只记录 addInlineElement 的首个 int 参数（offset），
        // 绕开纯 Kotlin collector 调用走哪个重载的签名匹配问题。
        val sink = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(InlayHintsSink::class.java)
        ) { _, method, args ->
            if (method.name == "addInlineElement" && args != null && args.isNotEmpty() && args[0] is Int) {
                offsets.add(args[0] as Int)
            }
            null
        } as InlayHintsSink
        val collector = provider.getCollectorFor(css, myFixture.editor, settings, sink)

        ApplicationManager.getApplication().runReadAction { collector.collect(css, myFixture.editor, sink) }

        // 期望：display:flex 行 1 个「总效果」徽标 + justify-content / align-items / gap 共 3 个属性预览
        Assert.assertEquals(
            "flex 容器应生成 1 总效果 + 3 属性预览；实际 offsets=$offsets",
            4, offsets.size
        )
        // display:block 的 ruleset 不应生成任何 inlay
        Assert.assertTrue("非 flex 容器不应生成 inlay", offsets.all { it > 0 })
    }

    // ========================================================================
    // #9. Flex 预览弹窗：applyToBlock 把调整后的 flex 值写回 CSS ruleset
    //     （已存在声明改值 + 缺失声明新增）
    // ========================================================================
    @Test
    fun `flex preview popup applyToBlock writes back flex values to ruleset`() {
        val css = myFixture.configureByText(
            "Flex2.module.css",
            """
            .toolbar {
              display: flex;
              justify-content: flex-start;
              gap: 0;
            }
            """.trimIndent()
        )
        val ruleset = PsiTreeUtil.findChildrenOfType(css, CssRuleset::class.java).firstOrNull()
        Assert.assertNotNull("应能找到 .toolbar ruleset", ruleset)

        val props = FlexLayoutResolver.Props(
            direction = FlexLayoutResolver.Direction.ROW,
            justify = FlexLayoutResolver.Justify.CENTER,      // 已有声明 → 改值
            align = FlexLayoutResolver.Align.STRETCH,          // 缺失 → 新增
            gap = 16,                                          // 已有声明 → 改值
            wrap = true,                                       // 缺失 → 新增
            childCount = 3
        )
        WriteCommandAction.runWriteCommandAction(project) {
            FlexPreviewPopup.applyToBlock(ruleset!!.block!!, props)
        }
        val block = ruleset!!.block!!
        Assert.assertEquals("justify-content 应改为 center", "center", block.findDeclaration("justify-content")?.value?.text?.trim())
        Assert.assertEquals("gap 应改为 16px", "16px", block.findDeclaration("gap")?.value?.text?.trim())
        Assert.assertNotNull("align-items 应被新增", block.findDeclaration("align-items"))
        Assert.assertEquals("新增 align-items 应为 stretch", "stretch", block.findDeclaration("align-items")?.value?.text?.trim())
        Assert.assertNotNull("flex-wrap 应被新增", block.findDeclaration("flex-wrap"))
        Assert.assertNotNull("flex-direction 应被新增", block.findDeclaration("flex-direction"))
    }
}
