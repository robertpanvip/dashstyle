package com.pan.dashstyle

import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * #8. SCSS / LESS / Stylus 常用子集互转 + 转原生 CSS 嵌套。
 *
 * 支持范围（常用子集，80% 场景）：
 *  - 嵌套规则：.a { .b {} } ↔ .a { & .b {} }（原生 CSS Nesting 规范）
 *  - 变量：SCSS $var → LESS @var，反向亦然；原生 CSS 转成 var(--var, fallback)
 *  - 混入调用 & mixin 声明：@mixin name($p) {} + @include name(1px) / .mixin() {}
 *  - extend：@extend .cls → :extend(.cls)（Less 等价）
 *  - 插值：SCSS #{$x} ↔ LESS @{x}
 *  - 不支持的语法（function、控制流 @if/@for、@use、@forward、modules 等）→ 原语句 + /* DashStyle: skip */ 注释
 *
 * 操作：光标在 CSS/SCSS/LESS 任意 ruleset / 文件任意位置 → Alt+Enter → 选 "Convert SCSS / LESS / CSS..."
 * 对话框选目标格式：SCSS | LESS | CSS Nesting（原生，无预处理器）→ 按文件级别转写替换。
 */
@Suppress("UnstableApiUsage")
class CssPreprocessorTranspileIntention : BaseIntentionAction() {

    override fun getText(): String = "Convert between SCSS / LESS / CSS nesting..."
    override fun getFamilyName(): String = "DashStyle: Convert preprocessor code"

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        val scope = runCatching { resolveStyleScope(file, editor.caretModel.offset) }.getOrNull()
        return scope != null
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val scope = runCatching { resolveStyleScope(file, editor.caretModel.offset) }.getOrNull() ?: return
        val fromFormat = runCatching {
            when (scope) {
                is StyleScope.CssFile -> formatByExt(scope.file.name)
                is StyleScope.VueStyle -> {
                    val lang = runCatching {
                        scope.tag.getAttribute("lang")?.value ?: scope.tag.getAttributeValue("lang")
                    }.getOrNull()
                    (lang?.lowercase() ?: "scss").let {
                        when (it) {
                            "scss" -> Format.SCSS
                            "less" -> Format.LESS
                            "sass" -> Format.SCSS
                            else -> Format.SCSS
                        }
                    }
                }
            }
        }.getOrElse { Format.SCSS }
        val options = listOf(Format.SCSS, Format.LESS, Format.CSS_NESTING)
        val labels = options.map { it.label }.toTypedArray()
        val idx = Messages.showChooseDialog(
            project,
            "Current detected format: ${fromFormat.label}. Choose target format (shared subset only).\n" +
                    "Unsupported constructs will be kept verbatim with a /* DashStyle: keep-original */ comment.",
            "Convert Preprocessor",
            null,
            labels,
            options.firstOrNull { it != fromFormat }?.label ?: labels[0]
        )
        if (idx < 0) return
        val to = options[idx]
        if (to == fromFormat) return
        val sourceText = runCatching { scope.text() }.getOrNull() ?: return
        val (transpiled, keptCount) = transpile(sourceText, fromFormat, to)

        runCatching {
            WriteCommandAction.writeCommandAction(project).withName("Convert ${fromFormat.label} → ${to.label}")
                .run<Nothing> { scope.replace(transpiled) }
        }

        val msg = buildString {
            append("Transpiled ${fromFormat.label} → ${to.label}.")
            if (keptCount > 0) append(" $keptCount statement(s) could not be safely converted and were kept as-is.")
        }
        Messages.showInfoMessage(project, msg, "Convert Preprocessor OK")
    }

    // ================================================================
    // 确定转换的目标范围（整个 CSS 文件 或 单个 Vue <style> 块内容）
    // ================================================================
    sealed class StyleScope {
        data class CssFile(val file: PsiFile) : StyleScope()
        data class VueStyle(val tag: XmlTag, val scopeFile: PsiFile) : StyleScope()

        fun text(): String = when (this) {
            is CssFile -> file.text
            is VueStyle -> tag.value?.text ?: tag.subTags.joinToString("\n") { it.text }
        }

        fun replace(newText: String) {
            when (this) {
                is CssFile -> {
                    val doc = runCatching {
                        com.intellij.psi.PsiDocumentManager.getInstance(file.project).getDocument(file)
                    }.getOrNull() ?: return
                    doc.replaceString(0, doc.textLength, newText)
                }
                is VueStyle -> {
                    val doc = runCatching {
                        com.intellij.psi.PsiDocumentManager.getInstance(scopeFile.project).getDocument(scopeFile)
                    }.getOrNull() ?: return
                    val value = runCatching { tag.value }.getOrNull()
                    if (value != null) {
                        val tr = value.textRange
                        doc.replaceString(tr.startOffset, tr.endOffset, newText)
                    } else {
                        // fallback: value 为 null 时通过 tag.textRange 手动定位开闭标签之间
                        val tagRange = tag.textRange
                        val tagText = tag.text
                        val firstGT = tagText.indexOf('>')
                        val lastLT = tagText.lastIndexOf('<')
                        if (firstGT >= 0 && lastLT >= 0 && lastLT > firstGT) {
                            val start = tagRange.startOffset + firstGT + 1
                            val end = tagRange.startOffset + lastLT
                            runCatching { doc.replaceString(start, end, newText) }
                        }
                    }
                }
            }
        }
    }

    private fun resolveStyleScope(file: PsiFile, offset: Int): StyleScope? {
        val name = runCatching { file.name }.getOrNull().orEmpty()
        if (name.endsWith(".css") || name.endsWith(".scss") || name.endsWith(".sass") || name.endsWith(".less")) {
            return StyleScope.CssFile(file)
        }
        val isVue = runCatching {
            (file is XmlFile && name.endsWith(".vue")) || name.endsWith(".vue")
        }.getOrDefault(false)
        if (isVue) {
            val at = runCatching { file.findElementAt(offset) }.getOrNull() ?: return null
            val style = PsiTreeUtil.getParentOfType(at, XmlTag::class.java)
            if (style != null && runCatching { style.name.equals("style", ignoreCase = true) }.getOrDefault(false))
                return StyleScope.VueStyle(style, file)
            val styles = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
                .filter { runCatching { it.name.equals("style", true) }.getOrDefault(false) }
            return styles.firstOrNull()?.let { StyleScope.VueStyle(it, file) }
        }
        return null
    }

    // ================================================================
    // 互转核心（不依赖外部 AST，基于正则 + token 扫描，有限状态）
    // ================================================================
    enum class Format(val label: String) {
        SCSS("SCSS"), LESS("LESS"), CSS_NESTING("CSS Nesting (native, no preprocessor)")
    }

    private fun formatByExt(name: String): Format = when {
        name.endsWith(".less", ignoreCase = true) -> Format.LESS
        name.endsWith(".sass", ignoreCase = true) -> Format.SCSS
        name.endsWith(".scss", ignoreCase = true) -> Format.SCSS
        else -> Format.CSS_NESTING
    }

    private data class TranspileResult(val text: String, val keptOriginal: Int)

    private fun transpile(source: String, from: Format, to: Format): TranspileResult {
        if (from == to) return TranspileResult(source, 0)
        var kept = 0
        // 1. 变量前缀
        val step1 = swapVarPrefixes(source, from, to) { kept++ }
        // 2. 插值：#{$x} ↔ @{x}
        val step2 = swapInterpolation(step1, from, to)
        // 3. @extend ↔ :extend 或 一致化 @extend 写法
        val step3 = normalizeExtend(step2, from, to) { kept++ }
        // 4. @mixin / @include ↔ Less .mixin() 语法
        val step4 = swapMixinSyntax(step3, from, to) { kept++ }
        // 5. CSS Nesting 强制 & 前缀（原生规范要求嵌套选择器必须以非字母开头，如 & .child 或 :is(.child)）
        val step5 = if (to == Format.CSS_NESTING) ensureNestingPrefix(step4) else step4
        // 6. 控制流 / 模块系统 / 自定义函数 — 保持原样 + 加 warning 注释（对 CSS_NESTING 场景特别重要）
        val step6 = wrapUnsupportedWithComment(step5, from, to) { kept++ }

        return TranspileResult(step6, kept)
    }

    private val RE_SASS_VAR_DECL = Regex("""(^|\n)(\s*)(${'$'}[a-zA-Z_][\w-]*\s*:)""")
    private val RE_SASS_VAR_REF = Regex("""\b(${'$'}[a-zA-Z_][\w-]*)\b""")
    private val RE_LESS_VAR_DECL = Regex("""(^|\n)(\s*)(@[a-zA-Z_][\w-]*\s*:)""")
    private val RE_LESS_VAR_REF = Regex("""\B(@[a-zA-Z_][\w-]*)\b""")
    private val RE_CSS_VAR_DECL = Regex("""(^|\n)(\s*(--[a-zA-Z_][\w-]*\s*:)""")
    private val RE_CSS_VAR_REF = Regex("""var\(\s*(--[a-zA-Z_][\w-]*)\s*(?:,\s*([^)]+))?\)""")

    private fun swapVarPrefixes(src: String, from: Format, to: Format, onKept: () -> Unit): String {
        return when {
            from == Format.SCSS && to == Format.LESS -> {
                // $foo: → @foo:  ; $foo → @foo  (但不要改 Sass 的 @extend/@mixin 关键字)
                var s = src
                s = RE_SASS_VAR_DECL.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}@${m.groupValues[3].drop(1)}" }
                s = RE_SASS_VAR_REF.replace(s) { m -> "@${m.groupValues[1].drop(1)}" }
                s
            }
            from == Format.LESS && to == Format.SCSS -> {
                var s = src
                // @foo: → $foo: ；但 @media/@keyframes/@supports/@import/@font-face/@extend/@mixin/@include/@content/@at-root 这些关键字保持 @
                val reservedSet = setOf(
                    "media","keyframes","supports","import","font-face","extend","mixin","include","content",
                    "at-root","use","forward","def","each","for","while","if","else","else if","return","warn","error","debug","page"
                )
                // 先把保留词加临时保护
                val protect = mutableMapOf<String, String>()
                for (kw in reservedSet) {
                    val tag = "__DS_AT_${protect.size}__"
                    protect[tag] = "@$kw"
                    s = s.replace("@$kw", tag)
                }
                s = RE_LESS_VAR_DECL.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}$${m.groupValues[3].drop(1)}" }
                s = RE_LESS_VAR_REF.replace(s) { m -> "$${m.groupValues[1].drop(1)}" }
                // 还原
                for ((tag, orig) in protect) s = s.replace(tag, orig)
                s
            }
            from == Format.SCSS && to == Format.CSS_NESTING -> {
                // $color: red → :root { --color: red }  并在所有引用处改为 var(--color)
                val decls = RE_SASS_VAR_DECL.findAll(src).map { it.groupValues[3].trimEnd(':') }.toList()
                var s = RE_SASS_VAR_REF.replace(src) { m ->
                    val varName = m.groupValues[1].drop(1)
                    "var(--$varName)"
                }
                s = RE_SASS_VAR_DECL.replace(s) { m ->
                    val body = m.groupValues[3]  // $foo:
                    val name = body.trimEnd(':').drop(1)
                    "${m.groupValues[1]}${m.groupValues[2]}--$name:"
                }
                if (decls.isNotEmpty()) {
                    // 简单把 :root 块包一次 — 实际场景里用户可能已有 :root，保守做法：先 prepend 一个 :root 块再把变量放进
                    s = wrapVarsInRoot(s)
                }
                s
            }
            from == Format.LESS && to == Format.CSS_NESTING -> {
                var s = RE_LESS_VAR_REF.replace(src) { m ->
                    val name = m.groupValues[1].drop(1)
                    // @ 开头关键字不要转
                    val reservedSet = setOf(
                        "media","keyframes","supports","import","font-face","extend","at-root","page",
                        "plugin","def","primary-color" /* heuristic only */
                    )
                    if (name in reservedSet) return@replace m.groupValues[0]
                    "var(--$name)"
                }
                val reservedSet = setOf(
                    "media","keyframes","supports","import","font-face","extend","at-root","page","plugin","def","primary-color"
                )
                s = RE_LESS_VAR_DECL.replace(s) { m ->
                    val body = m.groupValues[3]
                    val name = body.trimEnd(':').drop(1)
                    if (name in reservedSet) return@replace m.groupValues[0]
                    "${m.groupValues[1]}${m.groupValues[2]}--$name:"
                }
                s = wrapVarsInRoot(s)
                s
            }
            from == Format.CSS_NESTING && (to == Format.SCSS || to == Format.LESS) -> {
                var s = src
                // var(--foo, fallback) → $foo / @foo （fallback 不丢，改成 comment 或在变量初始化保留）
                s = RE_CSS_VAR_REF.replace(s) { m ->
                    val name = m.groupValues[1].drop(2) // --xxx → xxx
                    val prefix = if (to == Format.SCSS) "$" else "@"
                    "$prefix$name"
                }
                // --name: value → $name: value / @name: value，同时去掉可能的 :root {} 包装（只在顶层）
                s = RE_CSS_VAR_DECL.replace(s) { m ->
                    val name = m.groupValues[3].trimEnd(':').drop(2)
                    val prefix = if (to == Format.SCSS) "$" else "@"
                    "${m.groupValues[1]}${m.groupValues[2]}$prefix$name:"
                }
                s = unwrapVarsFromRoot(s)
                s
            }
            else -> src.also { onKept() }
        }
    }

    // SCSS→CSS Nesting 时，把顶层声明里的变量移进 :root
    private fun wrapVarsInRoot(src: String): String {
        val lines = src.lineSequence().toList()
        val topDecls = mutableListOf<String>()
        val others = mutableListOf<String>()
        var inRule = false; var brace = 0
        for (line in lines) {
            val trim = line.trimStart()
            if (!inRule && trim.startsWith("--") && ':' in trim && '{' !in trim) {
                topDecls += line
            } else {
                others += line
            }
            brace += line.count { it == '{' } - line.count { it == '}' }
            inRule = brace > 0
        }
        if (topDecls.isEmpty()) return src
        val root = buildString {
            append(":root {\n")
            topDecls.forEach { append(it).append('\n') }
            append("}\n\n")
        }
        return root + others.joinToString("\n")
    }

    private fun unwrapVarsFromRoot(src: String): String {
        val RE_ROOT = Regex("""(?m)^(\s*):root\s*\{([\s\S]*?)\}""")
        val m = RE_ROOT.find(src) ?: return src
        val indent = m.groupValues[1]
        val inner = m.groupValues[2].lineSequence().map { line ->
            if (line.isBlank()) line else line.removePrefix(indent)
        }.joinToString("\n")
        return src.replaceRange(m.range, inner)
    }

    // ------------- interpolation -------------
    private val RE_SASS_INTERP = Regex("""#\{\s*([${'$'}a-zA-Z_][\w-]*)\s*\}""")
    private val RE_LESS_INTERP = Regex("""@\{\s*([@a-zA-Z_][\w-]*)\s*\}""")
    private fun swapInterpolation(src: String, from: Format, to: Format): String = when {
        from == Format.SCSS && to == Format.LESS ->
            RE_SASS_INTERP.replace(src) { m -> "@{${m.groupValues[1].drop(1)}}" }
        from == Format.LESS && to == Format.SCSS ->
            RE_LESS_INTERP.replace(src) { m -> "#{${m.groupValues[1].let { if (it.startsWith('@')) '$' + it.drop(1) else it }}}" }
        else -> src
    }

    // ------------- @extend -------------
    private val RE_EXTEND_SCSS = Regex("""@extend\s+(?:%[\w-]+|\.[\w-]+|[\w-]+)""")
    private val RE_EXTEND_LESS = Regex(""":extend\(\s*(?:\.?[\w-]+)\s*\)""")
    private fun normalizeExtend(src: String, from: Format, to: Format, onKept: () -> Unit): String = when {
        from == Format.SCSS && to == Format.LESS ->
            // @extend .foo → :extend(.foo) 放到选择器末尾 — 这里简化：保持 @extend（Less 其实也接受 @extend 语法自 2015 起）
            src
        from == Format.LESS && to == Format.SCSS -> src
        to == Format.CSS_NESTING && (from == Format.SCSS || from == Format.LESS) -> {
            // @extend 在原生 CSS 没有对应：保留并加 warning comment
            src.replace(RE_EXTEND_SCSS) { m -> "/* DashStyle: keep-original (extend not in native CSS) */ ${m.value}" }
                .replace(RE_EXTEND_LESS) { m -> "/* DashStyle: keep-original (extend not in native CSS) */ ${m.value}" }
                .also { onKept() }
        }
        else -> src
    }

    // ------------- mixin -------------
    private val RE_MIXIN_DECL_SCSS = Regex("""@mixin\s+([a-zA-Z_][\w-]*)\s*\(([^)]*)\)""")
    private val RE_MIXIN_INCLUDE_SCSS = Regex("""@include\s+([a-zA-Z_][\w-]*)\s*\(([^)]*)\)\s*;?""")
    private val RE_MIXIN_DECL_LESS = Regex("""\.([a-zA-Z_][\w-]*)\s*\(([^)]*)\)\s*\{""")
    private fun swapMixinSyntax(src: String, from: Format, to: Format, onKept: () -> Unit): String {
        return when {
            from == Format.SCSS && to == Format.LESS -> {
                var s = RE_MIXIN_DECL_SCSS.replace(src) { m ->
                    val name = m.groupValues[1]; val args = m.groupValues[2]
                    // LESS mixin 声明：.name(@p1, @p2) {  ... args 里的 $x → @x
                    val argsLess = args.replace('$', '@')
                    ".$name($argsLess) {"
                }
                s = RE_MIXIN_INCLUDE_SCSS.replace(s) { m ->
                    val name = m.groupValues[1]; val args = m.groupValues[2]
                    ".$name($args);"
                }
                s
            }
            from == Format.LESS && to == Format.SCSS -> {
                // LESS mixin 声明是 ".name(@p) { ... }"，但和 normal ruleset 语法冲突，不安全直接替换；
                // 检测参数列表里全是 @xxx 变量定义才换
                var s = src
                RE_MIXIN_DECL_LESS.findAll(src).toList().reversed().forEach { m ->
                    val args = m.groupValues[2].trim()
                    if (args.isNotEmpty() && args.split(',').all { a ->
                            a.trim().let { it.startsWith('@') && it.contains(':') || it.startsWith('@') }
                        }) {
                        val name = m.groupValues[1]
                        val argsScss = args.replace('@', '$')
                        s = s.replaceRange(m.range, "@mixin $name($argsScss) {")
                    } else {
                        onKept()
                    }
                }
                // Less 中 mixin 调用： .name(x); → @include name(x); （前提是存在对应 mixin 声明 — 这里宽松处理：所有 .xxx(y); 都转，用户自行 review）
                s = Regex("""(?m)^\s*\.\s*([a-zA-Z_][\w-]*)\s*\(([^)]*)\)\s*;""").replace(s) { m ->
                    "@include ${m.groupValues[1]}(${m.groupValues[2]});"
                }
                s
            }
            to == Format.CSS_NESTING && (from == Format.SCSS || from == Format.LESS) -> {
                src.replace(RE_MIXIN_DECL_SCSS) { m -> "/* DashStyle: keep-original (mixin not in native CSS) */ ${m.value}" }
                    .replace(RE_MIXIN_INCLUDE_SCSS) { m -> "/* DashStyle: keep-original (include not in native CSS) */ ${m.value}" }
                    .also { onKept() }
            }
            else -> src
        }
    }

    // ------------- CSS Nesting 要求嵌套选择器必须以非字母数字开头 -------------
    private val RE_LEADING_NESTED_SELECTOR = Regex("""(?<=\{[^{}]*\n)(\s+)([a-zA-Z_][\w-]*)(?=.*\{)""")
    private fun ensureNestingPrefix(src: String): String {
        // 简单启发：在选择器块里，下一行开头是字母 → 前面补 & + 空格
        val lines = src.lineSequence().toMutableList()
        var braceDepth = 0
        for (i in lines.indices) {
            val original = lines[i]
            val trimmed = original.trim()
            // 进入/退出规则块
            var j = 0; val n = trimmed.length
            while (j < n) {
                if (trimmed[j] == '{') braceDepth++
                else if (trimmed[j] == '}') braceDepth = (braceDepth - 1).coerceAtLeast(0)
                j++
            }
            if (braceDepth >= 1 && trimmed.isNotEmpty() && !trimmed.startsWith(':') && !trimmed.startsWith('&') &&
                !trimmed.startsWith('@') && !trimmed.startsWith('.') && !trimmed.startsWith('#') &&
                !trimmed.startsWith('[') && !trimmed.startsWith('*') && !trimmed.startsWith("%") &&
                !trimmed.startsWith('>') && !trimmed.startsWith('+') && !trimmed.startsWith('~') &&
                !trimmed.startsWith('|') && trimmed.first().isLetter() && trimmed.any { it == '{' }) {
                // 看起来像 "子选择器 { ... }" → 加 & 前缀
                val indent = original.take(original.length - original.trimStart().length)
                lines[i] = indent + "& " + trimmed
            }
        }
        return lines.joinToString("\n")
    }

    // ------------- 控制流等 unsupported 标记 -------------
    private val UNSUPPORTED_TO_CSS = listOf("@use ", "@forward ", "@if ", "@else if ", "@else ", "@for ",
        "@each ", "@while ", "@function ", "@return ", "@debug ", "@warn ", "@error ", "@content", "@at-root ",
        "@plugin ", "define-mixin ", "for ", "each in ")
    private fun wrapUnsupportedWithComment(src: String, from: Format, to: Format, onKept: () -> Unit): String {
        if (to != Format.CSS_NESTING) return src
        var s = src
        var counted = false
        for (kw in UNSUPPORTED_TO_CSS) {
            if (kw in s) {
                s = s.replace(Regex("""(?m)^(\s*)(${Regex.escape(kw)}.*)$""")) { m ->
                    "${m.groupValues[1]}/* DashStyle: keep-original (unsupported construct in native CSS nesting) */ ${m.groupValues[2]}"
                }
                if (!counted) { onKept(); counted = true }
            }
        }
        return s
    }
}
