package com.pan.dashstyle.support

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.annotator.*

import com.intellij.lang.javascript.psi.*
import com.intellij.psi.PsiElement
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
