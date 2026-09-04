package com.pan.dashstyle

import com.pan.dashstyle.support.*
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 框架 CSS 解析层集成测试（运行在 WebStorm-2025.3 沙箱）——
 * 按功能补充声明签名计算、选择器遍历解析、语义类名推断、Vue SFC 工具方法的真实 PSI 覆盖。
 */
@RunWith(JUnit4::class)
@Suppress("UnstableApiUsage", "UNUSED_PARAMETER")
class FrameworkCssResolverTest : BasePlatformTestCase() {

    @Test
    fun `computeSignature - 声明顺序不影响签名`() {
        val c1 = myFixture.configureByText("a.css", ".a { padding: 0; margin: 0; }")
        val c2 = myFixture.configureByText("b.css", ".a { margin: 0; padding: 0; }")
        val sig1 = ApplicationManager.getApplication().runReadAction<String?> {
            DeclarationSignatureUtil.computeSignature(rulesetOf(c1))
        }
        val sig2 = ApplicationManager.getApplication().runReadAction<String?> {
            DeclarationSignatureUtil.computeSignature(rulesetOf(c2))
        }
        Assert.assertEquals("声明顺序应归一", sig1, sig2)
        Assert.assertEquals("margin:0|padding:0", sig1)
    }

    @Test
    fun `computeSignature - hex3 展开与值归一`() {
        val css = myFixture.configureByText("c.css", ".a { COLOR: #F00; }")
        val sig = ApplicationManager.getApplication().runReadAction<String?> {
            DeclarationSignatureUtil.computeSignature(rulesetOf(css))
        }
        Assert.assertEquals("color:#ff0000", sig)
    }

    @Test
    fun `computeSignature - 空块返回 null`() {
        val css = myFixture.configureByText("d.css", ".a {}")
        val sig = ApplicationManager.getApplication().runReadAction<String?> {
            DeclarationSignatureUtil.computeSignature(rulesetOf(css))
        }
        Assert.assertNull(sig)
    }

    @Test
    fun `computeSignatureList - 排序后的声明签名列表`() {
        val css = myFixture.configureByText("e.css", ".a { z-index: 1; color: red; }")
        val list = ApplicationManager.getApplication().runReadAction<List<String>> {
            DeclarationSignatureUtil.computeSignatureList(rulesetOf(css))
        }
        Assert.assertEquals(listOf("color:red", "z-index:1"), list)
    }

    @Test
    fun `computeSignatureFromDeclarations - 直取声明列表`() {
        val css = myFixture.configureByText("f.css", ".a { color: red; margin: 8px; }")
        val decls = ApplicationManager.getApplication().runReadAction<List<CssDeclaration>> {
            PsiTreeUtil.findChildrenOfType(rulesetOf(css), CssDeclaration::class.java).toList()
        }
        val sig = ApplicationManager.getApplication().runReadAction<String> {
            DeclarationSignatureUtil.computeSignatureFromDeclarations(decls)
        }
        Assert.assertEquals("color:red|margin:8px", sig)
    }

    // ========================================================================
    // B. CssSelectorResolver —— 容器内 class 遍历与声明直取
    // ========================================================================

    @Test
    fun `collectAllClasses - 收集顶级与嵌套规则展开后的类名`() {
        val css = myFixture.addFileToProject(
            "m.module.css",
            ".a { color: red; }\n.b { color: blue; .b-child { margin: 0; } }\n"
        )
        val container = CssModuleResolver.resolveContainerByFile(project, css.virtualFile, "styles")
        Assert.assertNotNull("容器应能解析", container)
        val entries = ApplicationManager.getApplication().runReadAction<List<CssSelectorResolver.ClassEntry>> {
            CssSelectorResolver.collectAllClasses(container!!)
        }
        val names = entries.map { it.kebabName }.toSet()
        Assert.assertTrue("应含顶级类 a", "a" in names)
        Assert.assertTrue("应含顶级类 b", "b" in names)
        Assert.assertTrue("嵌套子类应展开命名为 b-child", "b-child" in names)

        // 关键：声明确认是"直接子节点"，嵌套子规则里的 margin 不能混入 a
        val entryA = entries.firstOrNull { it.kebabName == "a" }
        Assert.assertNotNull("a 应有对应 entry", entryA)
        val aProps = entryA!!.declarations.map { it.propertyName }
        Assert.assertEquals("a 只应直接包含 color", listOf("color"), aProps)
    }

    @Test
    fun `collectAllClasses - 顶层类声明不含嵌套后代声明`() {
        val css = myFixture.addFileToProject(
            "n.module.css",
            ".card { padding: 1px; }\n.card { .icon { color: #fff; } }\n"
        )
        val container = CssModuleResolver.resolveContainerByFile(project, css.virtualFile, "styles")
        Assert.assertNotNull("容器应能解析", container)
        val card = ApplicationManager.getApplication().runReadAction<CssSelectorResolver.ClassEntry?> {
            CssSelectorResolver.collectAllClasses(container!!)
                .firstOrNull { it.kebabName == "card" && it.declarations.any { d -> d.propertyName == "padding" } }
        }
        Assert.assertNotNull("应找到直接含 padding 的 card", card)
        Assert.assertEquals(
            "card 的直接声明只应含 padding（嵌套 icon 的 color 不能并入）",
            listOf("padding"),
            card!!.declarations.map { it.propertyName }
        )
    }

    @Test
    fun `resolveClassName - 从 JSX styles 引用解析回规则集（camelCase 转 kebab）`() {
        myFixture.addFileToProject("r.module.css", ".foo-bar { color: red; }")
        val tsx = myFixture.addFileToProject("R.tsx", "import styles from './r.module.css'\nconst a = styles.fooBar\n")
        val ref = ApplicationManager.getApplication().runReadAction<JSReferenceExpression?> {
            PsiTreeUtil.findChildrenOfType(tsx, JSReferenceExpression::class.java)
                .firstOrNull { it.text == "styles.fooBar" }
        }
        Assert.assertNotNull("应找到 styles.fooBar 引用", ref)
        val resolved = ApplicationManager.getApplication().runReadAction<CssSelectorResolver.ResolvedClass?> {
            CssSelectorResolver.resolveClassName(ref!!, "fooBar")
        }
        Assert.assertNotNull("fooBar 应解析到 .foo-bar 规则集", resolved)
        Assert.assertEquals("kebab 名应为 foo-bar", "foo-bar", resolved!!.kebabName)
        Assert.assertTrue(
            "展开选择器应含 .foo-bar",
            resolved.expandedSelector.contains(".foo-bar")
        )
    }

    @Test
    fun `resolveClassName - 不存在的类返回 null`() {
        myFixture.addFileToProject("s.module.css", ".foo-bar { color: red; }")
        val tsx = myFixture.addFileToProject("S.tsx", "import styles from './s.module.css'\nconst a = styles.missing\n")
        val ref = ApplicationManager.getApplication().runReadAction<JSReferenceExpression?> {
            PsiTreeUtil.findChildrenOfType(tsx, JSReferenceExpression::class.java)
                .firstOrNull { it.text == "styles.missing" }
        }
        Assert.assertNotNull("应找到 styles.missing 引用", ref)
        val resolved = ApplicationManager.getApplication().runReadAction<CssSelectorResolver.ResolvedClass?> {
            CssSelectorResolver.resolveClassName(ref!!, "missing")
        }
        Assert.assertNull("不存在的类应返回 null", resolved)
    }

    // ========================================================================
    // C. SemanticClassNameInferrer —— 基于 CSS 声明的语义候选
    // ========================================================================

    @Test
    fun `inferCandidates - 布局与视觉声明驱动语义候选且优先于兜底`() {
        val css = myFixture.configureByText("sc.css", ".dummy { display: flex; }")
        val el = ApplicationManager.getApplication().runReadAction<CssRuleset> { rulesetOf(css) }
        val cands = ApplicationManager.getApplication().runReadAction<List<SemanticClassNameInferrer.Candidate>> {
            SemanticClassNameInferrer.inferCandidates(
                el, "display:flex\njustify-content:center", el
            )
        }
        val names = cands.map { it.name }
        Assert.assertTrue("应含 layout 语义 flex", "flex" in names)
        Assert.assertTrue("应含 layout 语义 center", "center" in names)
        Assert.assertTrue("语义候选应在兜底 wrapper 之前", names.indexOf("flex") < names.indexOf("wrapper"))
        Assert.assertTrue("平滑 fallback box 应在末尾", names.indexOf("box") == names.lastIndex)
        val top = SemanticClassNameInferrer.topCandidate(cands)
        Assert.assertFalse("top 候选不应是兜底名", top == "wrapper" || top == "box")
    }

    @Test
    fun `inferCandidates - 明确的布局语义生成完整候选集合`() {
        val css = myFixture.configureByText("sc2.css", ".dummy { display: flex; }")
        val el = ApplicationManager.getApplication().runReadAction<CssRuleset> { rulesetOf(css) }
        val cands = ApplicationManager.getApplication().runReadAction<List<SemanticClassNameInferrer.Candidate>> {
            SemanticClassNameInferrer.inferCandidates(el, "display:flex", el)
        }
        val names = cands.map { it.name }
        // flex / flexbox / container 都是 flex 布局的候选
        Assert.assertTrue("flex 布局应生成 flexbox", "flexbox" in names)
        Assert.assertTrue("flex 布局应生成 container", "container" in names)
    }

    @Test
    fun `topCandidate - 空候选回退 wrapper`() {
        Assert.assertEquals("wrapper", SemanticClassNameInferrer.topCandidate(emptyList()))
    }

    // ========================================================================
    // D. Util —— Vue SFC 脚本/style 标签定位
    // ========================================================================

    @Test
    fun `findScriptTag - 返回 script 标签`() {
        val xml = myFixture.configureByText(
            "App.vue.xml",
            "<root><template><div/></template><script>const a = 1</script></root>"
        )
        val tag = ApplicationManager.getApplication().runReadAction<com.intellij.psi.xml.XmlTag?> {
            com.pan.dashstyle.support.Util.findScriptTag(xml)
        }
        Assert.assertNotNull("应找到 script 标签", tag)
        Assert.assertEquals("script", tag!!.name)
    }

    @Test
    fun `findModuleStyleTag - 只有带 module 属性的 style 才命中`() {
        val xml = myFixture.configureByText(
            "App.vue.xml",
            "<root><style>body{}</style><style module>.a{}</style></root>"
        )
        val mod = ApplicationManager.getApplication().runReadAction<com.intellij.psi.xml.XmlTag?> {
            com.pan.dashstyle.support.Util.findModuleStyleTag(xml)
        }
        Assert.assertNotNull("应找到带 module 的 style 标签", mod)
        Assert.assertNotNull("该 style 应带 module 属性", mod!!.getAttribute("module"))
    }

    @Test
    fun `findModuleStyleTag - 无 module style 返回 null`() {
        val xml = myFixture.configureByText(
            "App.vue.xml",
            "<root><style>body{}</style></root>"
        )
        val mod = ApplicationManager.getApplication().runReadAction<com.intellij.psi.xml.XmlTag?> {
            com.pan.dashstyle.support.Util.findModuleStyleTag(xml)
        }
        Assert.assertNull("不应命中不带 module 的 style", mod)
    }

    @Test
    fun `findTagInFile - 不区分大小写定位标签`() {
        val xml = myFixture.configureByText(
            "App.vue.xml",
            "<root><TEMPLATE><div/></TEMPLATE></root>"
        )
        val tag = ApplicationManager.getApplication().runReadAction<com.intellij.psi.xml.XmlTag?> {
            com.pan.dashstyle.support.Util.findTagInFile(xml, "template")
        }
        Assert.assertNotNull("应大小写不敏感定位 TEMPLATE", tag)
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    private fun rulesetOf(file: com.intellij.psi.PsiFile): CssRuleset =
        PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java).firstOrNull()
            ?: throw AssertionError("fixture 未解析出任何 CssRuleset")
}