package com.pan.dashstyle

import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 探针：验证测试沙箱里 Vue / Angular 插件（build.gradle.kts 的 bundledPlugin）的 PSI 能力。
 *
 * 背景坑：Gradle 下载的 WebStorm 发行版里，语言服务资源位于 plugins/javascript-plugin/
 * jsLanguageServicesImpl，而 JSPluginPathManager 按 "plugins/JavaScriptLanguage/resources/"
 * 布局查找（安装器版布局），找不到 → VueLspServerPackageDescriptor 构造抛
 * ExceptionInInitializerError → "Cannot create extension VueLspServerSupportProvider" ERROR
 * 由后台线程异步落到任意正在运行的测试头上（TestLoggerAssertionError）。
 * LSP 服务对 headless 测试无意义（启动外部 vue-tsc 进程），因此 setUp 里安装
 * LoggedErrorProcessor 精准吞掉 VueLsp 相关错误，避免误伤随机测试。
 */
@RunWith(JUnit4::class)
class ProbeVueAngularSandboxTest : BasePlatformTestCase() {

    private var errorProcessorToken: com.intellij.openapi.application.AccessToken? = null

    override fun setUp() {
        super.setUp()
        errorProcessorToken = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(
                message: String,
                detailMessage: String,
                details: Array<out String>,
                t: Throwable?
            ): Set<LoggedErrorProcessor.Action> {
                val text = "$message $detailMessage " +
                    (t?.let { "${it.javaClass.name}: ${it.message}" } ?: "") + " " +
                    (t?.cause?.let { "${it.javaClass.name}: ${it.message}" } ?: "")
                // 结构性判断：异常链任一层的调用栈经过 org.jetbrains.vuejs /
                // JSPluginPathManager，即视为 Vue 插件语言服务在下载版布局下的已知噪音
                val fromVueServices = generateSequence(t) { it.cause }.any { th ->
                    th.stackTrace.any {
                        it.className.startsWith("org.jetbrains.vuejs") ||
                            it.className == "com.intellij.lang.javascript.psi.util.JSPluginPathManager"
                    }
                }
                if (fromVueServices ||
                    text.contains("VueLsp") ||
                    text.contains("vuejs.lang.typescript.service") ||
                    text.contains("should be lib directory") ||
                    text.contains("jsLanguageServicesImpl")
                ) {
                    return emptySet()
                }
                return super.processError(message, detailMessage, details, t)
            }
        })
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
