package com.pan.dashstyle

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pan.dashstyle.support.CssModuleResolver.CssContainer
import com.intellij.psi.xml.XmlAttribute
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

    private var errorProcessorToken: com.intellij.openapi.application.AccessToken? = null

    override fun setUp() {
        super.setUp()
        // 沙箱装入 Vue 插件后，addFileToProject 写 .ts/.tsx 物理文件会触发 VFS 监听器
        // 初始化 VueLsp 服务，其在测试沙箱布局下必然失败并被记为错误日志，
        // 测试框架会把该日志 rethrow 成 TestLoggerAssertionError —— 统一过滤，见 [VueSandboxNoiseFilter]。
        errorProcessorToken = VueSandboxNoiseFilter.install()
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

    override fun tearDown() {
        try {
            errorProcessorToken?.finish()
        } finally {
            super.tearDown()
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
              border-radius: 4px;
            }
            .b {
              padding: 4px;
              margin: 0;
              border-radius: 4px;
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
            "com.pan.dashstyle.annotator.DashStyleHighlightAnnotator",
            "com.pan.dashstyle.inspection.UnusedCssModuleClassInspection",
            "com.pan.dashstyle.inspection.DuplicateCssDeclarationsInspection",
            "com.pan.dashstyle.annotator.DashStyleDocumentationProvider",
            "com.pan.dashstyle.action.InlineStyleToCssModuleIntention"
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
    // #6. Vue $style 绑定感知扫描：$style.flex / $style['flex'] 通过 PSI resolve 确认
    //     Vue 文件必须有 <style module> 标签，$style 才能正确解析
    // ========================================================================
    @Test
    fun `vue $style access via scanUsages should mark classes as used`() {
        // .xml 替身：覆盖 scanUsages 的模板属性值正则 fallback（.vue.xml 无 JS PSI，
        // 文件名也不是 .vue 结尾，不走 resolveQualifier 的 Vue fallback）；
        // 真实 .vue 的 PSI 路径见 RealVueFileIntegrationTest。
        val xmlFile = myFixture.configureByText(
            "App.vue.xml",
            """
            <style module>
            .flex { display: flex; }
            .flex-item { gap: 8px; }
            .flexItem { color: red; }
            .unused { opacity: 0; }
            </style>
            <template>
              <div :class="${'$'}style.flex">
                <span :class="${'$'}style['flex-item']">Item</span>
              </div>
            </template>
            """.trimIndent()
        )
        val modTag = PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag::class.java)
            .firstOrNull { it.name.equals("style", ignoreCase = true) && it.getAttribute("module") != null }
        Assert.assertNotNull("Vue 文件应有 <style module> 标签", modTag)
        val container = CssContainer.VueStyleTag(modTag!!, "\$style", xmlFile)
        ApplicationManager.getApplication().runReadAction {
            val (used, dynamic) = CssModuleUsageScanner.scanUsages(xmlFile, container)
            Assert.assertFalse("hasDynamic should be false", dynamic)
            Assert.assertTrue("dot access flex should be used", "flex" in used)
            Assert.assertTrue("bracket flex-item should be used", "flex-item" in used)
            Assert.assertFalse("unused should not be in used set", "unused" in used)
        }
    }

    // ========================================================================
    // #7. Vue 动态引用 $style[varName] → hasDynamic 应为 true
    // ========================================================================
    @Test
    fun `vue dynamic $style variable reference should set hasDynamic via scanUsages`() {
        val xmlFile = myFixture.configureByText(
            "App.vue.xml",
            """
            <style module>
            .flex { display: flex; }
            </style>
            <template>
              <div :class="${'$'}style[className]">Hello</div>
            </template>
            """.trimIndent()
        )
        val modTag = PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag::class.java)
            .firstOrNull { it.name.equals("style", ignoreCase = true) && it.getAttribute("module") != null }
        Assert.assertNotNull("Vue 文件应有 <style module> 标签", modTag)
        val container = CssContainer.VueStyleTag(modTag!!, "\$style", xmlFile)
        ApplicationManager.getApplication().runReadAction {
            val (_, dynamic) = CssModuleUsageScanner.scanUsages(xmlFile, container)
            Assert.assertTrue("dynamic var ref should set hasDynamic", dynamic)
        }
    }

    // ========================================================================
    // #8. JSX styles['xxx'] 模式（非 Vue 文件）→ 必须被识别
    // ========================================================================
    @Test
    fun `jsx styles bracket string key should be recognized as used`() {
        val cssFile = myFixture.addFileToProject(
            "App.module.css",
            """
            .hello-world { color: red; }
            .foo-bar { font-size: 14px; }
            .unused { opacity: 0; }
            """.trimIndent()
        )
        val tsxFile = myFixture.addFileToProject(
            "App.tsx",
            """
            import styles from './App.module.css'
            function App() {
              return (
                <div>
                  <span className={styles['hello-world']}>Hello</span>
                  <span className={styles["foo-bar"]}>Foo</span>
                </div>
              )
            }
            """.trimIndent()
        )
        val snap = UnusedCssModuleClassInspection.computeFileSnapshot(
            cssFile, listOf(tsxFile to "styles")
        )
        Assert.assertFalse("hasDynamic 应为 false", snap.hasDynamic)
        Assert.assertTrue("styles['hello-world'] 应被识别", "hello-world" in snap.used)
        Assert.assertTrue("styles[\"foo-bar\"] 应被识别", "foo-bar" in snap.used)
        Assert.assertFalse("unused 不应在 used 中", "unused" in snap.used)
    }

    // ========================================================================
    // #9. 本地变量 shadowing `styles` → 不应被计入 CSS Module 使用
    // ========================================================================
    @Test
    fun `local variable shadowing styles should not be counted as css module usage`() {
        val cssFile = myFixture.addFileToProject(
            "App.module.css",
            """.card { color: red; }""".trimIndent()
        )
        val tsxFile = myFixture.addFileToProject(
            "App.tsx",
            """
            import styles from './App.module.css'
            function Foo() {
                const styles = { card: 'card' }
                return <div className={styles.card} />
            }
            """.trimIndent()
        )
        ApplicationManager.getApplication().runReadAction {
            val container = CssContainer.ImportedFile(cssFile, cssFile.virtualFile!!, "styles", null)
            val (used, dynamic) = CssModuleUsageScanner.scanUsages(tsxFile, container)
            Assert.assertFalse("shadowing 不应导致 hasDynamic", dynamic)
            Assert.assertTrue("shadowing styles.card 不应被计入 CSS Module 使用", "card" !in used)
        }
    }

    // ========================================================================
    // #10. 无关变量 `classes.foo` → 不应被计入 CSS Module 使用
    // ========================================================================
    @Test
    fun `unrelated classes foo should not be counted as css module usage`() {
        val cssFile = myFixture.addFileToProject(
            "App.module.css",
            """.title { color: blue; }""".trimIndent()
        )
        val tsxFile = myFixture.addFileToProject(
            "App.tsx",
            """
            import styles from './App.module.css'
            const classes = { title: 'title' }
            function App() { return <div className={classes.title}></div> }
            """.trimIndent()
        )
        ApplicationManager.getApplication().runReadAction {
            val container = CssContainer.ImportedFile(cssFile, cssFile.virtualFile!!, "styles", null)
            val (used, dynamic) = CssModuleUsageScanner.scanUsages(tsxFile, container)
            Assert.assertFalse("classes 不应有 hasDynamic", dynamic)
            Assert.assertTrue("classes.title 不应被计入 CSS Module", "title" !in used)
        }
    }

    // ========================================================================
    // #11. 无关变量 `css.foo` → 不应被计入 CSS Module 使用
    // ========================================================================
    @Test
    fun `unrelated css foo should not be counted as css module usage`() {
        val cssFile = myFixture.addFileToProject(
            "App.module.css",
            """.header { color: green; }""".trimIndent()
        )
        val tsxFile = myFixture.addFileToProject(
            "App.tsx",
            """
            import styles from './App.module.css'
            const css = { header: 'header' }
            function App() { return <div className={css.header}></div> }
            """.trimIndent()
        )
        ApplicationManager.getApplication().runReadAction {
            val container = CssContainer.ImportedFile(cssFile, cssFile.virtualFile!!, "styles", null)
            val (used, dynamic) = CssModuleUsageScanner.scanUsages(tsxFile, container)
            Assert.assertFalse("css 不应有 hasDynamic", dynamic)
            Assert.assertTrue("css.header 不应被计入 CSS Module", "header" !in used)
        }
    }

    // ========================================================================
    // #12. 无关变量 `styled.foo` → 不应被计入 CSS Module 使用
    // ========================================================================
    @Test
    fun `unrelated styled foo should not be counted as css module usage`() {
        val cssFile = myFixture.addFileToProject(
            "App.module.css",
            """.button { color: green; }""".trimIndent()
        )
        val tsxFile = myFixture.addFileToProject(
            "App.tsx",
            """
            import styles from './App.module.css'
            const styled = { button: 'styled-button' }
            function App() { return <div className={styled.button}></div> }
            """.trimIndent()
        )
        ApplicationManager.getApplication().runReadAction {
            val container = CssContainer.ImportedFile(cssFile, cssFile.virtualFile!!, "styles", null)
            val (used, dynamic) = CssModuleUsageScanner.scanUsages(tsxFile, container)
            Assert.assertFalse("styled 不应有 hasDynamic", dynamic)
            Assert.assertTrue("styled.button 不应被计入 CSS Module", "button" !in used)
        }
    }

    // ========================================================================
    // #13. Vue 无关变量 $foo.bar → 不应被计入 CSS Module 使用
    // ========================================================================
    @Test
    fun `vue unrelated $foo bar should not be counted as css module usage`() {
        val xmlFile = myFixture.configureByText(
            "App.vue.xml",
            """
            <style module>
            .bar { color: red; }
            </style>
            <template>
              <div :class="${'$'}notstyle.bar">Unrelated</div>
            </template>
            """.trimIndent()
        )
        val modTag = PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag::class.java)
            .firstOrNull { it.name.equals("style", ignoreCase = true) && it.getAttribute("module") != null }
        Assert.assertNotNull("Vue 文件应有 <style module> 标签", modTag)
        val container = CssContainer.VueStyleTag(modTag!!, "\$style", xmlFile)
        ApplicationManager.getApplication().runReadAction {
            val (used, dynamic) = CssModuleUsageScanner.scanUsages(xmlFile, container)
            Assert.assertFalse("\${'$'}notstyle.bar 不应有 hasDynamic", dynamic)
            Assert.assertTrue("\${'$'}notstyle.bar 不应被计入 CSS Module", "bar" !in used)
        }
    }

    // ========================================================================
    // #14. 真实 CSS Module 使用（styles.foo）在同文件中应被正确识别
    // ========================================================================
    @Test
    fun `real css module usage in same file should be detected`() {
        val cssFile = myFixture.addFileToProject(
            "App.module.css",
            """.used { color: red; }""".trimIndent()
        )
        val tsxFile = myFixture.addFileToProject(
            "App.tsx",
            """
            import styles from './App.module.css'
            function App() { return <div className={styles.used}></div> }
            """.trimIndent()
        )
        ApplicationManager.getApplication().runReadAction {
            val container = CssContainer.ImportedFile(cssFile, cssFile.virtualFile!!, "styles", null)
            val (used, dynamic) = CssModuleUsageScanner.scanUsages(tsxFile, container)
            Assert.assertFalse("real usage hasDynamic 应为 false", dynamic)
            Assert.assertTrue("styles.used 应被识别为 CSS Module 使用", "used" in used)
        }
    }

    // ========================================================================
    // #15. 混合场景：真实使用 + 无关变量 + shadowing 在同一文件
    // ========================================================================
    @Test
    fun `mixed real usage and shadowing and unrelated variables in same file`() {
        val cssFile = myFixture.addFileToProject(
            "App.module.css",
            """
            .real { color: red; }
            .shadowed-card { color: blue; }
            """.trimIndent()
        )
        val tsxFile = myFixture.addFileToProject(
            "App.tsx",
            """
            import styles from './App.module.css'
            // 无关变量
            const classes = { real: 'local-real' }
            const css = { shadowedCard: 'local-shadowed' }
            function Foo() {
                // 本地 shadowing
                const styles = { real: 'shadowed' }
                return <div className={styles.real}></div>
            }
            // 真实使用
            function Bar() {
                return <div className={styles.shadowedCard}></div>
            }
            """.trimIndent()
        )
        ApplicationManager.getApplication().runReadAction {
            val container = CssContainer.ImportedFile(cssFile, cssFile.virtualFile!!, "styles", null)
            val (used, dynamic) = CssModuleUsageScanner.scanUsages(tsxFile, container)
            Assert.assertFalse("mixed 场景 hasDynamic 应为 false", dynamic)
            // real 在 Foo() 内被 local const styles 遮蔽，不应计入
            Assert.assertTrue("Foo 内 styles.real 被遮蔽，不应计入 CSS Module", "real" !in used)
            // shadowedCard 在 Bar() 内使用 import styles，应计入
            Assert.assertTrue("Bar 内 styles.shadowedCard 应被计入 CSS Module", "shadowed-card" in used)
        }
    }

    // ========================================================================
    // #16. PSI import 插入格式验证：ensureImportExists 追加 import 后，
    //      新 import 必须与已有 import / 后续代码之间有换行分隔（不能粘连成
    //      "import areactimport styles" 这样的坏语法）。
    // ========================================================================
    @Test
    fun `PSI import insertion keeps newline separators`() {
        val cssVf = myFixture.addFileToProject("Other.module.css", ".foo { color: red; }\n").virtualFile!!
        val tsx = myFixture.addFileToProject(
            "App16.tsx",
            """
            import react from 'react'
            const x = 1
            """.trimIndent() + "\n"
        )
        val binding = CssModuleFileResolver.ensureImportExists(project, tsx, cssVf)
        Assert.assertEquals("styles", binding)
        val text = tsx.text
        println("=== #16 resulting App16.tsx ===")
        println(text)
        Assert.assertFalse(
            "新 import 与已有 import 粘连（缺少换行分隔）: ${text.replace("\n", "\\n")}",
            text.contains("reactimport") || text.contains("import stylesfrom")
        )
        Assert.assertTrue(
            "新 import 应出现在独立一行: ${text.replace("\n", "\\n")}",
            Regex("""^import styles from ['"].*['"]$""", RegexOption.MULTILINE).containsMatchIn(text)
        )
        // 原有代码不能被破坏
        Assert.assertTrue("原有 const x = 1 应保留", text.contains("const x = 1"))
        Assert.assertTrue("原有 react import 应保留", text.contains("import react from 'react'"))
    }

    // ========================================================================
    // #17. RemoveRuleQuickFix 空白折叠验证：删除规则后多余的空行应被折叠，
    //      且全程纯 PSI 写入（Document 与 PSI 文本必须一致 —— 原子性）。
    // ========================================================================
    @Test
    fun `remove rule quick fix folds whitespace via pure PSI`() {
        val cssFile = myFixture.addFileToProject(
            "styles17.module.css",
            ".used { color: red; }\n\n.unused { color: blue; }\n\n.other { color: green; }\n"
        )
        val unusedRule = PsiTreeUtil.findChildrenOfType(cssFile, CssRuleset::class.java)
            .firstOrNull { it.text.startsWith(".unused") }
        Assert.assertNotNull("找不到 .unused 规则", unusedRule)
        val selectorList = unusedRule!!.selectorList
        Assert.assertNotNull(".unused 规则没有 selectorList", selectorList)

        val quickFix = UnusedCssModuleClassInspection.RemoveRuleQuickFix("unused")
        val descriptor = com.intellij.codeInspection.InspectionManager.getInstance(project)
            .createProblemDescriptor(
                selectorList!!,
                "unused",
                quickFix,
                com.intellij.codeInspection.ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                /* isOnTheFly */ true
            )
        WriteCommandAction.runWriteCommandAction(project) {
            quickFix.applyFix(project, descriptor)
        }

        val text = cssFile.text
        println("=== #17 resulting styles17.module.css ===")
        println(text)
        Assert.assertFalse(".unused 规则应被删除: ${text.replace("\n", "\\n")}", ".unused" in text)
        Assert.assertTrue(".used 规则应保留", text.contains(".used { color: red; }"))
        Assert.assertTrue(".other 规则应保留", text.contains(".other { color: green; }"))
        Assert.assertFalse(
            "被删规则留下的多余空行应被折叠（不应出现 3 个以上连续换行）: ${text.replace("\n", "\\n")}",
            Regex("\n{4,}").containsMatchIn(text)
        )
        // 原子性：纯 PSI 写入后 Document 与 PSI 文本必须一致
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        val docText = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(cssFile)?.text
        Assert.assertEquals("Document 与 PSI 文本应一致（原子写入）", text, docText)
    }

    // ========================================================================
    // #18. JSX 属性 PSI 整体替换 + 兄弟节点删除（Convert Action / Inline Style
    //      Intention 重构后的核心写入机制）：
    //      a) className="foo" --replace--> className={clsx("foo", styles.bar)}
    //      b) 同一 WriteAction 内删除兄弟 style 属性 + 前置空白
    //      c) Document 与 PSI 文本一致（原子性）
    // ========================================================================
    @Test
    fun `jsx className attribute replace and sibling style delete via pure PSI`() {
        val tsx = myFixture.addFileToProject(
            "App18.tsx",
            """const A = () => (
  <div style={{ color: 'red' }} className="foo">hi</div>
)
"""
        )
        // 与 ConvertClassNameToCssModuleAction.findOwningClassNameAttribute 同款类型化查找
        val classNameAttr = PsiTreeUtil.findChildrenOfType(tsx, XmlAttribute::class.java)
            .firstOrNull { it.name == "className" }
        Assert.assertNotNull("找不到 className 属性节点（XmlAttribute 类型化查找）", classNameAttr)
        val styleAttr = PsiTreeUtil.findChildrenOfType(tsx, XmlAttribute::class.java)
            .firstOrNull { it.name == "style" }
        Assert.assertNotNull("找不到 style 属性节点（XmlAttribute 类型化查找）", styleAttr)
        val newAttr = CssModuleFileResolver.createJsxAttributePsi(
            project, tsx, """className={clsx("foo", styles.bar)}"""
        )
        Assert.assertNotNull("createJsxAttributePsi 应能创建 className={clsx(...)} 节点", newAttr)

        WriteCommandAction.runWriteCommandAction(project) {
            classNameAttr!!.replace(newAttr!!)
            val prevWs = styleAttr!!.prevSibling
            if (prevWs is com.intellij.psi.PsiWhiteSpace && !prevWs.textContains('\n')) prevWs.delete()
            styleAttr!!.delete()
        }

        val text = tsx.text
        println("=== #18 resulting App18.tsx ===")
        println(text)
        Assert.assertTrue(
            "className 应被整体替换: ${text.replace("\n", "\\n")}",
            text.contains("""className={clsx("foo", styles.bar)}""")
        )
        Assert.assertFalse("style 属性应被删除", text.contains("style="))
        Assert.assertFalse("旧字符串值不应残留", text.contains("\"foo\">"))
        Assert.assertFalse("不应产生双空格属性", Regex("  +className").containsMatchIn(text.replace("\n", " ")))
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        val docText = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(tsx)?.text
        Assert.assertEquals("Document 与 PSI 文本应一致（原子写入）", text, docText)
    }

    // ========================================================================
    // #19. XmlAttribute PSI 替换机制（Inline Style Intention Vue 分支同款）：
    //      沙箱现已装 Vue 插件（.vue → VueFileImpl），真实 .vue 上的整链路
    //      （handleVueTemplateReplacement）见 RealVueFileIntegrationTest；
    //      本用例保留 .xml fixture，验证 XmlAttribute dummy 工厂 + replace
    //      的底层机制（与文件语言无关）。
    //      :style="{...}" --replace--> :class="$style.card"
    // ========================================================================
    @Test
    fun `xml style attribute replaced via xml attribute psi`() {
        val xml = myFixture.addFileToProject(
            "data19.xml",
            "<root><div :style=\"{ color: 'red' }\">hi</div></root>"
        )
        val styleAttr = PsiTreeUtil.findChildrenOfType(xml, XmlAttribute::class.java)
            .firstOrNull { it.name.endsWith("style") }
        Assert.assertNotNull("找不到 :style 属性节点", styleAttr)
        val newAttr = CssModuleFileResolver.createXmlAttributePsi(project, ":class=\"\$style.card\"")
        Assert.assertNotNull("createXmlAttributePsi 应能创建 :class 节点", newAttr)

        WriteCommandAction.runWriteCommandAction(project) { styleAttr!!.replace(newAttr!!) }

        val text = xml.text
        println("=== #19 resulting data19.xml ===")
        println(text)
        Assert.assertTrue(":class 属性应出现: ${text.replace("\n", "\\n")}", text.contains(":class=\"\$style.card\""))
        Assert.assertFalse(":style 属性应被删除", text.contains(":style="))
        Assert.assertTrue("root 结构应保留", text.contains("<root>") && text.contains("</root>"))
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        val docText = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(xml)?.text
        Assert.assertEquals("Document 与 PSI 文本应一致（原子写入）", text, docText)
    }

    // ========================================================================
    // #20. 框架探测：区分 Vue 的 TSX / React 的 TSX ——
    //      文件内 import 证据优先（vue 系 / react 系），都没有则向上找
    //      package.json 按 dependencies 判断；.vue 文件直接判 VUE。
    // ========================================================================
    @Test
    fun `framework detection distinguishes vue tsx react tsx and package json`() {
        val reactTsx = myFixture.addFileToProject(
            "R20.tsx",
            "import { useState } from 'react'\nexport const A = () => <div style={{color:'red'}}>x</div>\n"
        )
        Assert.assertEquals(
            "from 'react' 应判定 REACT",
            InlineStyleToCssModuleIntention.Framework.REACT,
            InlineStyleToCssModuleIntention.detectFramework(reactTsx)
        )

        val vueTsx = myFixture.addFileToProject(
            "V20.tsx",
            "import { defineComponent } from 'vue'\nexport default defineComponent({ setup: () => () => <div style={{color:'red'}}>x</div> })\n"
        )
        Assert.assertEquals(
            "from 'vue' 应判定 VUE",
            InlineStyleToCssModuleIntention.Framework.VUE,
            InlineStyleToCssModuleIntention.detectFramework(vueTsx)
        )

        // 无文件证据 → package.json 兜底
        myFixture.addFileToProject("pkgVue/package.json", """{"name":"a","dependencies":{"vue":"^3.4.0"}}""")
        val plainVue = myFixture.addFileToProject(
            "pkgVue/P20.tsx", "export const A = () => <div style={{color:'red'}}>x</div>\n"
        )
        Assert.assertEquals(
            "package.json 只有 vue 应判定 VUE",
            InlineStyleToCssModuleIntention.Framework.VUE,
            InlineStyleToCssModuleIntention.detectFramework(plainVue)
        )

        myFixture.addFileToProject("pkgReact/package.json", """{"name":"b","dependencies":{"react":"^18.2.0","react-dom":"^18.2.0"}}""")
        val plainReact = myFixture.addFileToProject(
            "pkgReact/P21.tsx", "export const A = () => <div style={{color:'red'}}>x</div>\n"
        )
        Assert.assertEquals(
            "package.json 只有 react 应判定 REACT",
            InlineStyleToCssModuleIntention.Framework.REACT,
            InlineStyleToCssModuleIntention.detectFramework(plainReact)
        )

        val vueFile = myFixture.addFileToProject(
            "S20.vue", "<template><div :style=\"{color:'red'}\">x</div></template>"
        )
        Assert.assertEquals(
            ".vue 文件应直接判定 VUE",
            InlineStyleToCssModuleIntention.Framework.VUE,
            InlineStyleToCssModuleIntention.detectFramework(vueFile)
        )
    }

    // ========================================================================
    // #21. React TSX：已有 className 的合并 ——
    //      a) findClassAttr 必须能找到已有 className（e4x 类型兼容，旧实现只搜
    //         JSAttributeNameValuePair 导致 merge 永不触发、生成重复 className）；
    //      b) 未安装 clsx → 模板字符串合并（不生成 clsx 调用）；
    //      c) style 属性删除、无双 className。
    // ========================================================================
    @Test
    fun `react inline style merge into existing className uses template literal`() {
        val tsx = myFixture.addFileToProject(
            "M21.tsx",
            "import { useState } from 'react'\nconst A = () => (\n  <div style={{ color: 'red' }} className=\"foo\">hi</div>\n)\n"
        )
        val intention = InlineStyleToCssModuleIntention()
        val styleAttr = PsiTreeUtil.findChildrenOfType(tsx, XmlAttribute::class.java)
            .firstOrNull { it.name == "style" }
        Assert.assertNotNull("找不到 style 属性", styleAttr)

        val found = intention.findClassAttr(styleAttr!!.parent, "className")
        Assert.assertNotNull("findClassAttr 应找到已有 className（e4x 兼容，旧实现的 bug）", found)

        WriteCommandAction.runWriteCommandAction(project) {
            intention.mergeIntoExistingClass(
                found!!, "styles.bar", styleAttr, InlineStyleToCssModuleIntention.Framework.REACT
            )
        }

        val text = tsx.text
        println("=== #21 resulting M21.tsx ===")
        println(text)
        Assert.assertTrue(
            "无 clsx 时应合并为模板字符串: ${text.replace("\n", "\\n")}",
            text.contains("className={`foo \${styles.bar}`}")
        )
        Assert.assertFalse("style 属性应删除", text.contains("style="))
        Assert.assertFalse("未安装 clsx 不应生成 clsx 调用", text.contains("clsx"))
        Assert.assertEquals("className 只应出现一次（不能有重复属性）", 1, Regex("className=").findAll(text).count())
    }

    // ========================================================================
    // #22. Vue TSX：class（Vue JSX 惯例）而非 className ——
    //      a) 已有 class → 数组语法合并 class={[old, new]}；
    //      b) 无已有 class → handleJsxReplacement 端到端：style 替换为 class={styles.card}。
    // ========================================================================
    @Test
    fun `vue tsx inline style uses class attribute and array merge`() {
        val tsx = myFixture.addFileToProject(
            "M22.tsx",
            "import { defineComponent } from 'vue'\nconst A = () => (\n  <div style={{ color: 'red' }} class=\"foo\">hi</div>\n)\n"
        )
        val intention = InlineStyleToCssModuleIntention()
        val styleAttr = PsiTreeUtil.findChildrenOfType(tsx, XmlAttribute::class.java)
            .firstOrNull { it.name == "style" }
        Assert.assertNotNull(styleAttr)

        val classAttr = intention.findClassAttr(styleAttr!!.parent, "class")
        Assert.assertNotNull("findClassAttr 应找到已有 class（Vue JSX 惯例）", classAttr)

        WriteCommandAction.runWriteCommandAction(project) {
            intention.mergeIntoExistingClass(
                classAttr!!, "styles.bar", styleAttr, InlineStyleToCssModuleIntention.Framework.VUE
            )
        }
        val text = tsx.text
        println("=== #22 merge resulting M22.tsx ===")
        println(text)
        Assert.assertTrue(
            "Vue JSX 应合并为数组语法: ${text.replace("\n", "\\n")}",
            text.contains("""class={["foo", styles.bar]}""")
        )
        Assert.assertFalse("style 属性应删除", text.contains("style="))

        // 端到端：无已有 class 的 Vue tsx → style 整体替换为 class={styles.card}
        val tsx2 = myFixture.addFileToProject(
            "M22b.tsx",
            "import { defineComponent } from 'vue'\nconst B = () => (\n  <div style={{ color: 'red' }}>hi</div>\n)\n"
        )
        val s2 = PsiTreeUtil.findChildrenOfType(tsx2, XmlAttribute::class.java).firstOrNull { it.name == "style" }
        Assert.assertNotNull(s2)
        WriteCommandAction.runWriteCommandAction(project) {
            intention.handleJsxReplacement(
                project, tsx2, s2!!, "styles.card", InlineStyleToCssModuleIntention.Framework.VUE
            )
        }
        val text2 = tsx2.text
        println("=== #22 replace resulting M22b.tsx ===")
        println(text2)
        Assert.assertTrue(
            "Vue tsx 应生成 class= 而非 className=: ${text2.replace("\n", "\\n")}",
            text2.contains("class={styles.card}")
        )
        Assert.assertFalse("不应生成 React 的 className", text2.contains("className"))
        Assert.assertFalse("style 属性应删除", text2.contains("style="))
    }

    // ========================================================================
    // #23. Framework 探测缓存：CachedValue 避免每次 Alt+Enter 都做 20 层
    //      package.json IO；package.json 内容修改必须正确失效缓存。
    // ========================================================================
    @Test
    fun `framework detection cached and invalidated on package json change`() {
        val pkg = myFixture.addFileToProject(
            "pkgC/package.json", """{"name":"c","dependencies":{"vue":"^3.4.0"}}"""
        )
        val tsx = myFixture.addFileToProject(
            "pkgC/C23.tsx", "export const A = () => <div style={{color:'red'}}>x</div>\n"
        )
        // 第一次探测（package.json 兜底路径，结果进缓存）
        Assert.assertEquals(
            "package.json vue → VUE",
            InlineStyleToCssModuleIntention.Framework.VUE, InlineStyleToCssModuleIntention.detectFramework(tsx)
        )
        // 第二次应命中缓存（结果一致；真实环境中不再触发目录向上扫描 IO）
        Assert.assertEquals(
            "第二次调用应命中缓存",
            InlineStyleToCssModuleIntention.Framework.VUE, InlineStyleToCssModuleIntention.detectFramework(tsx)
        )

        // 修改 package.json 内容 → 缓存必须失效并重算
        // （模拟真实用户编辑：Document.setText + 显式 commit 同步 PSI → tracker 失效缓存。
        //   注意 VfsUtil.saveText 只改 VFS，不会 reload Document，PSI 仍读旧值。）
        val doc = pkg.viewProvider.document
        Assert.assertNotNull("package.json 应有 Document", doc)
        WriteCommandAction.runWriteCommandAction(project) {
            doc!!.setText("""{"name":"c","dependencies":{"react":"^18.2.0"}}""")
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(doc)
        }
        println("after change pkg.text=${pkg.text.take(60)}")
        Assert.assertEquals(
            "package.json 改为 react 后应失效缓存并重算为 REACT",
            InlineStyleToCssModuleIntention.Framework.REACT, InlineStyleToCssModuleIntention.detectFramework(tsx)
        )
    }

    // ===================== #24-#28 公共小工具 =====================

    /** 在 [file] 里找到 style 属性与同标签的 [classAttrName]，执行 merge，返回合并后全文。 */
    private fun mergeStyleIntoClass(
        file: PsiFile,
        classAttrName: String,
        access: String,
        framework: InlineStyleToCssModuleIntention.Framework
    ): String {
        val intention = InlineStyleToCssModuleIntention()
        val styleAttr = PsiTreeUtil.findChildrenOfType(file, XmlAttribute::class.java)
            .firstOrNull { it.name == "style" }
        Assert.assertNotNull("找不到 style 属性", styleAttr)
        val classAttr = intention.findClassAttr(styleAttr!!.parent, classAttrName)
        Assert.assertNotNull("findClassAttr 应找到已有 $classAttrName", classAttr)
        WriteCommandAction.runWriteCommandAction(project) {
            Assert.assertTrue(
                "mergeIntoExistingClass 应成功（失败时不得删 style）",
                intention.mergeIntoExistingClass(classAttr!!, access, styleAttr, framework)
            )
        }
        return file.text
    }

    // ========================================================================
    // #24. clsx/classnames 本地绑定名（A1 修复）——
    //      clsxLocalName 解析矩阵：默认导入 / classnames 默认名 / 命名导入 /
    //      别名导入 / 副作用导入 / namespace 导入 / 近似包名；
    //      merge 生成的调用必须用「本地绑定名」而不是硬编码 clsx
    //      （旧实现 hasClsxImport 匹配 classnames 却生成 clsx(...) → 未定义标识符）。
    // ========================================================================
    @Test
    fun `clsx local binding name is used instead of hardcoded clsx`() {
        fun localName(fileName: String, src: String): String? =
            InlineStyleToCssModuleIntention.clsxLocalName(myFixture.addFileToProject(fileName, src))

        Assert.assertEquals(
            "import cn from 'clsx' 应解析出本地名 cn", "cn",
            localName("C24a.tsx", "import cn from 'clsx'\nexport const A = () => <div>x</div>\n")
        )
        Assert.assertEquals(
            "import classNames from 'classnames' 应解析出 classNames", "classNames",
            localName("C24b.tsx", "import classNames from 'classnames'\nexport const A = () => <div>x</div>\n")
        )
        Assert.assertEquals(
            "import { clsx } from 'clsx' → clsx", "clsx",
            localName("C24c.tsx", "import { clsx } from 'clsx'\nexport const A = () => <div>x</div>\n")
        )
        Assert.assertEquals(
            "import { clsx as c } from 'clsx' → 别名 c", "c",
            localName("C24d.tsx", "import { clsx as c } from 'clsx'\nexport const A = () => <div>x</div>\n")
        )
        Assert.assertNull(
            "副作用导入 import 'clsx' 不是可调用绑定",
            localName("C24e.tsx", "import 'clsx'\nexport const A = () => <div>x</div>\n")
        )
        Assert.assertNull(
            "namespace 导入需成员访问，不能直接调用",
            localName("C24f.tsx", "import * as ns from 'clsx'\nexport const A = () => <div>x</div>\n")
        )
        Assert.assertNull(
            "clsx-deep 不是 clsx/classnames",
            localName("C24g.tsx", "import cn from 'clsx-deep'\nexport const A = () => <div>x</div>\n")
        )

        // 别名默认导入：merge 必须生成 cn(...)，而不是未定义的 clsx(...)
        val tsx = myFixture.addFileToProject(
            "M24.tsx",
            "import { useState } from 'react'\nimport cn from 'clsx'\nconst A = () => (\n  <div style={{ color: 'red' }} className=\"foo\">hi</div>\n)\n"
        )
        val text = mergeStyleIntoClass(tsx, "className", "styles.bar", InlineStyleToCssModuleIntention.Framework.REACT)
        println("=== #24 alias merge M24.tsx ===")
        println(text)
        Assert.assertTrue(
            "应生成本地绑定名调用 cn(...): ${text.replace("\n", "\\n")}",
            text.contains("""className={cn("foo", styles.bar)}""")
        )
        Assert.assertFalse("不应出现硬编码 clsx( 调用", text.contains("clsx("))
        Assert.assertFalse("style 属性应删除", text.contains("style="))

        // namespace 导入 → 回退模板字符串（不能生成 ns(...) / clsx(...)）
        val tsx2 = myFixture.addFileToProject(
            "M24b.tsx",
            "import { useState } from 'react'\nimport * as ns from 'clsx'\nconst A = () => (\n  <div style={{ color: 'red' }} className=\"foo\">hi</div>\n)\n"
        )
        val text2 = mergeStyleIntoClass(tsx2, "className", "styles.bar", InlineStyleToCssModuleIntention.Framework.REACT)
        println("=== #24 namespace fallback M24b.tsx ===")
        println(text2)
        Assert.assertTrue(
            "namespace 导入应回退模板字符串: ${text2.replace("\n", "\\n")}",
            text2.contains("className={`foo \${styles.bar}`}")
        )
        Assert.assertFalse("不应生成 ns(...) 调用", text2.contains("ns("))
    }

    // ========================================================================
    // #25. React 动态表达式合并（A2/A5 修复）——
    //      三元：整体包进模板字符串插值；
    //      字符串拼接 "a" + "b"：不得误判为纯字面量（旧实现产生悬挂引号坏代码）；
    //      已有模板字面量：内接追加而不是嵌套新模板。
    // ========================================================================
    @Test
    fun `react dynamic expression merge wraps into template interpolation`() {
        val ternary = myFixture.addFileToProject(
            "M25.tsx",
            "import { useState } from 'react'\nconst A = () => (\n  <div style={{ color: 'red' }} className={isActive ? 'a' : 'b'}>hi</div>\n)\n"
        )
        val t1 = mergeStyleIntoClass(ternary, "className", "styles.bar", InlineStyleToCssModuleIntention.Framework.REACT)
        println("=== #25 ternary M25.tsx ===")
        println(t1)
        Assert.assertTrue(
            "三元应整体包进插值: ${t1.replace("\n", "\\n")}",
            t1.contains("className={`\${isActive ? 'a' : 'b'} \${styles.bar}`}")
        )
        Assert.assertFalse("style 属性应删除", t1.contains("style="))

        // A2：拼接表达式 —— 旧实现误判 "a" + "b" 为字面量，生成 `a" + "b ${...}` 悬挂引号
        val concat = myFixture.addFileToProject(
            "M25b.tsx",
            "import { useState } from 'react'\nconst B = () => (\n  <div style={{ color: 'red' }} className={\"a\" + \"b\"}>hi</div>\n)\n"
        )
        val t2 = mergeStyleIntoClass(concat, "className", "styles.bar", InlineStyleToCssModuleIntention.Framework.REACT)
        println("=== #25 concat M25b.tsx ===")
        println(t2)
        Assert.assertTrue(
            "拼接表达式应整体进插值: ${t2.replace("\n", "\\n")}",
            t2.contains("className={`\${\"a\" + \"b\"} \${styles.bar}`}")
        )
        Assert.assertFalse("不应产生悬挂引号的坏合并（旧 bug）", t2.contains("`a\""))

        // A5：已有模板字面量 → 内接（反引号数量不变）
        val tpl = myFixture.addFileToProject(
            "M25c.tsx",
            "import { useState } from 'react'\nconst C = () => (\n  <div style={{ color: 'red' }} className={`foo \${x}`}>hi</div>\n)\n"
        )
        val t3 = mergeStyleIntoClass(tpl, "className", "styles.bar", InlineStyleToCssModuleIntention.Framework.REACT)
        println("=== #25 template M25c.tsx ===")
        println(t3)
        Assert.assertTrue(
            "模板字面量应内接追加: ${t3.replace("\n", "\\n")}",
            t3.contains("className={`foo \${x} \${styles.bar}`}")
        )
        Assert.assertEquals("模板不应嵌套（反引号恰好 2 个）", 2, t3.count { it == '`' })
    }

    // ========================================================================
    // #26. 平铺语义（A4/A6 修复）——
    //      React：已有 clsx(...) 调用 → 追加参数而不是嵌套 clsx(clsx(...))；
    //      Vue JSX：已有 class={[...]} 数组 → 平铺追加而不是嵌套 [[...], x]
    //      （Vue 数组绑定原生递归展平，嵌套虽合法但属冗余噪音）。
    // ========================================================================
    @Test
    fun `flatten existing clsx call and vue array instead of nesting`() {
        val react = myFixture.addFileToProject(
            "M26.tsx",
            "import { useState } from 'react'\nimport clsx from 'clsx'\nconst A = () => (\n  <div style={{ color: 'red' }} className={clsx(\"a\", \"b\")}>hi</div>\n)\n"
        )
        val t1 = mergeStyleIntoClass(react, "className", "styles.bar", InlineStyleToCssModuleIntention.Framework.REACT)
        println("=== #26 clsx flatten M26.tsx ===")
        println(t1)
        Assert.assertTrue(
            "clsx 调用应平铺追加参数: ${t1.replace("\n", "\\n")}",
            t1.contains("""className={clsx("a", "b", styles.bar)}""")
        )
        Assert.assertEquals("clsx( 只应出现 1 次（不嵌套）", 1, Regex("""clsx\(""").findAll(t1).count())
        Assert.assertFalse("style 属性应删除", t1.contains("style="))

        val vueTsx = myFixture.addFileToProject(
            "M26b.tsx",
            "import { defineComponent } from 'vue'\nconst B = () => (\n  <div style={{ color: 'red' }} class={[\"a\", \"b\"]}>hi</div>\n)\n"
        )
        val t2 = mergeStyleIntoClass(vueTsx, "class", "styles.bar", InlineStyleToCssModuleIntention.Framework.VUE)
        println("=== #26 vue array flatten M26b.tsx ===")
        println(t2)
        Assert.assertTrue(
            "Vue 数组应平铺追加: ${t2.replace("\n", "\\n")}",
            t2.contains("""class={["a", "b", styles.bar]}""")
        )
        Assert.assertFalse("不应嵌套 [[", t2.contains("[["))
        Assert.assertFalse("Vue JSX 不应引入 clsx", t2.contains("clsx"))
        Assert.assertFalse("style 属性应删除", t2.contains("style="))
    }

    // ========================================================================
    // #27. Vue 模板 :class 合并（B1 修复）——
    //      已有 :class="dyn" → 合并为 [dyn, $style.card]，绝不产生第二个 :class
    //      （重复 :class 是 Vue 编译错误）；对象绑定 → 数组混排；已是数组 → 平铺；
    //      只有静态 class → 新 :class 与之共存（Vue 自动合并静态+动态）。
    //      .xml fixture 覆盖机制矩阵（与语言无关的合并逻辑）；
    //      真实 .vue 上的同链路见 RealVueFileIntegrationTest。
    // ========================================================================
    @Test
    fun `vue template merges into existing class binding without duplication`() {
        val intention = InlineStyleToCssModuleIntention()
        fun merge(fileName: String, content: String): String {
            val xml = myFixture.addFileToProject(fileName, content)
            val styleAttr = PsiTreeUtil.findChildrenOfType(xml, XmlAttribute::class.java)
                .firstOrNull { it.name.endsWith(":style") }
            Assert.assertNotNull("找不到 :style 属性", styleAttr)
            var ok = false
            WriteCommandAction.runWriteCommandAction(project) {
                ok = intention.handleVueTemplateReplacement(project, xml, styleAttr!!, "\$style.card")
            }
            Assert.assertTrue("应走 PSI 合并路径（而非 Document 兜底）", ok)
            return xml.text
        }

        // a) 任意表达式 dyn → [dyn, $style.card]，静态 class 保留
        val t1 = merge(
            "data27a.xml",
            "<root><div class=\"s\" :class=\"dyn\" :style=\"{ color: 'red' }\">hi</div></root>"
        )
        println("=== #27 vue template data27a.xml ===")
        println(t1)
        Assert.assertTrue("应合并为数组绑定: $t1", t1.contains(":class=\"[dyn, \$style.card]\""))
        Assert.assertEquals("只应有一个 :class（重复会 Vue 编译报错）", 1, Regex(""":class=""").findAll(t1).count())
        Assert.assertTrue("静态 class 应保留", t1.contains("class=\"s\""))
        Assert.assertFalse(":style 应删除", t1.contains(":style="))

        // b) 对象绑定 → 数组混排
        val t2 = merge(
            "data27b.xml",
            "<root><div :class=\"{ active: isActive }\" :style=\"{ color: 'red' }\">hi</div></root>"
        )
        println("=== #27 vue object data27b.xml ===")
        println(t2)
        Assert.assertTrue(
            "对象绑定应数组混排: ${t2.replace("\n", "\\n")}",
            t2.contains(":class=\"[{ active: isActive }, \$style.card]\"")
        )
        Assert.assertEquals(":class 只应有一个", 1, Regex(""":class=""").findAll(t2).count())

        // c) 已是数组 → 平铺（不嵌套）
        val t3 = merge(
            "data27c.xml",
            "<root><div :class=\"[a, b]\" :style=\"{ color: 'red' }\">hi</div></root>"
        )
        println("=== #27 vue array data27c.xml ===")
        println(t3)
        Assert.assertTrue("数组应平铺追加: $t3", t3.contains(":class=\"[a, b, \$style.card]\""))
        Assert.assertFalse("不应嵌套 [[", t3.contains("[["))

        // d) 无 :class：与静态 class 共存
        val t4 = merge(
            "data27d.xml",
            "<root><div class=\"s\" :style=\"{ color: 'red' }\">hi</div></root>"
        )
        println("=== #27 vue no-bind data27d.xml ===")
        println(t4)
        Assert.assertTrue("应新增 :class: $t4", t4.contains(":class=\"\$style.card\""))
        Assert.assertTrue("静态 class 应共存", t4.contains("class=\"s\""))
        Assert.assertFalse(":style 应删除", t4.contains(":style="))
        Assert.assertEquals(":class 只应有一个", 1, Regex(""":class=""").findAll(t4).count())
    }

    // ========================================================================
    // #28. 兄弟范围（A3 修复）——
    //      findClassAttr 只扫同标签直接属性；后代 span 的 className 不能被
    //      误当作兄弟合并目标（旧实现递归 descendants 会合并进错误的元素）。
    // ========================================================================
    @Test
    fun `findClassAttr only matches sibling attributes not descendants`() {
        val tsx = myFixture.addFileToProject(
            "M28.tsx",
            "import { useState } from 'react'\nconst A = () => (\n  <div style={{ color: 'red' }}><span className=\"inner\">x</span></div>\n)\n"
        )
        val intention = InlineStyleToCssModuleIntention()
        val styleAttr = PsiTreeUtil.findChildrenOfType(tsx, XmlAttribute::class.java)
            .firstOrNull { it.name == "style" }
        Assert.assertNotNull("找不到 style 属性", styleAttr)

        val found = intention.findClassAttr(styleAttr!!.parent, "className")
        Assert.assertNull("后代 span 的 className 不应被当作兄弟属性", found)

        WriteCommandAction.runWriteCommandAction(project) {
            intention.handleJsxReplacement(
                project, tsx, styleAttr, "styles.card", InlineStyleToCssModuleIntention.Framework.REACT
            )
        }
        val text = tsx.text
        println("=== #28 sibling scope M28.tsx ===")
        println(text)
        Assert.assertTrue("style 应替换为 className: $text", text.contains("className={styles.card}"))
        Assert.assertTrue("span 原有 className 应原样保留", text.contains("className=\"inner\""))
        Assert.assertFalse("style 属性应删除", text.contains("style="))
        Assert.assertEquals(
            "className 恰好 2 次（div 新增 + span 原有）",
            2, Regex("className=").findAll(text).count()
        )
    }
}
