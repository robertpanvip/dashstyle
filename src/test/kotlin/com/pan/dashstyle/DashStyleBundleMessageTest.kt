package com.pan.dashstyle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DashStyleBundle.message() 的参数格式化回归测试。
 *
 * 背景（1.3.3 引入的 bug）：Kotlin vararg 调 Java vararg 时漏写 `*` 展开符，
 * 整个参数数组被当成单个占位符 {0}，MessageFormat 渲染出 "[Ljava.lang.Object;@..."。
 * 对话框文案无法在 headless 沙箱里断言，因此直接在 bundle glue 层锁定行为：
 *  - 无参：返回原始文案；
 *  - 单参/多参：占位符逐个替换为参数值；
 *  - 任何情况下不得出现数组 toString 泄漏。
 *
 * 运行（build.gradle.kts 的 test 任务固定 en locale，断言按英文文案编写）：
 *   $ gradle --init-script _local_init.gradle.kts test --tests "com.pan.dashstyle.DashStyleBundleMessageTest"
 */
class DashStyleBundleMessageTest {

    @Test
    fun noParamsReturnsRawText() {
        assertEquals(
            "Rename extracted CSS class",
            DashStyleBundle.message("intention.extract.rename.dialog.title")
        )
    }

    @Test
    fun singleParamIsSubstituted() {
        assertEquals(
            "Cannot open Foo.module.css as PSI.",
            DashStyleBundle.message("intention.create.missing.class.cannot.open.psi", "Foo.module.css")
        )
    }

    @Test
    fun multipleParamsAreAllSubstituted() {
        val msg = DashStyleBundle.message(
            "intention.extract.success.message",
            "card", 3, "file X.module.css (via styles)", ".card {\n  color: red;\n}"
        )
        assertEquals(
            "Extracted `.card` (3 declarations) to file X.module.css (via styles).\n\n.card {\n  color: red;\n}",
            msg
        )
    }

    @Test
    fun formattedMessageNeverLeaksArrayToString() {
        val msg = DashStyleBundle.message("quickfix.remove.unused.rule.name", "foo-bar")
        assertTrue(msg.contains("foo-bar"))
        assertFalse(msg.contains("[Ljava.lang"))
    }
}
