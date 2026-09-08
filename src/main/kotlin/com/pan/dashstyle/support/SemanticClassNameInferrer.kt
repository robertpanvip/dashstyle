package com.pan.dashstyle.support

import com.intellij.lang.javascript.psi.*
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.util.PsiTreeUtil

/**
 * 语义化类名推断引擎：
 *  根据 (1) 组件自身命名 (2) 父/兄弟组件的 className (3) inlineStyle 本身的属性语义，
 *  产出一组 "从高到低" 的候选 kebab-case 类名。
 *
 *  在 QuickFix 触发后作为重命名对话框的默认值 (Top candidate)。
 */
object SemanticClassNameInferrer {

    // 样式属性 → 语义类名词汇表 (kebab-case，可组合)
    private val LAYOUT_HINT = mapOf(
        // display / position
        "display:flex" to listOf("flex", "flexbox", "container"),
        "display:grid" to listOf("grid", "grid-container"),
        "display:inline-flex" to listOf("inline-flex"),
        "display:inline-block" to listOf("inline-block"),
        "display:block" to listOf("block"),
        "display:none" to listOf("hidden", "hide"),
        "position:absolute" to listOf("overlay", "floating", "absolute"),
        "position:fixed" to listOf("sticky", "fixed", "overlay"),
        "position:relative" to listOf("relative", "wrapper"),
        "position:sticky" to listOf("sticky"),
        // justify / align
        "justify-content:center" to listOf("center", "centered"),
        "justify-content:space-between" to listOf("between", "space-between"),
        "align-items:center" to listOf("center", "middle", "v-center"),
        // flex-direction
        "flex-direction:column" to listOf("col", "column", "stack"),
        "flex-direction:row" to listOf("row", "horizontal"),
        "flex:1" to listOf("grow", "flex-1", "flexible"),
        // gap
        "gap" to listOf("gap", "spaced"),
        // size
        "width:100%" to listOf("full", "full-width", "w-full"),
        "height:100%" to listOf("full", "full-height", "h-full"),
        // overflow
        "overflow:auto" to listOf("scrollable", "overflow-auto"),
        "overflow:hidden" to listOf("clipped", "overflow-hidden", "no-scroll"),
        "overflow:scroll" to listOf("scrollable")
    )

    private val VISUAL_HINT = mapOf(
        // 颜色语义
        "color:red" to listOf("danger", "error", "text-danger"),
        "color:#f00" to listOf("danger", "error"),
        "color:green" to listOf("success", "ok"),
        "color:#0f0" to listOf("success"),
        "color:blue" to listOf("info", "primary"),
        "color:#00f" to listOf("info", "primary"),
        "background:red" to listOf("bg-danger", "danger-bg"),
        "background:#fff" to listOf("bg-white", "surface"),
        "background:transparent" to listOf("bg-transparent"),
        // 字体权重
        "font-weight:bold" to listOf("bold", "strong", "heavy"),
        "font-weight:600" to listOf("semibold"),
        "font-weight:500" to listOf("medium"),
        "font-size:24" to listOf("large", "lg"),
        "font-size:12" to listOf("small", "sm", "tiny"),
        // 边框/圆角
        "border-radius:50%" to listOf("round", "circle", "avatar"),
        "border-radius" to listOf("rounded", "radius"),
        "border:1" to listOf("bordered", "outlined"),
        // 阴影
        "box-shadow" to listOf("shadow", "elevated", "card"),
        // 鼠标
        "cursor:pointer" to listOf("clickable", "pointer", "interactive"),
        "cursor:not-allowed" to listOf("disabled"),
        // 变换
        "transform" to listOf("animated", "transformed"),
        "opacity:0" to listOf("transparent", "fade-out", "invisible"),
        "opacity:0.5" to listOf("semi-transparent", "muted"),
        "text-align:center" to listOf("center", "text-center"),
        "text-decoration:underline" to listOf("underline", "link"),
        "white-space:nowrap" to listOf("no-wrap", "truncate"),
        "user-select:none" to listOf("unselectable", "noselect")
    )

    // 组件级 "常用通用角色" 语义（基于 JSX 标签名 / 组件名）
    private val TAG_ROLE_HINT = mapOf(
        "button" to listOf("btn", "button"),
        "a" to listOf("link"),
        "img" to listOf("image", "img", "avatar"),
        "input" to listOf("field", "input"),
        "textarea" to listOf("field", "textarea"),
        "select" to listOf("select", "dropdown"),
        "form" to listOf("form"),
        "label" to listOf("label"),
        "nav" to listOf("nav", "navigation"),
        "header" to listOf("header"),
        "footer" to listOf("footer"),
        "main" to listOf("main"),
        "section" to listOf("section"),
        "aside" to listOf("sidebar", "aside"),
        "article" to listOf("article"),
        "div" to emptyList(),
        "span" to emptyList()
    )

    data class Candidate(val name: String, val score: Int, val source: String)

    /**
     * 主入口：给定 style 属性的父 JSXAttribute (整个 style={...})，
     * 以及解析出的 CSS 声明列表 (已经由 convertInlineStyleToCss 产出的行级字符串)，
     * 产出有序候选类名列表 (最高优先度先)。
     */
    fun inferCandidates(
        styleAttrElement: PsiElement,
        cssDeclarations: String,
        contextFileElement: PsiElement
    ): List<Candidate> {
        val result = LinkedHashMap<String, Candidate>()

        fun addScore(name: String, delta: Int, source: String) {
            if (name.isBlank()) return
            val kebab = anyToKebab(name)
            if (kebab.isBlank()) return
            val existing = result[kebab]
            if (existing == null) result[kebab] = Candidate(kebab, delta, source)
            else result[kebab] = existing.copy(score = existing.score + delta,
                source = if (existing.score >= delta) existing.source else source)
        }

        // (1) 基于 style 属性值的语义线索
        val layoutHits = mutableListOf<String>()
        val visualHits = mutableListOf<String>()
        for (decl in cssDeclarations.lineSequence()) {
            val line = decl.trim().trimEnd(';').trim().lowercase()
            if (line.isBlank()) continue
            // 完整匹配 "key:value"
            val pair = line.split(':', limit = 2).map { it.trim() }
            if (pair.size == 2) {
                val (k, v) = pair
                val keyValue = "$k:$v"
                LAYOUT_HINT[keyValue]?.forEach { layoutHits += it }
                VISUAL_HINT[keyValue]?.forEach { visualHits += it }
                // 只看 key 的通用匹配
                if (k == "border-radius") visualHits += "rounded"
                if (k == "box-shadow" || k == "text-shadow") visualHits += "shadow"
                if (k.startsWith("padding")) layoutHits += "padded"
                if (k.startsWith("margin") && v != "0") layoutHits += "spaced"
                if (k == "gap") layoutHits += "spaced"
                if (k == "z-index") layoutHits += "layer"
            }
        }
        for (h in layoutHits) addScore(h, 8, "style:layout")
        for (h in visualHits) addScore(h, 6, "style:visual")

        // (2) 组件/标签级别 (JSXTag name 或组件名)
        val jsxLikeTag = findEnclosingJsxTag(styleAttrElement)
        if (jsxLikeTag != null) {
            // TAG_ROLE_HINT 原生标签
            val tagLow = jsxLikeTag.lowercase()
            TAG_ROLE_HINT[tagLow]?.forEach { addScore(it, 12, "tag:$tagLow") }
            // 自定义组件名 (首字母大写的 PascalCase): MyButton → my-button
            if (jsxLikeTag.firstOrNull()?.isUpperCase() == true) {
                val kebabTag = anyToKebab(jsxLikeTag)
                // 取最后一段词做强信号 (ThemeHeaderBar → header-bar)
                val words = kebabTag.split('-').filter { it.isNotEmpty() }
                if (words.size >= 2) addScore(words.takeLast(2).joinToString("-"), 20, "component-name(last-2)")
                addScore(kebabTag, 10, "component-name")
            }
        }

        // (3) 兄弟 / 邻近 className
        siblingClassNames(styleAttrElement).forEach { sib ->
            if (sib.isNotBlank()) {
                addScore("$sib-item", 7, "sibling-context")
                addScore("$sib-element", 5, "sibling-context")
            }
        }

        // (3.5) 祖先元素 className：把内联样式提升为父容器里的一个"子项"。
        //   例：<div className={styles.nav}><div style={...}/></div> → 期望 nav-item / nav-element，
        //   而不是退化为"文件名+root"。这里的优先级（18>17）刻意高于 component-scope 的 root(18)？
        //   注意：与 root(18) 同分时 name 更长的 -element 会胜出，为让 nav-item 稳定优先于 nav-element，
        //   给 nav-item 高于 18 的分，正好轻压 component 的 root；同时低于垂直组合语义，避免喧宾夺主。
        ancestorClassNames(styleAttrElement).forEach { anc ->
            if (anc.isNotBlank()) {
                addScore("$anc-item", 21, "ancestor-context")
                addScore("$anc-element", 18, "ancestor-context")
            }
        }

        // (4) 文件/父组件: class / function 组件名
        val component = inferComponentName(contextFileElement)
        if (component != null) {
            val compKebab = anyToKebab(component)
            addScore("$compKebab-item", 15, "component-scope")
            addScore("$compKebab-root", 18, "component-scope")
            addScore(compKebab, 8, "component-scope")
        }

        // (5) 兜底启发式：结合 layout + visual 组合拼接
        if (layoutHits.isNotEmpty() && visualHits.isNotEmpty()) {
            addScore("${layoutHits.first()}-${visualHits.first()}", 5, "combo(layout+visual)")
        }
        // fallback: wrapper / container / box
        addScore("wrapper", 2, "fallback")
        addScore("container", 2, "fallback")
        addScore("box", 1, "fallback")

        return result.values.sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenByDescending { it.name.length } // 同名分高优先，同分优先短？不，优先长 (更具体)
        )
    }

    /** top 候选：作为对话框的默认值 */
    fun topCandidate(candidates: List<Candidate>): String =
        candidates.firstOrNull()?.name ?: "wrapper"

    // ================================================================
    // 辅助: Psi 环境探查
    // ================================================================

    private fun findEnclosingJsxTag(el: PsiElement): String? {
        // 支持两种 PSI: JSXAttribute (from JSX) 和 XmlTag (Vue/Svelte template)
        var current: PsiElement? = el.parent
        var safety = 0
        while (current != null && safety++ < 20) {
            val clsName = current.javaClass.simpleName
            // JSXTag / JSXOpeningElement / JSXXmlTag 等类似
            if (clsName.contains("JSXTag", ignoreCase = true) ||
                clsName == "JSXXmlElementImpl" ||
                (clsName.contains("JSX", ignoreCase = true) &&
                 clsName.contains("OpeningElement", ignoreCase = true))) {
                val name = current.firstChild?.text?.takeIf { it.isNotBlank() }
                if (name != null) return name
            }
            if (current is XmlTag) return current.name
            current = current.parent
        }
        return null
    }

    private fun siblingClassNames(el: PsiElement): List<String> {
        // 在兄弟 JSXAttribute / XmlAttribute 中找 className/class/:class 等
        val attrsParent = el.parent ?: return emptyList()
        val names = mutableListOf<String>()
        for (sib in attrsParent.children) {
            val sibText = sib.text?.trim() ?: continue
            // className= / class= / :class= / v-bind:class=
            val m = Regex("""^(?:className|class|:class|v-bind:class)\s*=\s*"([^"]+)"""")
                .find(sibText) ?: continue
            val cls = m.groupValues[1].trim()
            // 如果是模板字符串/对象，取字面量部分
            if (cls.isNotBlank() && !cls.contains('{') && !cls.contains('}')) {
                cls.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { names += it }
            }
        }
        // kebab-case 类名作为语义上下文 (取第一个，避免泛滥)
        return names.distinct().take(2)
    }

    /**
     * 读取**祖先元素**上的 className/class 字面量（跳开 style 自身所在的那个元素的属性）。
     * 定位方式是：从 style 属性所在的容器向上走若干层，每遇到一个"新标签"就扫它的静态类属性，
     * 直到遇到边界（文件根 / <template> / <script> / JS 函数体）为止。
     *
     * 相比 siblingClassNames：
     *   - siblings 看的是"和 style 同标签的其它属性"（内联区域与 class 并列，少有）；
     *   - ancestors 看的是"style 所在标签的父/祖标签"（<div className={styles.nav}><div style=…/></div>），
     *     这是最常见的"把内联样式提成 父容器的子项类" 场景，能产生 nav-item / nav-element。
     *
     * 支持 className="nav"（JSX 字符串）与 Vue class="a b"；对 `{styles.nav}` 这类绑定表达式，仅当其
     * 文本能解析出字面量类名（如 styles.nav / styles["nav"]）时提取，否则忽略。
     */
    private fun ancestorClassNames(el: PsiElement): List<String> {
        val names = mutableListOf<String>()
        val seenTags = LinkedHashSet<String>()
        var cur: PsiElement? = el.parent // 跳过 style 属性自身
        var safety = 0
        while (cur != null && safety++ < 20) {
            val clsName = cur.javaClass.simpleName
            val isTag = clsName.contains("JSXTag", ignoreCase = true) ||
                    clsName == "JSXXmlElementImpl" ||
                    (clsName.contains("JSX", ignoreCase = true) &&
                     clsName.contains("OpeningElement", ignoreCase = true)) ||
                    cur is XmlTag
            val isBoundary = clsName.contains("JSFile", ignoreCase = true) ||
                    (cur is XmlTag && (cur.name == "template" || cur.name == "script" || cur.name == "style")) ||
                    // 到达函数体/语句块，说明已离开 JSX 树
                    clsName.contains("block", ignoreCase = true) && cur !is XmlTag

            // 只有"新标签"才扫描属性（防止在同一个标签的多个属性节点上重复扫）。
            // 注意 key 不能用 clsName：JSX/XmlTag 的多个标签 clsName 相同（如都是 XmlTagImpl），
            // 会导致只扫第一个标签、把更外层的祖先漏掉。改用标签自身的 textRange 作 key。
            if (isTag && seenTags.add(cur.textRange.toString())) {
                // 收集该标签属性里的静态 class / className
                var attrSib = cur.firstChild
                while (attrSib != null) {
                    collectClassLiteralFromAttr(attrSib.text ?: "", names)
                    // 若是 XmlTag 用 PSI 属性更稳
                    if (attrSib is XmlAttribute) collectClassLiteralFromAttr(attrSib.text ?: "", names)
                    attrSib = attrSib.nextSibling
                }
                if (names.size >= 2) break // 最多取两个，避免泛滥
            }

            if (isBoundary) break
            cur = cur.parent
        }
        return names.distinct().take(2)
    }

    /** 从一个属性文本里提取类名字面量：className="nav" / class="nav" / className={styles.nav} / styles["nav"]。 */
    private fun collectClassLiteralFromAttr(attrText: String, into: MutableList<String>) {
        if (attrText.isNullOrBlank()) return
        // 1) 静态字符串：className="a b" / class="a b" / :class="a b"
        val static = Regex("""(?:className|class|:class|v-bind:class)\s*=\s*"([^"{}]+)"""")
            .find(attrText)
        if (static != null) {
            val cls = static.groupValues[1].trim()
            if (cls.isNotBlank()) cls.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { into += it }
            return
        }
        // 2) 绑定表达式，仅当可静态还原为唯一字面量：className={styles.nav} → nav、styles["nav"] → nav。
        //    其它的（对象/三元/模板拼接）忽略，避免把含语义噪声的表达式当类名。
        val bind = Regex("""(?:className|class|:class|v-bind:class)\s*=\s*\{\s*styles\.([a-zA-Z_$][\w$]*)""")
            .find(attrText)
        if (bind != null) {
            val s = bind.groupValues[1].trim()
            if (s.isNotBlank()) into += s
            return
        }
        val bindBracket = Regex("""(?:className|class|:class|v-bind:class)\s*=\s*\{\s*styles\[\s*"(?:&lt;)?([^"&]+)"\s*\]""")
            .find(attrText)
        if (bindBracket != null) {
            val s = bindBracket.groupValues[1].trim()
            if (s.isNotBlank()) into += s
        }
    }

    private fun inferComponentName(fileEl: PsiElement): String? {
        // .vue 文件: 文件名 (如 UserProfile.vue → UserProfile)
        val vFile = fileEl.containingFile?.virtualFile
        if (vFile != null) {
            val ext = vFile.extension?.lowercase()
            val base = vFile.nameWithoutExtension
            if (ext == "vue" || ext == "svelte" || ext == "astro") return base
        }
        // JS/TS/TSX/JSX: 找 default export / function 组件 / const Comp = ...
        val file = fileEl.containingFile ?: return null
        val allFuns = PsiTreeUtil.findChildrenOfType(file, JSFunction::class.java)
        for (fn in allFuns) {
            // 默认导出函数组件
            val name = fn.name ?: continue
            if (name.firstOrNull()?.isUpperCase() == true) return name
        }
        val allVars = PsiTreeUtil.findChildrenOfType(file, JSVariable::class.java)
        for (v in allVars) {
            val name = v.name ?: continue
            if (name.firstOrNull()?.isUpperCase() == true) return name
        }
        return null
    }

    // ================================================================
    // 辅助: 任意字符串 → kebab-case
    // ================================================================
    private fun anyToKebab(raw: String): String {
        val s = raw.trim()
        if (s.isBlank()) return ""
        // 如果已经是 kebab-case (都是小写+-/_)
        if (s.all { it.isLowerCase() || it.isDigit() || it == '-' || it == '_' }) {
            return s.replace('_', '-')
        }
        // PascalCase / camelCase / Mixed with space / dot
        val spaceNorm = s.replace(Regex("[._\\s]+"), "-")
        // 插入 - 在 "aB" 之间
        val sb = StringBuilder(spaceNorm.length + 4)
        var prevLow = false
        for (i in spaceNorm.indices) {
            val c = spaceNorm[i]
            if (prevLow && c.isUpperCase()) sb.append('-')
            sb.append(c.lowercaseChar())
            prevLow = c.isLowerCase() || c.isDigit()
            if (c == '-') prevLow = false
        }
        return sb.toString().trim('-').replace("--", "-")
    }
}
