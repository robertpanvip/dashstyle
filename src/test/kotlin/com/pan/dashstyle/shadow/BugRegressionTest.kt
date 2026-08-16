package com.pan.dashstyle.shadow

import com.pan.dashstyle.CssColorParser
import com.pan.dashstyle.CssUnitAssistant
import com.pan.dashstyle.FlexLayoutResolver
import com.pan.dashstyle.GridLayoutResolver
import com.pan.dashstyle.ShadowRender
import com.pan.dashstyle.ShadowResolver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * Bug 回归测试 —— 覆盖本轮代码审查中发现的潜在缺陷。
 *
 * 每个 @Test 标注所属 Bug 编号与修复状态。
 */
class BugRegressionTest {

    // ==================================================================
    // Bug #1: ShadowRender.drawOuterShadow — spread 位置缺少 -spread 偏移
    // ==================================================================

    @Test
    fun `bug1 spread zero position is correct`() {
        val layer = ShadowResolver.Layer(
            inset = false, offsetX = 10.0, offsetY = 10.0,
            blur = 0.0, spread = 0.0, color = Color(255, 0, 0, 200)
        )
        val img = ShadowRender.render(100, 100, listOf(layer))
        val cx = (50 + 10 * 0.8).toInt()
        val cy = (50 + 10 * 0.8).toInt()
        val alpha = alphaAt(img, cx, cy)
        assertTrue(alpha > 50, "Expected visible shadow at offset center, got alpha=$alpha")
    }

    @Test
    fun `bug1 spread position should be centered around offset`() {
        val layerNoSpread = ShadowResolver.Layer(
            inset = false, offsetX = 10.0, offsetY = 10.0,
            blur = 0.0, spread = 0.0, color = Color(255, 0, 0, 200)
        )
        val layerWithSpread = ShadowResolver.Layer(
            inset = false, offsetX = 10.0, offsetY = 10.0,
            blur = 0.0, spread = 5.0, color = Color(255, 0, 0, 200)
        )
        val imgNoSpread = ShadowRender.render(100, 100, listOf(layerNoSpread))
        val imgWithSpread = ShadowRender.render(100, 100, listOf(layerWithSpread))
        val spreadRedBottom = redAt(imgWithSpread, 50, 80)
        val noSpreadRedBottom = redAt(imgNoSpread, 50, 80)
        assertTrue(
            spreadRedBottom > noSpreadRedBottom,
            "Bug #1: spread should expand shadow downward too. " +
            "spreadRed=$spreadRedBottom, noSpreadRed=$noSpreadRedBottom"
        )
    }

    // ==================================================================
    // Bug #2: CssColorParser.parseRgb — alpha 百分比用 toInt() 而非 Math.round()
    // ==================================================================

    @Test
    fun `bug2 rgba percentage alpha should round not truncate`() {
        val c = CssColorParser.parse("rgba(0, 0, 0, 50%)")
        assertNotNull(c, "rgba(0,0,0,50%) should parse")
        assertEquals(128, c!!.alpha, "Bug #2: 50% alpha should round to 128, not truncate to 127")
    }

    @Test
    fun `bug2 rgba 25pct alpha should round`() {
        val c = CssColorParser.parse("rgba(0, 0, 0, 25%)")
        assertNotNull(c)
        assertEquals(64, c!!.alpha, "Bug #2: 25% alpha should round to 64")
    }

    @Test
    fun `bug2 rgb channel percentage already uses round`() {
        val c = CssColorParser.parse("rgb(50%, 0%, 0%)")
        assertNotNull(c)
        assertEquals(128, c!!.red, "RGB 50% channel should round to 128")
    }

    // ==================================================================
    // Bug #3: CssColorParser.parseHex 6 位色 — 语义陷阱
    // ==================================================================

    @Test
    fun `bug3 hex 000000 should be black not red`() {
        val c = CssColorParser.parse("#000000")
        assertNotNull(c, "#000000 should parse")
        assertEquals(0, c!!.red, "Bug #3: #000000 red should be 0")
        assertEquals(0, c.green, "Bug #3: #000000 green should be 0")
        assertEquals(0, c.blue, "Bug #3: #000000 blue should be 0")
    }

    @Test
    fun `bug3 hex ffffff should be white`() {
        val c = CssColorParser.parse("#ffffff")
        assertNotNull(c)
        assertEquals(255, c!!.red)
        assertEquals(255, c.green)
        assertEquals(255, c.blue)
    }

    @Test
    fun `bug3 hex ff0000 should be red`() {
        val c = CssColorParser.parse("#ff0000")
        assertNotNull(c)
        assertEquals(255, c!!.red, "Bug #3: #ff0000 red should be 255")
        assertEquals(0, c.green)
        assertEquals(0, c.blue)
    }

    @Test
    fun `bug3 hex 00ff00 should be green`() {
        val c = CssColorParser.parse("#00ff00")
        assertNotNull(c)
        assertEquals(0, c!!.red)
        assertEquals(255, c.green)
        assertEquals(0, c.blue)
    }

    @Test
    fun `bug3 hex 0000ff should be blue`() {
        val c = CssColorParser.parse("#0000ff")
        assertNotNull(c)
        assertEquals(0, c!!.red)
        assertEquals(0, c.green)
        assertEquals(255, c.blue)
    }

    @Test
    fun `bug3 hex 808080 should be gray`() {
        val c = CssColorParser.parse("#808080")
        assertNotNull(c)
        assertEquals(128, c!!.red)
        assertEquals(128, c.green)
        assertEquals(128, c.blue)
    }

    // ==================================================================
    // Bug #4: CssUnitAssistant.format — 0 的特殊处理
    // ==================================================================

    @Test
    fun `bug4 format 0 should return 0`() {
        assertEquals("0", CssUnitAssistant.format(0.0))
    }

    @Test
    fun `bug4 format small value near zero`() {
        val result = CssUnitAssistant.format(0.004)
        assertEquals("0", result)
    }

    @Test
    fun `bug4 format preserves two decimal places`() {
        assertEquals("0.75", CssUnitAssistant.format(0.75))
        assertEquals("0.83", CssUnitAssistant.format(0.8333))
        assertEquals("28.8", CssUnitAssistant.format(28.8))
    }

    // ==================================================================
    // Bug #5: FlexLayoutResolver — wrap + 极端小容器
    // ==================================================================

    @Test
    fun `bug5 flex wrap in tiny container should not crash`() {
        val props = FlexLayoutResolver.Props(
            direction = FlexLayoutResolver.Direction.ROW,
            wrap = true,
            childCount = 5,
            gap = 0
        )
        val boxes = FlexLayoutResolver.place(props, W = 8, H = 100)
        assertEquals(5, boxes.size, "All 5 items should be placed")
        for (i in 0 until 4) {
            assertTrue(boxes[i].y < boxes[i + 1].y, "Items should stack vertically in tiny container")
        }
    }

    @Test
    fun `bug5 flex wrap with column direction and wrap`() {
        val props = FlexLayoutResolver.Props(
            direction = FlexLayoutResolver.Direction.COLUMN,
            wrap = true,
            childCount = 6,
            gap = 0
        )
        val boxes = FlexLayoutResolver.place(props, W = 100, H = 40)
        assertEquals(6, boxes.size)
        for (i in 0 until 5) {
            assertTrue(boxes[i].x < boxes[i + 1].x, "Items should be in separate columns")
        }
    }

    // ==================================================================
    // Bug #6: GridLayoutResolver — 空轨道默认行为
    // ==================================================================

    @Test
    fun `bug6 grid with empty columns should default to 3 flex columns`() {
        val props = GridLayoutResolver.Props(
            columns = emptyList(),
            childCount = 9
        )
        val boxes = GridLayoutResolver.place(props, W = 300, H = 200)
        assertEquals(9, boxes.size)
        assertTrue(boxes[0].x < boxes[1].x && boxes[1].x < boxes[2].x)
        assertEquals(boxes[0].x, boxes[3].x)
    }

    @Test
    fun `bug6 grid childCount exceeds rows should not crash`() {
        val props = GridLayoutResolver.Props(
            columns = listOf(GridLayoutResolver.Track.Flex(1)),
            rows = listOf(GridLayoutResolver.Track.Flex(1)),
            childCount = 100
        )
        val boxes = GridLayoutResolver.place(props, W = 200, H = 200)
        assertEquals(1, boxes.size)
    }

    // ==================================================================
    // Bug #7: TailwindClassResolver — 大小写敏感
    // ==================================================================

    @Test
    fun `bug7 tailwind search is case insensitive`() {
        val results = com.pan.dashstyle.TailwindClassResolver.search("FLEX")
        assertTrue(results.isNotEmpty(), "FLEX should match flex (case insensitive)")
        assertTrue(results.any { it.name == "flex" }, "Should find 'flex' class")
    }

    @Test
    fun `bug7 tailwind find exact is case insensitive`() {
        val found = com.pan.dashstyle.TailwindClassResolver.find("FLEX")
        assertNotNull(found, "FLEX should find 'flex'")
        assertEquals("display: flex", found!!.css)
    }

    // ==================================================================
    // Bug #8: CssColorParser.parseHsl — alpha 用 toInt() 截断
    // ==================================================================

    @Test
    fun `bug8 hsla percentage alpha should round`() {
        val c = CssColorParser.parse("hsla(0, 100%, 50%, 50%)")
        assertNotNull(c, "hsla(0,100%,50%,50%) should parse")
        assertEquals(128, c!!.alpha, "Bug #8: 50% alpha in hsla should round to 128")
    }

    @Test
    fun `bug8 hsla decimal alpha should round`() {
        val c = CssColorParser.parse("hsla(0, 100%, 50%, 0.5)")
        assertNotNull(c)
        assertEquals(128, c!!.alpha, "Bug #8: 0.5 alpha in hsla should round to 128")
    }

    // ==================================================================
    // Bug #9: CssColorParser.hslToRgb — 通道用 toInt() 截断
    // ==================================================================

    @Test
    fun `bug9 hsl red with rounding`() {
        val c = CssColorParser.parse("hsl(0, 100%, 50%)")
        assertNotNull(c)
        assertEquals(255, c!!.red, "Hue 0 should be red")
        assertEquals(0, c.green)
        assertEquals(0, c.blue)
    }

    @Test
    fun `bug9 hsl green with rounding`() {
        val c = CssColorParser.parse("hsl(120, 100%, 50%)")
        assertNotNull(c)
        assertEquals(0, c!!.red)
        assertEquals(255, c.green, "Hue 120 should be green")
        assertEquals(0, c.blue)
    }

    @Test
    fun `bug9 hsl blue with rounding`() {
        val c = CssColorParser.parse("hsl(240, 100%, 50%)")
        assertNotNull(c)
        assertEquals(0, c!!.red)
        assertEquals(0, c.green)
        assertEquals(255, c.blue, "Hue 240 should be blue")
    }

    @Test
    fun `bug9 hsl gray is neutral`() {
        val c = CssColorParser.parse("hsl(0, 0%, 50%)")
        assertNotNull(c)
        assertEquals(128, c!!.red)
        assertEquals(128, c.green)
        assertEquals(128, c.blue)
    }

    // ==================================================================
    // Bug #10: ShadowRender.tintAndComposite — outA=0 除零
    // ==================================================================

    @Test
    fun `bug10 render with fully transparent shadow should not crash`() {
        val layer = ShadowResolver.Layer(
            inset = false, offsetX = 5.0, offsetY = 5.0,
            blur = 0.0, spread = 0.0, color = Color(0, 0, 0, 0)
        )
        val img = ShadowRender.render(100, 100, listOf(layer))
        assertNotNull(img)
        assertEquals(100, img.width)
        assertEquals(100, img.height)
    }

    @Test
    fun `bug10 render with null color should not crash`() {
        val layer = ShadowResolver.Layer(
            inset = false, offsetX = 5.0, offsetY = 5.0,
            blur = 2.0, spread = 0.0, color = null
        )
        val img = ShadowRender.render(100, 100, listOf(layer))
        assertNotNull(img)
    }

    // ==================================================================
    // Bug #11: CssColorParser 边缘情况
    // ==================================================================

    @Test
    fun `bug11 transparent should be black with zero alpha`() {
        val c = CssColorParser.parse("transparent")
        assertNotNull(c)
        assertEquals(0, c!!.alpha)
    }

    @Test
    fun `bug11 named color red should parse`() {
        val c = CssColorParser.parse("red")
        assertNotNull(c)
        assertEquals(255, c!!.red)
        assertEquals(0, c.green)
        assertEquals(0, c.blue)
    }

    @Test
    fun `bug11 named color blue should parse`() {
        val c = CssColorParser.parse("blue")
        assertNotNull(c)
        assertEquals(0, c!!.red)
        assertEquals(0, c.green)
        assertEquals(255, c.blue)
    }

    @Test
    fun `bug11 short hex 3 digit should expand`() {
        val c = CssColorParser.parse("#f00")
        assertNotNull(c)
        assertEquals(255, c!!.red)
        assertEquals(0, c.green)
        assertEquals(0, c.blue)
    }

    @Test
    fun `bug11 short hex 4 digit with alpha`() {
        val c = CssColorParser.parse("#f00f")
        assertNotNull(c)
        assertEquals(255, c!!.red)
        assertEquals(0, c.green)
        assertEquals(0, c.blue)
        assertEquals(255, c.alpha)
    }

    @Test
    fun `bug11 hex 8 digit with alpha`() {
        val c = CssColorParser.parse("#ff000080")
        assertNotNull(c)
        assertEquals(255, c!!.red)
        assertEquals(0, c.green)
        assertEquals(0, c.blue)
        assertEquals(128, c.alpha)
    }

    @Test
    fun `bug11 invalid color returns null`() {
        assertNull(CssColorParser.parse("notacolor"))
        assertNull(CssColorParser.parse(""))
        assertNull(CssColorParser.parse(null))
    }

    // ==================================================================
    // Bug #12: ShadowResolver 边缘情况
    // ==================================================================

    @Test
    fun `bug12 parse none returns empty`() {
        assertEquals(0, ShadowResolver.parse("none").size)
        assertEquals(0, ShadowResolver.parse("").size)
        assertEquals(0, ShadowResolver.parse(null).size)
    }

    @Test
    fun `bug12 parse inset shadow`() {
        val layers = ShadowResolver.parse("inset 2px 2px 4px rgba(0,0,0,0.5)")
        assertEquals(1, layers.size)
        assertTrue(layers[0].inset)
        assertEquals(2.0, layers[0].offsetX)
        assertEquals(2.0, layers[0].offsetY)
        assertEquals(4.0, layers[0].blur)
    }

    @Test
    fun `bug12 parse multiple layers`() {
        val layers = ShadowResolver.parse("2px 2px 0 red, 4px 4px 0 blue")
        assertEquals(2, layers.size)
        assertEquals(2.0, layers[0].offsetX)
        assertEquals(4.0, layers[1].offsetX)
    }

    @Test
    fun `bug12 parse with var should return empty`() {
        val layers = ShadowResolver.parse("2px 2px var(--shadow-color)")
        assertEquals(0, layers.size)
    }

    @Test
    fun `bug12 parse with calc should return empty`() {
        val layers = ShadowResolver.parse("calc(2px + 1px) 2px 0 red")
        assertEquals(0, layers.size)
    }

    @Test
    fun `bug12 parse only offset without color`() {
        val layers = ShadowResolver.parse("2px 4px")
        assertEquals(1, layers.size)
        assertEquals(2.0, layers[0].offsetX)
        assertEquals(4.0, layers[0].offsetY)
        assertNull(layers[0].color)
    }

    @Test
    fun `bug12 parse with blur and spread`() {
        val layers = ShadowResolver.parse("2px 4px 6px 8px #000")
        assertEquals(1, layers.size)
        assertEquals(2.0, layers[0].offsetX)
        assertEquals(4.0, layers[0].offsetY)
        assertEquals(6.0, layers[0].blur)
        assertEquals(8.0, layers[0].spread)
    }

    // ==================================================================
    // Bug #13: CssUnitAssistant 边缘情况
    // ==================================================================

    @Test
    fun `bug13 parseLength with units`() {
        val l = CssUnitAssistant.parseLength("16px")
        assertNotNull(l)
        assertEquals(16.0, l!!.value)
        assertEquals("px", l.unit)
    }

    @Test
    fun `bug13 parseLength rem`() {
        val l = CssUnitAssistant.parseLength("1.5rem")
        assertNotNull(l)
        assertEquals(1.5, l!!.value)
        assertEquals("rem", l.unit)
    }

    @Test
    fun `bug13 parseLength vw`() {
        val l = CssUnitAssistant.parseLength("50vw")
        assertNotNull(l)
        assertEquals(50.0, l!!.value)
        assertEquals("vw", l.unit)
    }

    @Test
    fun `bug13 parseLength invalid returns null`() {
        assertNull(CssUnitAssistant.parseLength("abc"))
        assertNull(CssUnitAssistant.parseLength(""))
        assertNull(CssUnitAssistant.parseLength(null))
        assertNull(CssUnitAssistant.parseLength("12"))
    }

    @Test
    fun `bug13 clamp hint computes correctly`() {
        val hint = CssUnitAssistant.clampHint("clamp(16px, 2vw, 24px)")
        assertNotNull(hint)
        assertTrue(hint!!.contains("24"), "Should clamp to max 24px, got: $hint")
    }

    @Test
    fun `bug13 calc hint simplifies`() {
        val hint = CssUnitAssistant.calcHint("calc(100% - 20px)")
        assertNotNull(hint)
        assertTrue(hint!!.contains("px"), "Should return px value")
    }

    @Test
    fun `bug13 calc with multiplication`() {
        val hint = CssUnitAssistant.calcHint("calc(2 * 16px)")
        assertNotNull(hint)
        assertTrue(hint!!.contains("32"), "2*16px should be 32px, got: $hint")
    }

    @Test
    fun `bug13 calc with nested parens`() {
        val hint = CssUnitAssistant.calcHint("calc((100% - 40px) / 2)")
        assertNotNull(hint)
        assertTrue(hint!!.contains("700"), "Should be 700px, got: $hint")
    }

    // ==================================================================
    // Bug #14: GridLayoutResolver 轨道解析边缘情况
    // ==================================================================

    @Test
    fun `bug14 grid parse repeat track`() {
        val tracks = GridLayoutResolver.parseTrackList("repeat(3, 1fr)")
        assertEquals(3, tracks.size)
        assertTrue(tracks.all { it is GridLayoutResolver.Track.Flex && it.weight == 1 })
    }

    @Test
    fun `bug14 grid parse minmax track`() {
        val track = GridLayoutResolver.parseTrack("minmax(100px, 1fr)")
        assertNotNull(track)
        assertTrue(track is GridLayoutResolver.Track.Fixed && track.px == 100)
    }

    @Test
    fun `bug14 grid parse auto track`() {
        val track = GridLayoutResolver.parseTrack("auto")
        assertTrue(track is GridLayoutResolver.Track.Auto)
    }

    @Test
    fun `bug14 grid parse mixed tracks`() {
        val tracks = GridLayoutResolver.parseTrackList("100px 1fr auto 2fr")
        assertEquals(4, tracks.size)
        assertTrue(tracks[0] is GridLayoutResolver.Track.Fixed)
        assertTrue(tracks[1] is GridLayoutResolver.Track.Flex)
        assertTrue(tracks[2] is GridLayoutResolver.Track.Auto)
        assertTrue(tracks[3] is GridLayoutResolver.Track.Flex)
    }

    @Test
    fun `bug14 grid resize tracks fr to fr`() {
        val tracks = listOf(GridLayoutResolver.Track.Flex(1), GridLayoutResolver.Track.Flex(1))
        val result = GridLayoutResolver.resizeAdjacentTracks(tracks, 0, delta = 1)
        assertEquals(2, (result[0] as GridLayoutResolver.Track.Flex).weight)
        assertEquals(1, (result[1] as GridLayoutResolver.Track.Flex).weight)
    }

    @Test
    fun `bug14 grid resize tracks px to px`() {
        val tracks = listOf(GridLayoutResolver.Track.Fixed(100), GridLayoutResolver.Track.Fixed(100))
        val result = GridLayoutResolver.resizeAdjacentTracks(tracks, 0, delta = 20)
        assertEquals(120, (result[0] as GridLayoutResolver.Track.Fixed).px)
        assertEquals(80, (result[1] as GridLayoutResolver.Track.Fixed).px)
    }

    @Test
    fun `bug14 grid resize tracks out of bounds returns original`() {
        val tracks = listOf(GridLayoutResolver.Track.Flex(1))
        val result = GridLayoutResolver.resizeAdjacentTracks(tracks, 0, delta = 1)
        assertEquals(tracks, result)
    }

    @Test
    fun `bug14 grid resize tracks delta zero returns original`() {
        val tracks = listOf(GridLayoutResolver.Track.Flex(1), GridLayoutResolver.Track.Flex(1))
        val result = GridLayoutResolver.resizeAdjacentTracks(tracks, 0, delta = 0)
        assertEquals(tracks, result)
    }

    // ==================================================================
    // Bug #15: FlexLayoutResolver align-self 边缘情况
    // ==================================================================

    @Test
    fun `bug15 flex align self overrides container align`() {
        val props = FlexLayoutResolver.Props(
            direction = FlexLayoutResolver.Direction.ROW,
            align = FlexLayoutResolver.Align.STRETCH,
            childCount = 3,
            alignSelfs = listOf(FlexLayoutResolver.Align.FLEX_START, null, FlexLayoutResolver.Align.FLEX_END)
        )
        val boxes = FlexLayoutResolver.place(props, W = 200, H = 200)
        assertEquals(3, boxes.size)
        assertTrue(boxes[0].h < boxes[1].h, "align-self:flex-start should not stretch, so height < stretched")
        assertTrue(boxes[2].y > boxes[1].y, "align-self:flex-end should be at bottom")
    }

    @Test
    fun `bug15 flex align self partial list uses container align for rest`() {
        val props = FlexLayoutResolver.Props(
            direction = FlexLayoutResolver.Direction.ROW,
            align = FlexLayoutResolver.Align.CENTER,
            childCount = 5,
            alignSelfs = listOf(FlexLayoutResolver.Align.FLEX_START)
        )
        val boxes = FlexLayoutResolver.place(props, W = 300, H = 200)
        assertEquals(5, boxes.size)
        assertTrue(boxes[0].y < boxes[1].y, "align-self:flex-start should be higher than center-aligned items")
    }

    // ==================================================================
    // Bug #16: UnusedCssModuleClassInspection.MODULE_CLASS_RE 使用了错误的捕获组
    // ==================================================================

    @Test
    fun `bug16 MODULE_CLASS_RE group 2 should be class name not group 1`() {
        val re = Regex("""(^|[^\w-])\.-?([_a-zA-Z][_a-zA-Z0-9-]*)(?=[^\w-]|${'$'})""")
        val m1 = re.find(".foo-bar")!!
        assertEquals("", m1.groupValues[1], "Group 1 is the prefix anchor, should be empty")
        assertEquals("foo-bar", m1.groupValues[2], "Group 2 should be the class name")

        val m2 = re.find(" .foo-bar")!!
        assertEquals(" ", m2.groupValues[1], "Group 1 is the space prefix")
        assertEquals("foo-bar", m2.groupValues[2], "Group 2 should be the class name regardless of prefix")

        val m3 = re.find(".a .b")!!
        assertEquals("", m3.groupValues[1], "Group 1 for first class should be empty")
        assertEquals("a", m3.groupValues[2], "First class name should be 'a'")
    }

    // ==================================================================
    // Bug #17: GridLayoutResolver.resizeAdjacentTracks 单侧 fr 逻辑反了
    // ==================================================================

    @Test
    fun `bug17 resize left fixed right fr delta positive should decrease right`() {
        val tracks = listOf(
            GridLayoutResolver.Track.Fixed(100),
            GridLayoutResolver.Track.Flex(2)
        )
        val result = GridLayoutResolver.resizeAdjacentTracks(tracks, 0, delta = 1)
        assertEquals(
            1, (result[1] as GridLayoutResolver.Track.Flex).weight,
            "Bug #17: right FR should decrease when delta > 0 (divider moves right)"
        )
    }

    @Test
    fun `bug17 resize left fixed right fr delta negative should increase right`() {
        val tracks = listOf(
            GridLayoutResolver.Track.Fixed(100),
            GridLayoutResolver.Track.Flex(2)
        )
        val result = GridLayoutResolver.resizeAdjacentTracks(tracks, 0, delta = -1)
        assertEquals(
            3, (result[1] as GridLayoutResolver.Track.Flex).weight,
            "Bug #17: right FR should increase when delta < 0 (divider moves left)"
        )
    }

    @Test
    fun `bug17 resize left fr right fixed delta positive should increase left`() {
        val tracks = listOf(
            GridLayoutResolver.Track.Flex(2),
            GridLayoutResolver.Track.Fixed(100)
        )
        val result = GridLayoutResolver.resizeAdjacentTracks(tracks, 0, delta = 1)
        assertEquals(3, (result[0] as GridLayoutResolver.Track.Flex).weight)
    }

    // ==================================================================
    // Bug #18: CssUnitAssistant.toPx 将 % 当成视口宽度
    // ==================================================================

    @Test
    fun `bug18 toPx treats percent as viewport width`() {
        val len = CssUnitAssistant.Length(50.0, "%")
        assertEquals(720.0, CssUnitAssistant.toPx(len, viewport = 1440.0))
        assertEquals(960.0, CssUnitAssistant.toPx(len, viewport = 1920.0))
    }

    // ==================================================================
    // Bug #19: Util.camelToKebab 和 kebabToCamel 边缘情况
    // ==================================================================

    @Test
    fun `bug19 camelToKebab handles all uppercase`() {
        assertEquals("A-B-C", com.pan.dashstyle.Util.camelToKebab("ABC"))
        assertEquals("H-T-T-P", com.pan.dashstyle.Util.camelToKebab("HTTP"))
    }

    @Test
    fun `bug19 camelToKebab handles mixed case`() {
        assertEquals("foo-bar", com.pan.dashstyle.Util.camelToKebab("fooBar"))
        assertEquals("http-server", com.pan.dashstyle.Util.camelToKebab("HTTPServer"))
        assertEquals("xml-parser", com.pan.dashstyle.Util.camelToKebab("XMLParser"))
        assertEquals("my-http-server", com.pan.dashstyle.Util.camelToKebab("myHTTPServer"))
    }

    @Test
    fun `bug19 camelToKebab already kebab is unchanged`() {
        assertEquals("foo-bar", com.pan.dashstyle.Util.camelToKebab("foo-bar"))
        assertEquals("abc", com.pan.dashstyle.Util.camelToKebab("abc"))
    }

    @Test
    fun `bug19 kebabToCamel roundtrip`() {
        assertEquals("fooBar", com.pan.dashstyle.Util.kebabToCamel("foo-bar"))
        assertEquals("abc", com.pan.dashstyle.Util.kebabToCamel("abc"))
        assertEquals("httpServer", com.pan.dashstyle.Util.kebabToCamel("http-server"))
        assertEquals("fooBar", com.pan.dashstyle.Util.kebabToCamel("-foo-bar"))
    }

    // ==================================================================
    // Bug #20: FlexLayoutResolver 极小容器下 SPACE_* 分布产生负 gap
    // ==================================================================

    @Test
    fun `bug20 flex space between in tiny container should not crash`() {
        val props = FlexLayoutResolver.Props(
            direction = FlexLayoutResolver.Direction.ROW,
            justify = FlexLayoutResolver.Justify.SPACE_BETWEEN,
            childCount = 5,
            gap = 0
        )
        val boxes = FlexLayoutResolver.place(props, W = 20, H = 100)
        assertEquals(5, boxes.size)
    }

    @Test
    fun `bug20 flex space evenly in tiny container should not crash`() {
        val props = FlexLayoutResolver.Props(
            direction = FlexLayoutResolver.Direction.ROW,
            justify = FlexLayoutResolver.Justify.SPACE_EVENLY,
            childCount = 5,
            gap = 0
        )
        val boxes = FlexLayoutResolver.place(props, W = 20, H = 100)
        assertEquals(5, boxes.size)
    }

    // ==================================================================
    // Bug #21: CssUnitAssistant.format 负值处理
    // ==================================================================

    @Test
    fun `bug21 format handles negative values`() {
        assertEquals("-0.5", CssUnitAssistant.format(-0.5))
        assertEquals("-1", CssUnitAssistant.format(-1.0))
        assertEquals("-1.25", CssUnitAssistant.format(-1.25))
    }

    @Test
    fun `bug21 format handles zero`() {
        assertEquals("0", CssUnitAssistant.format(0.0))
        assertEquals("0", CssUnitAssistant.format(-0.0))
    }

    // ==================================================================
    // Bug #22: CssColorParser.parseHsl alpha 计算中 removeSuffix/endsWith 逻辑验证
    // ==================================================================

    @Test
    fun `bug22 hsl alpha with percentage uses removeSuffix then checks raw endsWith`() {
        val c = CssColorParser.parse("hsla(0, 100%, 50%, 50%)")
        assertNotNull(c)
        assertEquals(128, c!!.alpha)
    }

    @Test
    fun `bug22 hsl alpha with decimal uses correct path`() {
        val c = CssColorParser.parse("hsla(0, 100%, 50%, 0.5)")
        assertNotNull(c)
        assertEquals(128, c!!.alpha)
    }

    // ==================================================================
    // Bug #23: CssColorParser.parseRgb 非百分比通道用 toInt() 截断
    // ==================================================================

    @Test
    fun `bug23 rgb non percentage channels use truncation`() {
        val c = CssColorParser.parse("rgb(127.9, 0, 0)")
        assertNotNull(c)
        assertEquals(127, c!!.red, "127.9 truncated to 127 by toInt()")
    }

    @Test
    fun `bug23 rgb integer channels work correctly`() {
        val c = CssColorParser.parse("rgb(255, 128, 0)")
        assertNotNull(c)
        assertEquals(255, c!!.red)
        assertEquals(128, c.green)
        assertEquals(0, c.blue)
    }

    @Test
    fun `bug23 rgb out of range channels are clamped`() {
        val c = CssColorParser.parse("rgb(300, -10, 500)")
        assertNotNull(c)
        assertEquals(255, c!!.red, "300 should be clamped to 255")
        assertEquals(0, c.green, "-10 should be clamped to 0")
        assertEquals(255, c.blue, "500 should be clamped to 255")
    }

    // ==================================================================
    // 辅助方法
    // ==================================================================

    private fun alphaAt(img: BufferedImage, x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= img.width || y >= img.height) return 0
        return (img.getRGB(x, y) ushr 24) and 0xff
    }

    private fun redAt(img: BufferedImage, x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= img.width || y >= img.height) return 0
        return (img.getRGB(x, y) shr 16) and 0xff
    }
}