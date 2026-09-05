package com.pan.dashstyle

import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** 临时探针：探测 LESS mixin 调用/定义在 WS-2025.3 沙箱 PSI 中的形态（诊断后删除） */
@RunWith(JUnit4::class)
class ProbeLessMixinTest : BasePlatformTestCase() {

    @Test
    fun `probe less mixin psi shape`() {
        val less = myFixture.configureByText(
            "x.module.less",
            ".app-root {\n  .shared-color();\n}\n\n.shared-color {\n  color: red;\n}\n"
        )
        println("PROBEMIX file=${less.javaClass.name} lang=${less.language.id}")
        PsiTreeUtil.processElements(less) { el ->
            if (el is CssRuleset) {
                val kids = el.block?.children?.joinToString(",") { c ->
                    "${c.javaClass.simpleName}[${c.text.replace("\n", "\\n")}]"
                } ?: "<no-block>"
                println("PROBEMIX ruleset sel=[${el.selector?.text}] kids=$kids")
            }
            val cn = el.javaClass.simpleName
            if (cn.contains("Mixin", true) || cn.contains("Less", true)) {
                println("PROBEMIX lessNode ${el.javaClass.name} text=[${el.text}]")
            }
            true
        }
    }

    @Test
    fun `probe less mixin variants`() {
        val less = myFixture.configureByText(
            "y.module.less",
            ".a { .m1; .m2(#fff); .m3(1px, 2px); }\n.m1 { color: red; }\n.m2(@c) { color: @c; }\n.m3(@x; @y) { padding: @x @y; }\n"
        )
        println("PROBEMIX2 file=${less.javaClass.name} lang=${less.language.id}")
        PsiTreeUtil.processElements(less) { el ->
            if (el is CssRuleset) {
                val kids = el.block?.children?.joinToString(",") { c ->
                    "${c.javaClass.simpleName}[${c.text.replace("\n", "\\n")}]"
                } ?: "<no-block>"
                println("PROBEMIX2 ruleset sel=[${el.selector?.text}] kids=$kids")
            }
            true
        }
    }
}
