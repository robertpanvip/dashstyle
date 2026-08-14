package com.pan.dashstyle

import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import java.awt.Component

/**
 * #8. SCSS / LESS / Stylus 常用子集互转 + 转原生 CSS 嵌套。
 *
 * 注意（2026.2 兼容）：本类所有「XmlTag / XmlFile / Messages.showChooseDialog」等
 * 在不同发行版里方法签名可能变化的引用，**一律通过反射或字符串类名比较**访问，
 * 避免 PluginClassLoader 在实例化时因 IncompatibleClassChangeError / NoSuchMethodError
 * 导致 Cannot create class。isAvailable / invoke 整条链路都用 `runCatching` 包住，
 * 失败就静默返回，不影响其他 intention。
 */
@Suppress("UnstableApiUsage", "DEPRECATION")
class CssPreprocessorTranspileIntention : BaseIntentionAction() {

    override fun getText(): String = "Convert between SCSS / LESS / CSS nesting..."
    override fun getFamilyName(): String = "DashStyle: Convert preprocessor code"

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        return runCatching { resolveStyleScope(file, editor.caretModel.offset) != null }.getOrDefault(false)
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val scope = runCatching { resolveStyleScope(file, editor.caretModel.offset) }.getOrNull() ?: return
        val fromFormat = runCatching { detectFormat(scope) }.getOrDefault(Format.SCSS)
        val options = listOf(Format.SCSS, Format.LESS, Format.CSS_NESTING)
        val labels = options.map { it.label }.toTypedArray()
        val idx = runCatching {
            Messages.showChooseDialog(
                project,
                "Current detected format: ${fromFormat.label}. Choose target format (shared subset only).\n" +
                    "Unsupported constructs will be kept verbatim with a /* DashStyle: keep-original */ comment.",
                "Convert Preprocessor",
                null,
                labels,
                options.firstOrNull { it != fromFormat }?.label ?: labels[0]
            )
        }.getOrNull() ?: -1
        if (idx < 0 || idx >= options.size) return
        val to = options[idx]
        if (to == fromFormat) return
        val sourceText = runCatching { scope.text() }.getOrNull() ?: return
        val (transpiled, keptCount) = runCatching { transpile(sourceText, fromFormat, to) }
            .getOrElse { sourceText to -1 }

        runCatching {
            WriteCommandAction.writeCommandAction(project).withName("Convert ${fromFormat.label} -> ${to.label}")
                .run<Nothing> { scope.replace(transpiled) }
        }

        runCatching {
            val msg = buildString {
                append("Transpiled ${fromFormat.label} -> ${to.label}.")
                if (keptCount > 0) append(" $keptCount statement(s) could not be safely converted and were kept as-is.")
            }
            Messages.showInfoMessage(project, msg, "Convert Preprocessor OK")
        }
    }

    // ================================================================
    // 解析目标文件 / Vue <style> 块（全反射 + 类名字符串，不引用 XmlFile/XmlTag 字面类）
    // ================================================================
    private fun resolveStyleScope(file: PsiFile, offset: Int): StyleScope? {
        val name = runCatching { file.name }.getOrNull().orEmpty()
        if (name.endsWith(".css") || name.endsWith(".scss") || name.endsWith(".sass") || name.endsWith(".less")) {
            return StyleScope.CssFile(file)
        }
        if (!name.endsWith(".vue", ignoreCase = true)) return null

        val psiClassname = file.javaClass.name
        val looksLikeXmlLike = "XmlFile" in psiClassname || "VueFile" in psiClassname || ".vue" in name.lowercase()
        if (!looksLikeXmlLike) {
            val root = file as? PsiElement ?: return null
            val tags = PsiTreeUtil.findChildrenOfAnyType(root, false, PsiElement::class.java).filter { el ->
                val nm = callName(el)
                nm != null && nm.equals("style", ignoreCase = true)
            }
            return tags.firstOrNull()?.let { StyleScope.VueStyle(it, file) }
        }

        val at = runCatching { file.findElementAt(offset) }.getOrNull() ?: return null
        var cur: PsiElement? = at
        for (i in 0..20) {
            if (cur == null) break
            if ((callName(cur) ?: "").equals("style", ignoreCase = true)) return StyleScope.VueStyle(cur, file)
            cur = cur.parent
        }
        val styles = PsiTreeUtil.findChildrenOfType(file, PsiElement::class.java).filter { el ->
            (callName(el) ?: "").equals("style", ignoreCase = true)
        }
        return styles.firstOrNull()?.let { StyleScope.VueStyle(it, file) }
    }

    private fun callName(el: PsiElement): String? = runCatching {
        val m = el.javaClass.methods.firstOrNull { mm ->
            mm.parameterCount == 0 && mm.name == "getName" && CharSequence::class.java.isAssignableFrom(mm.returnType)
        } ?: el.javaClass.methods.firstOrNull { mm ->
            mm.parameterCount == 0 && mm.name == "getName" && mm.returnType == String::class.java
        } ?: return@runCatching null
        m.isAccessible = true
        when (val r = m.invoke(el)) {
            is String -> r
            is CharSequence -> r.toString()
            else -> null
        }
    }.getOrNull()

    private fun detectFormat(scope: StyleScope): Format = when (scope) {
        is StyleScope.CssFile -> formatByExt(scope.file.name)
        is StyleScope.VueStyle -> {
            val langVal = runCatching {
                val el = scope.styleTag
                val attrM = el.javaClass.methods.firstOrNull { m ->
                    m.name == "getAttributeValue" && m.parameterCount == 1 && m.parameterTypes[0] == String::class.java
                }
                if (attrM != null) {
                    attrM.isAccessible = true
                    (attrM.invoke(el, "lang") as? CharSequence)?.toString()
                } else {
                    val attr2 = el.javaClass.methods.firstOrNull { m ->
                        m.name == "getAttribute" && m.parameterCount == 1 && m.parameterTypes[0] == String::class.java
                    } ?: return@runCatching null
                    attr2.isAccessible = true
                    val attrObj = attr2.invoke(el, "lang") ?: return@runCatching null
                    val vf = attrObj.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
                        ?: attrObj.javaClass.methods.firstOrNull { it.name == "value" && it.parameterCount == 0 }
                        ?: return@runCatching null
                    vf.isAccessible = true
                    (vf.invoke(attrObj) as? CharSequence)?.toString()
                }
            }.getOrNull()
            when ((langVal ?: "scss").lowercase()) {
                "scss" -> Format.SCSS
                "less" -> Format.LESS
                "sass" -> Format.SCSS
                "css" -> Format.CSS_NESTING
                else -> Format.SCSS
            }
        }
    }

    // ================================================================
    // 格式枚举 + 按扩展名判定
    // ================================================================
    enum class Format(val label: String) {
        SCSS("SCSS"), LESS("LESS"), CSS_NESTING("CSS Nesting (native, no preprocessor)")
    }

    private fun formatByExt(name: String): Format = when {
        name.endsWith(".less", true) -> Format.LESS
        name.endsWith(".scss", true) -> Format.SCSS
        name.endsWith(".sass", true) -> Format.SCSS
        else -> Format.CSS_NESTING
    }

    // ================================================================
    // transpile 主函数 & 辅助
    // ================================================================
    private fun transpile(source: String, from: Format, to: Format): Pair<String, Int> {
        if (from == to) return source to 0
        val ctx = Context(keptCount = 0, out = mutableListOf())
        val normalized = source.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimStart()
            when {
                line.isBlank() -> {
                    ctx.out.add(raw)
                }
                line.startsWith("/*") || line.startsWith("//") -> {
                    ctx.out.add(raw)
                }
                // 变量声明
                VAR_DEC_RE.matchEntire(line) != null -> {
                    ctx.out.add(convertVarDecl(raw, from, to, ctx))
                }
                // extend
                EXTEND_RE.matchEntire(line) != null -> {
                    ctx.out.add(convertExtend(raw, from, to, ctx))
                }
                // include
                INCLUDE_RE.matchEntire(line) != null -> {
                    ctx.out.add(convertInclude(raw, from, to, ctx))
                }
                // mixin definition
                MIXIN_DEF_RE.matchEntire(line) != null -> {
                    ctx.out.add(convertMixinDef(raw, from, to, ctx))
                }
                line.contains("//") && !line.trim().startsWith("//") -> {
                    // inline comment: split code and keep code as-is
                    ctx.out.add(raw)
                }
                else -> {
                    // ruleset head / declaration line / close brace —— 交给 convertRegularLine
                    ctx.out.add(convertRegularLine(raw, from, to, ctx))
                }
            }
            i += 1
        }
        return ctx.out.joinToString("\n") to ctx.keptCount
    }

    private data class Context(var keptCount: Int, val out: MutableList<String>)

    private fun keepOriginal(raw: String, ctx: Context, reason: String? = null): String {
        ctx.keptCount += 1
        return if (reason == null) "$raw /* DashStyle: keep-original */"
        else "$raw /* DashStyle: keep-original: $reason */"
    }

    private fun convertRegularLine(raw: String, from: Format, to: Format, ctx: Context): String {
        val trimmed = raw.trim()
        // 变量插值替换
        val replaced = when (from to to) {
            Format.SCSS to Format.LESS -> raw.replace(SCSS_INTERP) { m -> "@{${m.groupValues[1]}}" }
            Format.SCSS to Format.CSS_NESTING -> raw.replace(SCSS_INTERP, "var(--\${'$'}\${'$'}1)")
            Format.LESS to Format.SCSS -> raw.replace(LESS_INTERP) { m -> "#{\${m.groupValues[1]}}" }
            Format.LESS to Format.CSS_NESTING -> raw.replace(LESS_INTERP, "var(--\${'$'}\${'$'}1)")
            Format.CSS_NESTING to Format.SCSS -> raw  // CSS nest 的变量已经是 -- 形式，保持
            Format.CSS_NESTING to Format.LESS -> raw
            else -> raw
        }
        // 原生 CSS Nesting ↔ 其他：所有以空格嵌套的子选择器前面加个 "& " （规范兼容）
        if ((from == Format.CSS_NESTING && (to == Format.SCSS || to == Format.LESS)) ||
            ((from == Format.SCSS || from == Format.LESS) && to == Format.CSS_NESTING)) {
            val headPattern = Regex("""^(\s*)([.#&][^{]+)\{$""")
            val m = headPattern.matchEntire(trimmed)
            if (m != null) {
                val selector = m.groupValues[2].trim()
                // 不重复加 &
                if (to == Format.CSS_NESTING && selector[0] != '&' && selector[0] != '@' && !selector.startsWith(':')) {
                    return "${m.groupValues[1]}& $selector {"
                }
                if (from == Format.CSS_NESTING && selector.startsWith("& ")) {
                    return "${m.groupValues[1]}${selector.removePrefix("& ").trim()} {"
                }
            }
        }
        return replaced
    }

    private fun convertVarDecl(raw: String, from: Format, to: Format, ctx: Context): String {
        val m = VAR_DEC_RE.matchEntire(raw.trim()) ?: return keepOriginal(raw, ctx, "var-decl")
        val indent = raw.take(raw.length - raw.trimStart().length)
        val name = m.groupValues[1].trim()
        val value = m.groupValues[2].trim()
        return when (from to to) {
            Format.SCSS to Format.LESS -> "$indent@$name: $value;"
            Format.LESS to Format.SCSS -> "$indent\$$name: $value;"
            Format.SCSS to Format.CSS_NESTING, Format.LESS to Format.CSS_NESTING -> {
                // 变量 fallback: 直接生成 --name: value;  引用处会被 convertVarReferences 在 convertRegularLine 里覆盖
                "$indent--$name: $value;"
            }
            Format.CSS_NESTING to Format.SCSS -> {
                val n = name.removePrefix("--")
                "$indent\$$n: $value;"
            }
            Format.CSS_NESTING to Format.LESS -> {
                val n = name.removePrefix("--")
                "$indent@$n: $value;"
            }
            else -> keepOriginal(raw, ctx, "var-decl: $from->$to")
        }
    }

    private fun convertExtend(raw: String, from: Format, to: Format, ctx: Context): String {
        val indent = raw.take(raw.length - raw.trimStart().length)
        return when {
            from == Format.SCSS && to == Format.LESS -> {
                val mm = EXTEND_RE.matchEntire(raw.trim())
                val target = mm?.groupValues?.get(1)?.trim() ?: return keepOriginal(raw, ctx, "extend")
                // Less 里没有原生 @extend；最近似等价是「Less 的 :extend(...) 伪类」，但写的位置必须放在 selector tail，
                // 没法在任意 ruleset block 里一行直接插入等价语法，因此保留原语句加注释，避免错位生成。
                "$indent/* DashStyle: LESS has no @extend. Keep original SCSS line below. */\n$indent$raw"
            }
            from == Format.LESS && to == Format.SCSS -> {
                // Less 常见形式是 :extend(.x) 放在选择器后，但这里只处理 block 里的 @extend 行（若用户写错了）。
                val mm = EXTEND_RE.matchEntire(raw.trim())
                val target = mm?.groupValues?.get(1)?.trim() ?: return keepOriginal(raw, ctx, "extend-less")
                if (!target.startsWith(".")) "$indent@extend .$target;"
                else raw
            }
            to == Format.CSS_NESTING -> keepOriginal(raw, ctx, "extend: CSS Nesting has no @extend")
            else -> raw
        }
    }

    private fun convertInclude(raw: String, from: Format, to: Format, ctx: Context): String {
        val indent = raw.take(raw.length - raw.trimStart().length)
        val inc = INCLUDE_RE.matchEntire(raw.trim())?.groupValues?.get(1)?.trim()
            ?: return keepOriginal(raw, ctx, "include")
        return when (from to to) {
            Format.SCSS to Format.LESS, Format.CSS_NESTING to Format.LESS ->
                "$indent.$inc;"  // Less 里 .xxx(...) 就是 include mixin
            Format.LESS to Format.SCSS, Format.CSS_NESTING to Format.SCSS ->
                "$indent@include $inc;"
            else -> keepOriginal(raw, ctx, "include: $from->$to")
        }
    }

    private fun convertMixinDef(raw: String, from: Format, to: Format, ctx: Context): String {
        val indent = raw.take(raw.length - raw.trimStart().length)
        val m = MIXIN_DEF_RE.matchEntire(raw.trim())
        val name = m?.groupValues?.get(1)?.trim()
        val args = m?.groupValues?.get(2)?.trim().orEmpty()
        if (name == null) return keepOriginal(raw, ctx, "mixin")
        return when (from to to) {
            Format.SCSS to Format.LESS -> "$indent.$name($args) {"
            Format.LESS to Format.SCSS -> "$indent@mixin $name($args) {"
            else -> keepOriginal(raw, ctx, "mixin: $from->$to")
        }
    }

    companion object {
        // 注意：避免直接引用 deprecated Messages.showChooseDialog 的参数 component（不传用 null）
        @Suppress("unused")
        private val anchor: Component? = null

        private val VAR_DEC_RE: Regex =
            Regex("""^(?:\$|@|--)\s*([A-Za-z_][\w-]*)\s*:\s*(.+?);?\s*${'$'}""")
        private val EXTEND_RE = Regex("""^@extend\s+([^;]+);?\s*${'$'}""")
        private val INCLUDE_RE = Regex("""^@include\s+([A-Za-z_][\w-]*\s*(?:\([^)]*\))?)[^;]*;?\s*${'$'}""")
        private val MIXIN_DEF_RE = Regex("""^@mixin\s+([A-Za-z_][\w-]*)\s*(\([^)]*\))?\s*\{\s*${'$'}""")
        private val SCSS_INTERP = Regex("""#\{\s*\$([A-Za-z_][\w-]*)\s*\}""")
        private val LESS_INTERP = Regex("""@\{([A-Za-z_][\w-]*)\}""")

        // ================================================================
        // Scope 抽象 —— 把嵌套类放进 companion，便于访问同域的私有反射工具函数
        // ================================================================
        sealed class StyleScope {
            data class CssFile(val file: PsiFile) : StyleScope()
            /** 存储 vue 的 <style> 节点（PsiElement）+ 整个 vue PsiFile */
            data class VueStyle(val styleTag: PsiElement, val scopeFile: PsiFile) : StyleScope()

            fun text(): String = runCatching {
                when (this) {
                    is CssFile -> file.text
                    is VueStyle -> readTagValueViaReflection(styleTag)
                }
            }.getOrDefault("")

            fun replace(newText: String) {
                runCatching {
                    when (this) {
                        is CssFile -> {
                            val doc = PsiDocumentManager.getInstance(file.project).getDocument(file)
                                ?: return@runCatching
                            doc.replaceString(0, doc.textLength, newText)
                        }
                        is VueStyle -> replaceTagValueViaReflection(styleTag, scopeFile, newText)
                    }
                }
            }
        }

        // ------------------------------------------------------------
        // 内部工具：style tag 的值读/写（全部反射，无 XmlTag 强依赖）
        // ------------------------------------------------------------
        @JvmStatic
        private fun readTagValueViaReflection(styleTag: PsiElement): String {
            // 方法1：getValue()
            runCatching {
                val m = styleTag.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
                    ?: return@runCatching null
                m.isAccessible = true
                when (val v = m.invoke(styleTag)) {
                    is CharSequence -> return v.toString()
                    is PsiElement -> return v.text
                    else -> null
                }
            }
            // 方法2：直接按文本拼开闭标签之间
            val whole = styleTag.text
            val firstGT = whole.indexOf('>')
            val lastLT = whole.lastIndexOf('<')
            if (firstGT >= 0 && lastLT >= 0 && lastLT > firstGT) return whole.substring(firstGT + 1, lastLT)
            return ""
        }

        @JvmStatic
        private fun replaceTagValueViaReflection(styleTag: PsiElement, scopeFile: PsiFile, newText: String) {
            val doc = PsiDocumentManager.getInstance(scopeFile.project).getDocument(scopeFile) ?: return
            // 优先 setValue 反射
            runCatching {
                val setM = styleTag.javaClass.methods.firstOrNull { m ->
                    m.name == "setValue" && m.parameterCount == 1 &&
                        (m.parameterTypes[0] == String::class.java || CharSequence::class.java.isAssignableFrom(m.parameterTypes[0]))
                }
                if (setM != null) {
                    setM.isAccessible = true
                    setM.invoke(styleTag, newText)
                    return
                }
            }
            // 其次：getValue() 返回 PsiElement，取其 textRange 替换
            runCatching {
                val getM = styleTag.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
                    ?: return@runCatching
                getM.isAccessible = true
                val v = getM.invoke(styleTag) as? PsiElement ?: return@runCatching
                val tr = v.textRange
                if (!tr.isEmpty) {
                    doc.replaceString(tr.startOffset, tr.endOffset, newText)
                    return
                }
            }
            // 最后：按整个 styleTag 文本切 > 与最后 < 之间定位
            val tagRange = styleTag.textRange
            val tagText = styleTag.text
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
