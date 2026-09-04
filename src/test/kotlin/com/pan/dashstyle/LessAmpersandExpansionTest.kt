package com.pan.dashstyle

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 测试 expandAmpersand 函数 - 覆盖 Less/SCSS 的各种 & 用法
 */
class LessAmpersandExpansionTest {

    // ===================== 1. 基本 & 替换 =====================

    @Test
    fun `expandAmpersand - 基本替换，无&的标准嵌套`() {
        assertEquals(".parent .child", CssSelectorUtil.expandAmpersand(".child", ".parent"))
    }

    @Test
    fun `expandAmpersand - 基本 & 替换成父选择器`() {
        assertEquals(".parent:hover", CssSelectorUtil.expandAmpersand("&:hover", ".parent"))
    }

    @Test
    fun `expandAmpersand - 伪元素`() {
        assertEquals(".parent::before", CssSelectorUtil.expandAmpersand("&::before", ".parent"))
    }

    @Test
    fun `expandAmpersand - 多伪类链式`() {
        assertEquals(".parent:hover:active", CssSelectorUtil.expandAmpersand("&:hover:active", ".parent"))
    }

    @Test
    fun `expandAmpersand - 单独一个 &`() {
        assertEquals(".parent", CssSelectorUtil.expandAmpersand("&", ".parent"))
    }

    // ===================== 2. 后缀拼接 &-suffix (Less 最常用特性之一) =====================

    @Test
    fun `expandAmpersand - 连字符后缀拼接 &-bar`() {
        assertEquals(".parent-bar", CssSelectorUtil.expandAmpersand("&-bar", ".parent"))
    }

    @Test
    fun `expandAmpersand - 连字符后缀 + 伪类`() {
        assertEquals(".parent-bar:hover", CssSelectorUtil.expandAmpersand("&-bar:hover", ".parent"))
    }

    @Test
    fun `expandAmpersand - 多层后缀拼接`() {
        // 模拟 .parent { &-bar { &-baz {} } } → 先 expand &-bar → .parent-bar，再 expand &-baz → .parent-bar-baz
        val level1 = CssSelectorUtil.expandAmpersand("&-bar", ".parent")
        assertEquals(".parent-bar", level1)
        val level2 = CssSelectorUtil.expandAmpersand("&-baz", level1)
        assertEquals(".parent-bar-baz", level2)
    }

    @Test
    fun `expandAmpersand - 三级嵌套 &-x 组合`() {
        // .a { &-b { &-c {} } }
        val l1 = CssSelectorUtil.expandAmpersand("&-b", ".a")
        assertEquals(".a-b", l1)
        val l2 = CssSelectorUtil.expandAmpersand("&-c", l1)
        assertEquals(".a-b-c", l2)
    }

    @Test
    fun `expandAmpersand - 下划线后缀拼接 &_bar`() {
        assertEquals(".parent_bar", CssSelectorUtil.expandAmpersand("&_bar", ".parent"))
    }

    @Test
    fun `expandAmpersand - 下划线后缀 + 其他`() {
        assertEquals(".parent_bar.baz", CssSelectorUtil.expandAmpersand("&_bar.baz", ".parent"))
    }

    @Test
    fun `expandAmpersand - 后缀与类组合`() {
        // .parent { &-item.active {} } → .parent-item.active
        assertEquals(".parent-item.active", CssSelectorUtil.expandAmpersand("&-item.active", ".parent"))
    }

    // ===================== 3. 类名拼接 &.className =====================

    @Test
    fun `expandAmpersand - 和 点 class 拼接`() {
        assertEquals(".parent.active", CssSelectorUtil.expandAmpersand("&.active", ".parent"))
    }

    @Test
    fun `expandAmpersand - 多个拼接类`() {
        assertEquals(".parent.active.open", CssSelectorUtil.expandAmpersand("&.active.open", ".parent"))
    }

    @Test
    fun `expandAmpersand - 后缀 + 类`() {
        assertEquals(".parent-item.selected", CssSelectorUtil.expandAmpersand("&-item.selected", ".parent"))
    }

    // ===================== 4. 多 & 组合 =====================

    @Test
    fun `expandAmpersand - & + & 相邻兄弟`() {
        assertEquals(".parent + .parent", CssSelectorUtil.expandAmpersand("& + &", ".parent"))
    }

    @Test
    fun `expandAmpersand - & & 后代选择器`() {
        assertEquals(".parent .parent", CssSelectorUtil.expandAmpersand("& &", ".parent"))
    }

    @Test
    fun `expandAmpersand - 和 子箭头 和 直接子元素`() {
        assertEquals(".parent > .parent", CssSelectorUtil.expandAmpersand("& > &", ".parent"))
    }

    @Test
    fun `expandAmpersand - & ~ & 通用兄弟`() {
        assertEquals(".parent ~ .parent", CssSelectorUtil.expandAmpersand("& ~ &", ".parent"))
    }

    // ===================== 5. 多选择器（逗号分隔） =====================

    @Test
    fun `expandAmpersand - 多父选择器`() {
        // .a, .b { .c {} } → .a .c, .b .c
        assertEquals(".a .c, .b .c", CssSelectorUtil.expandAmpersand(".c", ".a, .b"))
    }

    @Test
    fun `expandAmpersand - 多子选择器 带&`() {
        // .parent { &-a, &-b {} } → .parent-a, .parent-b
        assertEquals(".parent-a, .parent-b", CssSelectorUtil.expandAmpersand("&-a, &-b", ".parent"))
    }

    @Test
    fun `expandAmpersand - 多父选 + 多子选 无&`() {
        // .a, .b { .c, .d {} } → 笛卡尔积: .a .c, .a .d, .b .c, .b .d
        val result = CssSelectorUtil.expandAmpersand(".c, .d", ".a, .b")
        val parts = result.split(", ").toSet()
        assertEquals(setOf(".a .c", ".a .d", ".b .c", ".b .d"), parts)
    }

    @Test
    fun `expandAmpersand - 多父选 + 多子选 带&`() {
        // .a, .b { &-c, &-d {} } → .a-c, .a-d, .b-c, .b-d
        val result = CssSelectorUtil.expandAmpersand("&-c, &-d", ".a, .b")
        val parts = result.split(", ").toSet()
        assertEquals(setOf(".a-c", ".a-d", ".b-c", ".b-d"), parts)
    }

    // ===================== 6. 复杂嵌套组合场景 =====================

    @Test
    fun `expandAmpersand - 真实 Less 场景1 - BEM 风格`() {
        // .block { &__element { &--modifier {} } }
        val l1 = CssSelectorUtil.expandAmpersand("&__element", ".block")
        assertEquals(".block__element", l1)
        val l2 = CssSelectorUtil.expandAmpersand("&--modifier", l1)
        assertEquals(".block__element--modifier", l2)
    }

    @Test
    fun `expandAmpersand - 真实 Less 场景2 - 状态`() {
        // .button { &.primary { &:hover {} } }
        val l1 = CssSelectorUtil.expandAmpersand("&.primary", ".button")
        assertEquals(".button.primary", l1)
        val l2 = CssSelectorUtil.expandAmpersand("&:hover", l1)
        assertEquals(".button.primary:hover", l2)
    }

    @Test
    fun `expandAmpersand - 真实 Less 场景3 - 嵌套关系`() {
        // .list { &-item { & + & {} } }
        val l1 = CssSelectorUtil.expandAmpersand("&-item", ".list")
        assertEquals(".list-item", l1)
        val l2 = CssSelectorUtil.expandAmpersand("& + &", l1)
        assertEquals(".list-item + .list-item", l2)
    }

    @Test
    fun `expandAmpersand - 真实 Less 场景4 - 嵌套多级后缀`() {
        // .app { &-header { &-nav { &-item {} } } }
        val l1 = CssSelectorUtil.expandAmpersand("&-header", ".app")
        assertEquals(".app-header", l1)
        val l2 = CssSelectorUtil.expandAmpersand("&-nav", l1)
        assertEquals(".app-header-nav", l2)
        val l3 = CssSelectorUtil.expandAmpersand("&-item", l2)
        assertEquals(".app-header-nav-item", l3)
    }

    @Test
    fun `expandAmpersand - 真实 Less 场景5 - BEM 双下划线`() {
        // .menu { &__item { &__icon {} } }
        val l1 = CssSelectorUtil.expandAmpersand("&__item", ".menu")
        assertEquals(".menu__item", l1)
        val l2 = CssSelectorUtil.expandAmpersand("&__icon", l1)
        assertEquals(".menu__item__icon", l2)
    }

    // ===================== 7. 属性选择器 =====================

    @Test
    fun `expandAmpersand - 属性选择器在&后`() {
        assertEquals(".parent[disabled]", CssSelectorUtil.expandAmpersand("&[disabled]", ".parent"))
    }

    @Test
    fun `expandAmpersand - 后缀 + 属性选择器`() {
        assertEquals(".parent-btn[aria-hidden=true]", CssSelectorUtil.expandAmpersand("&-btn[aria-hidden=true]", ".parent"))
    }

    // ===================== 8. Less 变量插值 @{var} =====================

    @Test
    fun `expandAmpersand - 变量插值保留原文`() {
        // Less: @selector: foo; .parent { &-@{selector} {} }
        val result = CssSelectorUtil.expandAmpersand("&-@{selector}", ".parent")
        // 变量值我们无法在纯字符串层面解析，但 & 必须被正确替换
        assertEquals(".parent-@{selector}", result)
    }

    @Test
    fun `expandAmpersand - 纯变量插值选择器 无&`() {
        // .parent { @{selector} {} } → 当作普通嵌套
        assertEquals(".parent @{selector}", CssSelectorUtil.expandAmpersand("@{selector}", ".parent"))
    }

    @Test
    fun `expandAmpersand - 多个变量插值`() {
        assertEquals(".foo-@{a}-bar-@{b}", CssSelectorUtil.expandAmpersand("&-@{a}-bar-@{b}", ".foo"))
    }

    // ===================== 9. 复杂混合场景 =====================

    @Test
    fun `expandAmpersand - 综合场景 - 后缀 + 多&`() {
        // .card { &-header { & > &-title {} } }  这种实际不太常见但测试一下
        val l1 = CssSelectorUtil.expandAmpersand("&-header", ".card")
        assertEquals(".card-header", l1)
        // & > &-title  → .card-header > .card-header-title
        val l2 = CssSelectorUtil.expandAmpersand("& > &-title", l1)
        assertEquals(".card-header > .card-header-title", l2)
    }

    @Test
    fun `expandAmpersand - not 伪类`() {
        assertEquals(".parent:not(.hidden)", CssSelectorUtil.expandAmpersand("&:not(.hidden)", ".parent"))
    }

    @Test
    fun `expandAmpersand - is 伪类`() {
        assertEquals(".parent:is(.a, .b)", CssSelectorUtil.expandAmpersand("&:is(.a, .b)", ".parent"))
    }

    // ===================== 10. replaceAmpersandInPart 内部函数直接测试 =====================

    @Test
    fun `replaceAmpersandInPart - 基本替换`() {
        assertEquals(".foo:hover", CssSelectorUtil.replaceAmpersandInPart("&:hover", ".foo"))
    }

    @Test
    fun `replaceAmpersandInPart - 连字符后缀`() {
        assertEquals(".foo-bar", CssSelectorUtil.replaceAmpersandInPart("&-bar", ".foo"))
    }

    @Test
    fun `replaceAmpersandInPart - 下划线后缀`() {
        assertEquals(".foo_bar", CssSelectorUtil.replaceAmpersandInPart("&_bar", ".foo"))
    }

    @Test
    fun `replaceAmpersandInPart - 双&`() {
        assertEquals(".foo + .foo", CssSelectorUtil.replaceAmpersandInPart("& + &", ".foo"))
    }

    @Test
    fun `replaceAmpersandInPart - 无&原样返回`() {
        assertEquals(".bar", CssSelectorUtil.replaceAmpersandInPart(".bar", ".foo"))
    }

    @Test
    fun `replaceAmpersandInPart - 类拼接`() {
        assertEquals(".foo.active", CssSelectorUtil.replaceAmpersandInPart("&.active", ".foo"))
    }

    // ------ 一些真实的 BEM/组合场景 ------
    @Test
    fun `expandAmpersand - 多父多子 笛卡尔积 顺序`() {
        val out = CssSelectorUtil.expandAmpersand("&--active, &--hover", ".btn, .link")
        val parts = out.split(", ").map { it.trim() }.toSet()
        val expected = setOf(".btn--active", ".btn--hover", ".link--active", ".link--hover")
        assertEquals(expected, parts)
    }

    @Test
    fun `expandAmpersand - BEM underscore 修饰符`() {
        assertEquals(".block__elem", CssSelectorUtil.expandAmpersand("&__elem", ".block"))
    }

    @Test
    fun `expandAmpersand - 伪类拼接类`() {
        assertEquals(".btn.btn-primary:hover", CssSelectorUtil.expandAmpersand("&.btn-primary:hover", ".btn"))
    }

    @Test
    fun `expandAmpersand - 逗号父 + 简单子 逗号分隔输出`() {
        val out = CssSelectorUtil.expandAmpersand("&.open", ".nav, .menu")
        val parts = out.split(", ").map { it.trim() }.toSet()
        assertEquals(setOf(".nav.open", ".menu.open"), parts)
    }
}
