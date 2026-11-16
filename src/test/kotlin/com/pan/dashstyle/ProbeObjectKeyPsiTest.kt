package com.pan.dashstyle

import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path

@RunWith(JUnit4::class)
@Suppress("UnstableApiUsage")
class ProbeObjectKeyPsiTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String =
        Path.of("src/test/testData").toAbsolutePath().toString()

    private var noise: com.intellij.openapi.application.AccessToken? = null
    override fun setUp() { super.setUp(); noise = VueSandboxNoiseFilter.install() }
    override fun tearDown() { try { noise?.finish() } finally { super.tearDown() } }

    private fun configureVue(text: String): com.intellij.psi.xml.XmlFile {
        val vf = myFixture.addFileToProject("Test.vue", text)
        myFixture.openFileInEditor(vf.virtualFile!!)
        return myFixture.file as com.intellij.psi.xml.XmlFile
    }

    @Test
    fun dumpPsi() {
        val vue = configureVue("""
<template>
  <div :class="{ 'invalid-option': option.invalid, active: isOn }">Hi</div>
</template>
<style scoped>
.invalid-option { color: red; }
</style>
        """.trimIndent())

        // Dump everything inside :class attr
        val classAttr = PsiTreeUtil.findChildrenOfType(vue, XmlAttribute::class.java)
            .first { it.name == ":class" }

        println("=== :class attr text='${classAttr.text}' ===")
        println("=== :class value='${classAttr.value}' ===")

        // Walk all descendants of the attr
        println("=== ALL descendants of :class XmlAttribute ===")
        fun dumpTree(el: PsiElement, depth: Int = 0) {
            val indent = "  ".repeat(depth)
            val shortText = el.text?.replace("\n", "\\n")?.take(40) ?: "<null>"
            println("${indent}${el.javaClass.simpleName} '${shortText}'")
            for (child in el.children) dumpTree(child, depth + 1)
        }
        dumpTree(classAttr)

        // Also check if JSObjectLiteralExpression is found globally
        println("=== JSObjectLiteralExpressions found globally ===")
        PsiTreeUtil.findChildrenOfType(vue, JSObjectLiteralExpression::class.java)
            .forEach { println("  text='${it.text}'") }
        println("=== JSProperty found globally ===")
        PsiTreeUtil.findChildrenOfType(vue, JSProperty::class.java)
            .forEach { p ->
                println("  JSProperty text='${p.text}' name='${p.name}' children:")
                p.children.forEach { c ->
                    println("    ${c.javaClass.simpleName} '${c.text}'")
                }
            }
    }
}
