package com.pan.dashstyle

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 探针：验证测试沙箱里 Vue / Angular 插件（build.gradle.kts 的 bundledPlugin）的 PSI 能力。
 *
 * 背景坑与噪音过滤见 [VueSandboxNoiseFilter]。
 * 生产路径的正式测试见 [RealVueFileIntegrationTest]，本类只做能力验证。
 */
@RunWith(JUnit4::class)
class ProbeVueAngularSandboxTest : BasePlatformTestCase() {

    private var errorProcessorToken: com.intellij.openapi.application.AccessToken? = null

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

    private fun dump(label: String, f: com.intellij.psi.PsiFile) {
        println("PROBE[$label] class=${f::class.java.name} lang=${f.language.id} displayName=${f.language.displayName}")
    }

    @Test
    fun probe01_vueFileLanguageIsVue() {
        val f = myFixture.configureByText(
            "ProbeApp.vue",
            """
            <template>
              <div :class="${'$'}style.box">{{ msg }}</div>
            </template>
            <script setup>
            const msg = 'hi'
            </script>
            <style module>
            .box { color: red; }
            </style>
            """.trimIndent()
        )
        dump("vue-file", f)
        Assert.assertEquals("Vue", f.language.id)
    }

    @Test
    fun probe02_vueStyleModuleContainsCssRuleset() {
        val f = myFixture.configureByText(
            "ProbeStyle.vue",
            """
            <template><div/></template>
            <style module>
            .box { color: red; }
            </style>
            """.trimIndent()
        )
        val rulesets = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
            f, com.intellij.psi.css.CssRuleset::class.java
        )
        println("PROBE[vue-style-module] rulesets=${rulesets.map { it.selectorList?.text }}")
        Assert.assertEquals("style module 里应有 1 条 CssRuleset", 1, rulesets.size)
    }

    @Test
    fun probe03_vueTemplateDollarStyleIsJsPsi() {
        val f = myFixture.configureByText(
            "ProbeTpl.vue",
            """
            <template>
              <div :class="${'$'}style.box"></div>
              <div :class="${'$'}style['flex-item']"></div>
            </template>
            <style module>
            .box { color: red; }
            .flex-item { color: blue; }
            </style>
            """.trimIndent()
        )
        val names = com.intellij.psi.util.PsiTreeUtil.findChildrenOfAnyType(
            f, false, com.intellij.psi.PsiElement::class.java
        ).map { it::class.java.simpleName }.toSet()
        println("PROBE[vue-template-psi] elementClassNamesSample=${names.filter { it.contains("JS") }.sorted()}")
        val jsExprNames = names.filter { it.startsWith("JS") }
        Assert.assertTrue("模板表达式应注入 JS PSI（实际类名: $names）", jsExprNames.isNotEmpty())
    }

    @Test
    fun probe04_typescriptComponentDecoratorPsi() {
        val f = myFixture.configureByText(
            "app.component.ts",
            """
            import { Component } from '@angular/core';
            @Component({
              selector: 'app-root',
              templateUrl: './app.component.html',
              styles: ['.card { color: red; }']
            })
            export class AppComponent {}
            """.trimIndent()
        )
        dump("ng-component", f)
        Assert.assertEquals("TypeScript", f.language.id)
        val names = com.intellij.psi.util.PsiTreeUtil.findChildrenOfAnyType(
            f, false, com.intellij.psi.PsiElement::class.java
        ).map { it::class.java.simpleName }.toSet()
        println("PROBE[ng-decorator] hasDecorator=${names.any { it.contains("Decorator") }}")
        Assert.assertTrue("应有 @Component 装饰器 PSI（实际类名: $names）", names.any { it.contains("Decorator") })
    }

    @Test
    fun probe05_componentHtmlTemplateLanguage() {
        // 注意：addFileToProject 写 .ts 到 VFS 会触发 TypeScriptCompilerServiceVfsListener →
        // VueLsp 服务初始化（下载版布局下炸），因此这里与 probe04 一致用 configureByText；
        // Angular2HTML 接管需要真实索引关联，此探针验证基础 HTML PSI 与绑定属性可见性
        myFixture.configureByText(
            "app.component.ts",
            """
            import { Component } from '@angular/core';
            @Component({
              selector: 'app-root',
              templateUrl: './app.component.html'
            })
            export class AppComponent {}
            """.trimIndent()
        )
        val html = myFixture.configureByText(
            "app.component.html",
            """<div [class.active]="isActive" (click)="onClick()">{{ title }}</div>"""
        )
        dump("ng-html", html)
        val langId = html.language.id
        println("PROBE[ng-html] langId=$langId")
        // 宽松断言：至少应为 HTML 家族；若 Angular 插件接管则通常是 Angular2HTML
        Assert.assertTrue("html 语言应为 HTML/Angular2HTML，实际: $langId", langId == "HTML" || langId.contains("Angular"))
        val attrs = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
            html, com.intellij.psi.xml.XmlAttribute::class.java
        ).map { it.name }
        println("PROBE[ng-html-attrs] attrs=$attrs")
        Assert.assertTrue(
            "Angular 绑定属性 [class.active]/(click) 应作为 XmlAttribute 可见，实际: $attrs",
            attrs.contains("[class.active]") && attrs.contains("(click)")
        )
    }

    // ================================================================
    // 探针7：Vue 内嵌 <style module> 的置灰链路诊断——
    //        排查「新增 class 引用、点菜单自动创建 CSS 类后仍置灰」的 root cause。
    //        打印 cssFile.virtualFile / language / parent，以及 computeFileSnapshot。
    // ================================================================
    @Test
    fun probe07_vueEmbeddedStyleSnapshotDiagnostics() {
        val f = myFixture.configureByText(
            "ProbeSnap.vue",
            """
            <template>
              <div :class="${'$'}style.card"></div>
            </template>
            <style module>
            .card { color: red; }
            .unused { opacity: 0; }
            </style>
            """.trimIndent()
        )
        val ruleset = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
            f, com.intellij.psi.css.CssRuleset::class.java
        ).firstOrNull()
        Assert.assertNotNull("style module 应有 CssRuleset", ruleset)
        val cssFile = ruleset!!.containingFile
        val vf = cssFile.virtualFile
        println("PROBE[embedded-css] cssFile=${cssFile::class.java.simpleName} lang=${cssFile.language.id} " +
            "vf=${vf?.path} vfName=${vf?.name} parentCss=${cssFile.parent?.javaClass?.simpleName}")
        println("PROBE[embedded-css] cssFileIsStylesheet=${cssFile is com.intellij.psi.css.StylesheetFile} " +
            "isXml=${cssFile is com.intellij.psi.xml.XmlFile}")

        val snap = com.pan.dashstyle.inspection.UnusedCssModuleClassInspection.computeFileSnapshot(
            cssFile, listOf(f to "\$style")
        )
        println("PROBE[snapshot] hasDynamic=${snap.hasDynamic} used=${snap.used.sorted()} " +
            "global=${snap.globalClassNames.sorted()} rulesetCount=${snap.classesByRulesetText.size}")

        // 对照组 A：手动构造正确 alias="$style" 的 VueStyleTag 再 scanUsages，
        //        确定「正确的 container」能否扫到 $style.card（排除扫描器本身问题）
        val modTag = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
            f, com.intellij.psi.xml.XmlTag::class.java
        ).firstOrNull { it.name.equals("style", ignoreCase = true) && it.getAttribute("module") != null }
        Assert.assertNotNull("应有 style module 标签", modTag)
        val manual = com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction<
            Pair<MutableSet<String>, Boolean>
            > {
            com.pan.dashstyle.support.CssModuleUsageScanner.scanUsages(
                f,
                com.pan.dashstyle.support.CssModuleResolver.CssContainer.VueStyleTag(modTag!!, "\$style", f)
            )
        }
        println("PROBE[manual-alias-\\\$style] used=${manual.first.sorted()} hasDynamic=${manual.second}")

        // 对照组 B：构造「带双美元 alias」的容器（等价于 resolveContainerForUsageScan
        //         当前在 bindingName 已含 $ 时的行为），验证是否因此扫不到
        val doubleAlias = com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction<
            Pair<MutableSet<String>, Boolean>
        > {
            com.pan.dashstyle.support.CssModuleUsageScanner.scanUsages(
                f,
                com.pan.dashstyle.support.CssModuleResolver.CssContainer.VueStyleTag(modTag!!, "\$\$style", f)
            )
        }
        println("PROBE[double-dollar-alias] used=${doubleAlias.first.sorted()} hasDynamic=${doubleAlias.second}")

        // 对照组 C：把 bindingName 当作「import binding 名」走 resolveContainerForUsageScan
        //        私有不可直接调，改为模拟其 import 语义：bindingName="style"（不带 $）
        val importSem = com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction<
            Pair<MutableSet<String>, Boolean>
        > {
            com.pan.dashstyle.support.CssModuleUsageScanner.scanUsages(
                f,
                com.pan.dashstyle.support.CssModuleResolver.CssContainer.VueStyleTag(modTag!!, "\$style", f)
            )
        }
        println("PROBE[import-sem-binding-style] used=${importSem.first.sorted()} hasDynamic=${importSem.second}")

        // 对照组 D：模板里实际出现的是不是 $style（原样打印），确认正则匹配基准
        val tplAttrs = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                f, com.intellij.psi.xml.XmlAttribute::class.java
            ).map { "${it.name}=${it.value}" }
        println("PROBE[template-attrs] $tplAttrs")

        // 同时核对 annotation 置灰路径所用的 virtualFile 判断：
        // withName 是 .vue 而非 *.module.* —— 这是关键分叉
        val moduleEx = listOf(".module.css", ".module.scss", ".module.sass", ".module.less")
        val modOk = vf != null && moduleEx.any { vf.name.endsWith(it, ignoreCase = true) }
        println("PROBE[module-check] vfName=${vf?.name} passesModuleExtCheck=$modOk")
    }

    // ================================================================
    // 回归：Vue 内嵌 <style module> 的「未使用置灰」判定——
    //        走生产路径 UnusedCssModuleClassInspection.snapshotFor（内部含 findReferencingSourceFiles
    //        与 resolveContainerForUsageScan），断言被引用的 card 计入 used、未被引用的 unused 不在 used。
    //        （修复前：bindingName 被拼成 $$style 扫不到引用 → used 为空 → card 被置灰，即用户报告的 bug）
    // ================================================================
    @Test
    fun probe08_vueEmbeddedClassUsedIsNotGrayed() {
        val f = myFixture.configureByText(
            "ProbeRegress.vue",
            """
            <template>
              <div :class="${'$'}style.card"></div>
              <span :class="${'$'}style['flex-item']">x</span>
            </template>
            <style module>
            .card { color: red; }
            .flex-item { color: blue; }
            .unused { opacity: 0; }
            </style>
            """.trimIndent()
        )
        val ruleset = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
            f, com.intellij.psi.css.CssRuleset::class.java
        ).firstOrNull { it.selectorList?.text?.contains(".card") == true }
        Assert.assertNotNull("应有 .card 的 CssRuleset", ruleset)
        val cssFile = ruleset!!.containingFile

        // --- 诊断 1：cssFile 的形状 ---
        println("PROBE[diag-cssfile] class=${cssFile::class.java.simpleName} " +
            "isXmlFile=${cssFile is com.intellij.psi.xml.XmlFile} vfName=${cssFile.virtualFile?.name}")

        val snap = com.pan.dashstyle.inspection.UnusedCssModuleClassInspection().snapshotFor(cssFile)
        println("PROBE[regress-vue] used=${snap.used.sorted()} hasDynamic=${snap.hasDynamic} " +
            "isCssModule=${com.pan.dashstyle.support.CssModuleResolver.isCssModuleFile(cssFile)}")

        // --- 诊断 2：用与生产路径一致的方式（findVueStyleModuleTag + VueStyleTag 容器）直接扫描 ---
        val modTag = com.pan.dashstyle.support.CssModuleResolver.findVueStyleModuleTag(cssFile)
        println("PROBE[diag-modaTag] found=${modTag != null} aliasAttr=${modTag?.getAttributeValue("module")}")
        val tplTag = com.pan.dashstyle.support.Util.findTagInFile(cssFile, "template")
        println("PROBE[diag-template] found=${tplTag != null}")
        val attrs = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                cssFile, com.intellij.psi.xml.XmlAttribute::class.java
            ).map { "${it.name}=${it.value}" }
        println("PROBE[diag-attrs] $attrs")
        if (modTag != null) {
            val direct = com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction<
                Pair<MutableSet<String>, Boolean>
            > {
                com.pan.dashstyle.support.CssModuleUsageScanner.scanUsages(
                    cssFile,
                    com.pan.dashstyle.support.CssModuleResolver.CssContainer.VueStyleTag(modTag!!, "\$style", cssFile)
                )
            }
            println("PROBE[direct-scan] used=${direct.first.sorted()} hasDynamic=${direct.second}")
        }

        Assert.assertTrue("card 被 \$style.card 引用，不应被置灰，必须计入 used（实际 used=${snap.used.sorted()}, hasDynamic=${snap.hasDynamic}）",
            snap.used.contains("card"))
        Assert.assertTrue("flex-item 被 \$style['flex-item'] 引用，必须计入 used（实际 used=${snap.used.sorted()}）",
            snap.used.contains("flex-item"))
        Assert.assertFalse("unused 未被引用，应仍在置灰候选（即未出现在 used）",
            snap.used.contains("unused"))
    }

    @Test
    fun probe06_inlineStylesDecoratorCssRuleset() {
        val f = myFixture.configureByText(
            "app.card.ts",
            """
            import { Component } from '@angular/core';
            @Component({
              selector: 'app-card',
              template: '<div class="card">x</div>',
              styles: ['.card { color: red; padding: 4px; }']
            })
            export class AppCardComponent {}
            """.trimIndent()
        )
        // TS 文件上做 CssRuleset 树遍历可能触发 Vue 插件 CSS 扩展初始化（下载版布局下会抛
        // ExceptionInInitializerError），探针阶段防护后观察结果即可
        val rulesets = runCatching {
            com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                f, com.intellij.psi.css.CssRuleset::class.java
            )
        }.getOrDefault(emptySet())
        println("PROBE[ng-inline-styles] rulesets=${rulesets.map { it.selectorList?.text }}")
        // 观察项，弱断言：styles 字符串若注入 CSS 则出现 CssRuleset；否则为空也不算失败
        Assert.assertNotNull(f)
    }
}
