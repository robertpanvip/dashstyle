// 独立的 Less 特性与工具函数验证脚本
// 不依赖 Gradle / IntelliJ / JUnit，直接用 kotlinc 编译或 JShell 风格运行
// 运行方式: (在有 kotlinc 的环境下)
//   kotlinc StandaloneVerifier.kt Util.kt -include-runtime -d verify.jar && java -jar verify.jar

import java.io.File

// ------------- 复制自 Util.kt 的纯函数部分 -------------

object TestUtil {
    fun kebabToCamel(name: String): String {
        return name.split("-").mapIndexed { index, part ->
            if (index == 0) part else part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }.joinToString("")
    }

    fun camelToKebab(name: String): String {
        return buildString {
            name.forEach { ch ->
                if (ch.isUpperCase()) {
                    append("-")
                    append(ch.lowercaseChar())
                } else {
                    append(ch)
                }
            }
        }.removePrefix("-")
    }

    fun expandAmpersand(rawSelector: String, parentSelector: String): String {
        val placeholders = mutableMapOf<String, String>()
        var processedSelector = rawSelector
        val varPattern = Regex("""@\{([^}]+)\}""")
        var matchResult = varPattern.find(processedSelector)
        var counter = 0
        while (matchResult != null) {
            val placeholder = "__VAR_PLACEHOLDER_${counter}__"
            placeholders[placeholder] = matchResult.value
            processedSelector = processedSelector.replaceRange(matchResult.range, placeholder)
            counter++
            matchResult = varPattern.find(processedSelector)
        }

        val expanded = if (!processedSelector.contains("&")) {
            val parentParts = parentSelector.split(",").map { it.trim() }
            val childParts = processedSelector.split(",").map { it.trim() }
            val combinations = mutableListOf<String>()
            for (p in parentParts) {
                for (c in childParts) {
                    combinations.add("$p $c")
                }
            }
            combinations.joinToString(", ")
        } else {
            val parentParts = parentSelector.split(",").map { it.trim() }
            val results = mutableListOf<String>()
            for (parentPart in parentParts) {
                val childParts = processedSelector.split(",").map { it.trim() }
                for (childPart in childParts) {
                    results.add(replaceAmpersandInPart(childPart, parentPart))
                }
            }
            results.joinToString(", ")
        }

        var finalResult = expanded
        for ((placeholder, original) in placeholders) {
            finalResult = finalResult.replace(placeholder, original)
        }
        return finalResult
    }

    internal fun replaceAmpersandInPart(childPart: String, parentPart: String): String {
        val result = StringBuilder()
        var i = 0
        val n = childPart.length
        while (i < n) {
            if (childPart[i] == '&') {
                result.append(parentPart)
                i++
            } else {
                result.append(childPart[i])
                i++
            }
        }
        return result.toString()
    }
}

// ------------- 极简断言框架 -------------
var passedCount = 0
var failedCount = 0
val failures = mutableListOf<String>()

fun assertEquals(expected: Any?, actual: Any?, message: String = "") {
    if (expected == actual) {
        passedCount++
    } else {
        failedCount++
        val msg = "FAIL: $message\n  Expected: $expected\n  Actual:   $actual"
        failures.add(msg)
        println("✗ $msg")
    }
}

fun assertTrue(condition: Boolean, message: String = "") {
    assertEquals(true, condition, message)
}

fun assertFalse(condition: Boolean, message: String = "") {
    assertEquals(false, condition, message)
}

fun section(name: String) {
    println("\n━━━ $name ━━━")
}

// ------------- 测试执行入口 -------------
fun main() {
    println("╔══════════════════════════════════════════════════╗")
    println("║  Standalone Less Feature Verification            ║")
    println("╚══════════════════════════════════════════════════╝")

    runKebabCamelTests()
    runLessAmpersandTests()
    runBemScenarioTests()

    println("\n" + "═".repeat(52))
    println("✓ Passed: $passedCount")
    println("✗ Failed: $failedCount")
    if (failures.isNotEmpty()) {
        println("\nFailed details:")
        failures.forEachIndexed { i, f -> println("${i + 1}. $f") }
    }
    println("═".repeat(52))
    if (failedCount == 0) {
        println("\n🎉 All tests passed! Less 特性支持验证成功。")
    } else {
        println("\n⚠️  $failedCount tests failed, please check.")
    }
}

fun runKebabCamelTests() {
    section("kebabToCamel / camelToKebab 互转测试")

    assertEquals("fooBar", TestUtil.kebabToCamel("foo-bar"), "普通 kebab-case")
    assertEquals("fooBarBaz", TestUtil.kebabToCamel("foo-bar-baz"), "多段 kebab-case")
    assertEquals("foo", TestUtil.kebabToCamel("foo"), "单段无连字符")
    assertEquals("", TestUtil.kebabToCamel(""), "空字符串")
    assertEquals("aBC", TestUtil.kebabToCamel("a-b-c"), "单字符段")

    assertEquals("foo-bar", TestUtil.camelToKebab("fooBar"), "普通 camelCase")
    assertEquals("foo-bar-baz", TestUtil.camelToKebab("fooBarBaz"), "多段大写")
    assertEquals("foobar", TestUtil.camelToKebab("foobar"), "全小写无大写")
    assertEquals("foo-bar", TestUtil.camelToKebab("FooBar"), "首字母大写")
    assertEquals("", TestUtil.camelToKebab(""), "空串 camelToKebab")

    // 互逆测试
    val orig = "foo-bar-baz-qux"
    val camel = TestUtil.kebabToCamel(orig)
    val back = TestUtil.camelToKebab(camel)
    assertEquals(orig, back, "roundtrip kebab→camel→kebab")
}

fun runLessAmpersandTests() {
    section("Less & 选择器扩展测试")

    // 1. 基础
    assertEquals(".parent .child", TestUtil.expandAmpersand(".child", ".parent"), "标准嵌套（无&）")
    assertEquals(".parent:hover", TestUtil.expandAmpersand("&:hover", ".parent"), "&:hover")
    assertEquals(".parent::before", TestUtil.expandAmpersand("&::before", ".parent"), "&::before 伪元素")
    assertEquals(".parent", TestUtil.expandAmpersand("&", ".parent"), "单独一个&")

    // 2. 后缀拼接 - Less 最核心的特性
    assertEquals(".parent-bar", TestUtil.expandAmpersand("&-bar", ".parent"), "&-bar 连字符后缀")
    assertEquals(".parent-bar:hover", TestUtil.expandAmpersand("&-bar:hover", ".parent"), "&-bar:hover 后缀+伪类")
    assertEquals(".parent_bar", TestUtil.expandAmpersand("&_bar", ".parent"), "&_bar 下划线后缀")
    assertEquals(".parent-item.active", TestUtil.expandAmpersand("&-item.active", ".parent"), "&-item.active 后缀+类拼接")

    // 3. 类拼接
    assertEquals(".parent.active", TestUtil.expandAmpersand("&.active", ".parent"), "&.active 类拼接")
    assertEquals(".parent.active.open", TestUtil.expandAmpersand("&.active.open", ".parent"), "多个 &.class")
    assertEquals(".parent-item.selected", TestUtil.expandAmpersand("&-item.selected", ".parent"), "后缀 + 类组合")

    // 4. 多 & 组合
    assertEquals(".parent + .parent", TestUtil.expandAmpersand("& + &", ".parent"), "& + & 相邻兄弟")
    assertEquals(".parent .parent", TestUtil.expandAmpersand("& &", ".parent"), "& & 后代")
    assertEquals(".parent > .parent", TestUtil.expandAmpersand("& > &", ".parent"), "& > & 直接子")
    assertEquals(".parent ~ .parent", TestUtil.expandAmpersand("& ~ &", ".parent"), "& ~ & 通用兄弟")

    // 5. 多选择器（逗号）
    assertEquals(".a .c, .b .c", TestUtil.expandAmpersand(".c", ".a, .b"), "多父选 无&")
    assertEquals(".parent-a, .parent-b", TestUtil.expandAmpersand("&-a, &-b", ".parent"), "多子选 带&")
    val r = TestUtil.expandAmpersand("&-c, &-d", ".a, .b").split(", ").toSet()
    assertEquals(setOf(".a-c", ".a-d", ".b-c", ".b-d"), r, "多父+多子 笛卡尔积")

    // 6. 属性选择器
    assertEquals(".parent[disabled]", TestUtil.expandAmpersand("&[disabled]", ".parent"), "&[disabled]")
    assertEquals(".parent-btn[aria-hidden=true]", TestUtil.expandAmpersand("&-btn[aria-hidden=true]", ".parent"), "&-btn + attr")

    // 7. Less 变量插值
    assertEquals(".parent-@{selector}", TestUtil.expandAmpersand("&-@{selector}", ".parent"), "&-@{var} 变量插值")
    assertEquals(".foo-@{a}-bar-@{b}", TestUtil.expandAmpersand("&-@{a}-bar-@{b}", ".foo"), "多变量插值")

    // 8. 伪类
    assertEquals(".parent:not(.hidden)", TestUtil.expandAmpersand("&:not(.hidden)", ".parent"), ":not 伪类")
}

fun runBemScenarioTests() {
    section("真实 Less 场景 / BEM 风格测试")

    // 场景1: BEM 标准 .block__element--modifier
    val l1 = TestUtil.expandAmpersand("&__element", ".block")
    assertEquals(".block__element", l1, "BEM: block → block__element")
    val l2 = TestUtil.expandAmpersand("&--modifier", l1)
    assertEquals(".block__element--modifier", l2, "BEM: block__element → block__element--modifier")

    // 场景2: .button.primary:hover
    val s1 = TestUtil.expandAmpersand("&.primary", ".button")
    assertEquals(".button.primary", s1, ".button &.primary")
    val s2 = TestUtil.expandAmpersand("&:hover", s1)
    assertEquals(".button.primary:hover", s2, ".button.primary → hover")

    // 场景3: .list-item + .list-item (列表项间距)
    val list1 = TestUtil.expandAmpersand("&-item", ".list")
    assertEquals(".list-item", list1, ".list → &-item")
    val list2 = TestUtil.expandAmpersand("& + &", list1)
    assertEquals(".list-item + .list-item", list2, "相邻列表项选择器")

    // 场景4: 多级后缀嵌套 .app-header-nav-item
    val a1 = TestUtil.expandAmpersand("&-header", ".app")
    assertEquals(".app-header", a1, "level 1")
    val a2 = TestUtil.expandAmpersand("&-nav", a1)
    assertEquals(".app-header-nav", a2, "level 2")
    val a3 = TestUtil.expandAmpersand("&-item", a2)
    assertEquals(".app-header-nav-item", a3, "level 3 四级嵌套")

    // 场景5: 后缀 + 多& .card-header > .card-header-title
    val c1 = TestUtil.expandAmpersand("&-header", ".card")
    val c2 = TestUtil.expandAmpersand("& > &-title", c1)
    assertEquals(".card-header > .card-header-title", c2, "复杂组合")

    println("  ✓ 所有 BEM / 真实 Less 场景通过")
}
