package com.pan.dashstyle

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TailwindClassResolver 纯逻辑单测（不依赖 IDE 沙箱）。
 */
class TailwindClassResolverTest {

    @Test
    fun `built-in list is non empty and sorted`() {
        val all = TailwindClassResolver.all
        assertTrue(all.size > 100)
        assertEquals(all.sortedBy { it.name }.map { it.name }, all.map { it.name })
    }

    @Test
    fun `every class has css remarks`() {
        TailwindClassResolver.all.forEach {
            assertTrue(it.css.isNotBlank(), "class ${it.name} 缺少 CSS 预览")
            assertTrue(it.group.isNotBlank(), "class ${it.name} 缺少分组")
        }
    }

    @Test
    fun `search with empty prefix returns all`() {
        assertEquals(TailwindClassResolver.all.size, TailwindClassResolver.search("").size)
        assertEquals(TailwindClassResolver.all.size, TailwindClassResolver.search("  ").size)
    }

    @Test
    fun `search matches prefix case insensitive`() {
        val hits = TailwindClassResolver.search("flex")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.name.startsWith("flex") })
        // 大小写不敏感
        assertTrue(TailwindClassResolver.search("FLEX").isNotEmpty())
    }

    @Test
    fun `search partial prefix returns subset`() {
        val flexCol = TailwindClassResolver.search("flex-col")
        assertTrue(flexCol.all { it.name.startsWith("flex-col") })
        assertTrue(flexCol.any { it.name == "flex-col" })
    }

    @Test
    fun `search unknown prefix returns empty`() {
        assertTrue(TailwindClassResolver.search("zzzz-not-exist-xyz").isEmpty())
    }

    @Test
    fun `find exact name returns class with css remarks`() {
        val c = TailwindClassResolver.find("flex")
        assertEquals("flex", c?.name)
        assertEquals("display: flex", c?.css)
    }

    @Test
    fun `find is case insensitive and trims`() {
        assertEquals("flex", TailwindClassResolver.find("  FLEX ")?.name)
        assertTrue(TailwindClassResolver.find("no-such-class") == null)
    }

    @Test
    fun `common classes exist with correct css remarks`() {
        // 抽查几个关键类
        assertEquals("display: flex", TailwindClassResolver.find("flex")?.css)
        assertEquals("flex-direction: column", TailwindClassResolver.find("flex-col")?.css)
        assertEquals("align-items: center", TailwindClassResolver.find("items-center")?.css)
        assertEquals("justify-content: center", TailwindClassResolver.find("justify-center")?.css)
        assertEquals("padding: 1rem", TailwindClassResolver.find("p-4")?.css)
        assertEquals("width: 100%", TailwindClassResolver.find("w-full")?.css)
        assertEquals("font-weight: 700", TailwindClassResolver.find("font-bold")?.css)
        assertEquals("color: #3b82f6", TailwindClassResolver.find("text-blue-500")?.css)
        assertEquals("background-color: #ef4444", TailwindClassResolver.find("bg-red-500")?.css)
        assertEquals("border-radius: 0.375rem", TailwindClassResolver.find("rounded-md")?.css)
        assertTrue(TailwindClassResolver.find("shadow-md")!!.css.startsWith("box-shadow"))
    }
}