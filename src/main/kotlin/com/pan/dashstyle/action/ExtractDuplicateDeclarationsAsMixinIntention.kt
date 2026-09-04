package com.pan.dashstyle.action

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil

/** 提取门槛：共享声明（重复的 "属性:值"）至少 3 条才提取，避免单条共享也滥竽充数。 */
private const val MIN_SHARED_DECLARATIONS = 3

/**
 * #9. 提取重复的 CSS 声明块为共享 Less mixin 并原地引用。
 *
 * 输入（你反馈的例子，已去除你手写的 __tmp__ 占位符 / 乱序 {;} 等草稿标记）：
 *   .dashboard { padding: 10px; }
 *   .z         { padding: 10px; }
 *
 * 期望输出（Less 方言 —— CSS 原生语法不支持 mixin，所以默认按 Less 输出；SCSS 下会自动转成 %placeholder + @extend）：
 *   .dashboard { .shared-padding; }
 *   .z         { .shared-padding; }
 *   .shared-padding { padding: 10px; }
 *
 * 算法：
 *   1) 若编辑器有选区 → 只在选区文本范围内遍历顶层 ruleset；
 *      若无选区 → 在整个 scope（.less/.scss/.css/.module.* 文件 / Vue <style> 块）里扫。
 *   2) 对每个 ruleset.block 归一化得到 signature = List<"prop:normalized_value">，按签名分组。
 *   3) 任何组 size >= 2 → 认为是「重复块」，抽成一个 mixin：
 *        - 名字 = .shared-{首字母大写属性名}(去驼峰)， 比如 padding → .shared-padding；
 *        - 重名加后缀 2/3/...，也可跟剩余属性连缀（比如 shared-padding-color）。
 *   4) 在原 ruleset.block 里删除所有被抽走的 declaration，追加一行 .shared-xxx; 作为 mixin 调用。
 *   5) 把所有新生成的 shared mixin definition 追加到文件 / style scope 末尾（和其他 ruleset 隔 2 行空行）。
 *
 * 跨版本兼容策略同 CssPreprocessorTranspileIntention：所有 XmlTag / XmlFile / Messages.show*Dialog
 * 都走反射或 runCatching 包一层，避免 PluginClassLoader 在新 WS 版本上 NoSuchMethodError。
 */
@Suppress("UnstableApiUsage", "DEPRECATION")
class ExtractDuplicateDeclarationsAsMixinIntention : BaseIntentionAction() {

    override fun getText(): String = "Extract duplicated CSS blocks into shared Less mixins"
    override fun getFamilyName(): String = "DashStyle: Extract duplicate CSS"

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean = runCatching {
        val scope = resolveScope(file, editor.caretModel.offset) ?: return@runCatching false
        val root = scopeRoot(scope) ?: return@runCatching false
        val selection = editor.selectionModel
        val range = selectionRangeOrNull(selection, file)
        val rules = collectCandidateRulesets(root, range)
        // 共享声明（签名里的 prop:value 段）>= 3 且出现一组重复（size>=2）才显示，避免单条共享也滥竽充数
        groupBySignature(rules).any { (sign, list) -> list.size >= 2 && sign.size >= MIN_SHARED_DECLARATIONS }
    }.getOrDefault(false)

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val scope = runCatching { resolveScope(file, editor.caretModel.offset) }.getOrNull() ?: return
        val root = runCatching { scopeRoot(scope) }.getOrNull() ?: return
        val selection = editor.selectionModel
        val range = selectionRangeOrNull(selection, file)
        val sourceText = runCatching { scopeText(scope) }.getOrDefault("")
        val resultText = runCatching {
            extractDuplicateInText(
                source = sourceText,
                file = file,
                root = root,
                selection = range
            )
        }.getOrNull() ?: return
        runCatching {
            WriteCommandAction.writeCommandAction(project)
                .withName("Extract duplicate CSS as mixin")
                .run<Nothing> { scopeReplace(scope, resultText) }
        }
        runCatching {
            Messages.showInfoMessage(
                project,
                "Replaced duplicates with shared mixin calls in current scope.",
                "Extract Duplicate CSS OK"
            )
        }
    }

    // ===================================================================
    // 作用域 & 文本读写（复用 CssPreprocessorTranspileIntention 的 StyleScope 思想，但不耦合它的私有类）
    // ===================================================================
    private sealed class Scope {
        data class FileScope(val psi: PsiFile) : Scope()
        data class VueStyleScope(val styleTag: PsiElement, val scopeFile: PsiFile) : Scope()
    }

    private fun resolveScope(file: PsiFile, offset: Int): Scope? {
        val name = runCatching { file.name }.getOrNull().orEmpty()
        if (name.endsWith(".css", ignoreCase = true) ||
            name.endsWith(".scss", ignoreCase = true) ||
            name.endsWith(".sass", ignoreCase = true) ||
            name.endsWith(".less", ignoreCase = true)) {
            return Scope.FileScope(file)
        }
        if (name.endsWith(".vue", ignoreCase = true)) {
            // 定位当前 caret 所在 <style>；找不到就返回 null（不做全局抽取）
            val at = runCatching { file.findElementAt(offset) }.getOrNull() ?: return null
            var cur: PsiElement? = at
            for (i in 0..20) {
                if (cur == null) break
                if ((tagName(cur) ?: "").equals("style", ignoreCase = true)) return Scope.VueStyleScope(cur, file)
                cur = cur.parent
            }
            val all = PsiTreeUtil.findChildrenOfType(file, PsiElement::class.java)
                .filter { (tagName(it) ?: "").equals("style", ignoreCase = true) }
                .toList()
            if (all.size == 1) return Scope.VueStyleScope(all.first(), file)
            return null
        }
        return null
    }

    private fun scopeRoot(scope: Scope): PsiElement? = when (scope) {
        is Scope.FileScope -> scope.psi
        is Scope.VueStyleScope -> scope.styleTag
    }

    private fun scopeText(scope: Scope): String = when (scope) {
        is Scope.FileScope -> runCatching { scope.psi.text }.getOrDefault("")
        is Scope.VueStyleScope -> readTagInnerTextViaReflection(scope.styleTag)
    }

    private fun scopeReplace(scope: Scope, newText: String) {
        runCatching {
            when (scope) {
                is Scope.FileScope -> {
                    val doc = com.intellij.psi.PsiDocumentManager.getInstance(scope.psi.project)
                        .getDocument(scope.psi) ?: return@runCatching
                    doc.replaceString(0, doc.textLength, newText)
                }
                is Scope.VueStyleScope -> replaceTagInnerTextViaReflection(scope.styleTag, scope.scopeFile, newText)
            }
        }
    }

    private fun tagName(el: PsiElement): String? = runCatching {
        val m = el.javaClass.methods.firstOrNull { mm ->
            mm.parameterCount == 0 && mm.name == "getName" && (
                mm.returnType == String::class.java || CharSequence::class.java.isAssignableFrom(mm.returnType)
            )
        } ?: return@runCatching null
        m.isAccessible = true
        return when (val r = m.invoke(el)) {
            is String -> r
            is CharSequence -> r.toString()
            else -> null
        }
    }.getOrNull()

    // 选区的 (start,end) 偏移（相对 PsiFile.text / styleTag.value 的文本），没选区返回 null
    private fun selectionRangeOrNull(sel: SelectionModel, file: PsiFile): Pair<Int, Int>? = runCatching {
        if (!sel.hasSelection()) return@runCatching null
        val s = sel.selectionStart
        val e = sel.selectionEnd
        if (e <= s) return@runCatching null
        // 选区范围会在后面 collectCandidateRulesets 时拿来判断 ruleset 是否与选区相交
        s to e
    }.getOrNull()

    private fun collectCandidateRulesets(root: PsiElement, range: Pair<Int, Int>?): List<CssRuleset> {
        val all = PsiTreeUtil.findChildrenOfType(root, CssRuleset::class.java).toList()
        if (range == null) return all
        val (s, e) = range
        return all.filter { rs ->
            val r = rs.textRange
            r.startOffset in s until e || r.endOffset in (s + 1)..e || (r.startOffset <= s && r.endOffset >= e)
        }
    }

    private fun groupBySignature(rules: List<CssRuleset>): Map<List<String>, List<CssRuleset>> {
        return rules.groupBy { rs -> DeclarationSignatureUtil.computeSignatureList(rs) }
    }

    private fun readTagInnerTextViaReflection(styleTag: PsiElement): String = runCatching {
        // 常见两种实现：getValue() 返回 XmlTagValue 或直接返回 String；兜底取 text
        val getVal = styleTag.javaClass.methods.firstOrNull {
            it.name == "getValue" && it.parameterCount == 0
        }
        if (getVal != null) {
            getVal.isAccessible = true
            when (val obj = getVal.invoke(styleTag)) {
                is String -> return@runCatching obj
                is CharSequence -> return@runCatching obj.toString()
                else -> {
                    val textM = obj?.javaClass?.methods?.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
                    if (textM != null) {
                        textM.isAccessible = true
                        (textM.invoke(obj) as? CharSequence)?.toString()?.let { return@runCatching it }
                    }
                }
            }
        }
        val textM2 = styleTag.javaClass.methods.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
        if (textM2 != null) {
            textM2.isAccessible = true
            (textM2.invoke(styleTag) as? CharSequence)?.toString() ?: ""
        } else ""
    }.getOrDefault("")

    private fun replaceTagInnerTextViaReflection(styleTag: PsiElement, file: PsiFile, newText: String) {
        runCatching {
            // 1) 尝试 setValue(...)
            val setValue = styleTag.javaClass.methods.firstOrNull {
                it.parameterCount == 1 && (it.name == "setValue" || it.name == "setTagValue")
            }
            if (setValue != null) {
                setValue.isAccessible = true
                setValue.invoke(styleTag, newText)
                // 不在 write action 内调用 doPostponedOperationsAndUnblockDocument：
                // 它会同步派发 AWT 事件，导致 "AWT events are not allowed inside write action" 错误。
                // write action 结束后平台会自动 flush。
                return@runCatching
            }
            // 2) 兜底：直接走 document 替换，用 styleTag.value 的 textRange 在 file.document 上定位
            val doc = com.intellij.psi.PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return@runCatching
            val tr = styleTag.textRange
            val tagText = doc.getText(tr) ?: return@runCatching
            // 简单正则：找第一个 > 和最后一个 </style> 之间的内容（只处理 <style ...>inner</style> 常见形式）
            val closeIdx = tagText.indexOfLast { it == '>' }
            val openEnd = if (closeIdx < 0) -1 else closeIdx + 1
            val endStart = tagText.indexOf("</style", openEnd.coerceAtLeast(0))
            if (openEnd < 0 || endStart < openEnd) return@runCatching
            val absStart = tr.startOffset + openEnd
            val absEnd = tr.startOffset + endStart
            if (absEnd < absStart) return@runCatching
            doc.replaceString(absStart, absEnd, newText)
        }
    }

    // ===================================================================
    // 纯文本主函数：Less 语法处理（CSS 子集同样支持），也兼容 SCSS 的常用子集
    // ===================================================================
    internal fun extractDuplicateInText(
        source: String,
        @Suppress("UNUSED_PARAMETER") file: PsiFile,
        @Suppress("UNUSED_PARAMETER") root: PsiElement,
        selection: Pair<Int, Int>?
    ): String {
        val normalized = source.replace("\r\n", "\n")
        val ruleRanges = mutableListOf<RuleSpan>()
        parseTopLevelRules(normalized, ruleRanges)
        if (ruleRanges.isEmpty()) return source

        // 如果有 selection，只处理 selection 覆盖的 ruleset（允许部分包含也算）
        val selStart = selection?.first ?: 0
        val selEnd = selection?.second ?: normalized.length
        val eligible = ruleRanges.filterIndexed { _, rs ->
            (rs.bodyStart <= selEnd && rs.bodyEnd >= selStart)
        }

        // 归一化签名 → 分组（同时保存每个签名的"首次出现时的原始顺序+视觉格式声明列表"，用于命名与写回定义）
        val bySign = mutableMapOf<List<String>, MutableList<RuleSpan>>()
        val prettyBySign = mutableMapOf<List<String>, List<String>>()
        for (r in eligible) {
            val body = normalized.substring(r.bodyStart, r.bodyEnd)
            val (sign, pretty) = normalizeDeclsWithPretty(body)
            if (sign.isEmpty()) continue
            bySign.getOrPut(sign) { mutableListOf() }.add(r)
            prettyBySign.putIfAbsent(sign, pretty)
        }
        // 共享声明数 >= MIN_SHARED_DECLARATIONS 且重复规则数 >= 2 才提取；按大 offset 在前排序（避免错位）
        val groups = bySign.filter { (sign, list) ->
            list.size >= 2 && sign.size >= MIN_SHARED_DECLARATIONS
        }.values.sortedByDescending { g -> g.first().bodyStart }

        if (groups.isEmpty()) return source
        val newMixinDefs = mutableListOf<Pair<String, List<String>>>() // name → prettyDecls（按原始顺序 + 视觉一致的 prop: valueNorm）
        val sb = StringBuilder(normalized)

        // 为每个 signature 规划 mixin 名：按首次出现的 ruleset 的声明顺序取属性（更符合用户直觉），先到先得；重名加后缀 2/3...
        val assignedNames = hashMapOf<List<String>, String>()
        for (g in groups) {
            val firstBody = normalized.substring(g.first().bodyStart, g.first().bodyEnd)
            val (sign, pretty) = normalizeDeclsWithPretty(firstBody)
            val name = assignedNames.getOrPut(sign) { nextMixinNameFromPretty(pretty, assignedNames.values) }
            newMixinDefs.add(name to pretty)
            // 逐个 ruleset 原地替换 body 为 ".shared-xxx;" 调用
            for (r in g) {
                val indent = detectIndentBefore(normalized, r.bodyStart)
                val call = if (indent.isEmpty()) ".${name};" else "${indent}.${name};"
                sb.replace(r.bodyStart, r.bodyEnd, call)
                // 注意：因为我们是按 groups 从大 offset 往小处理，每组内部 r 也应从大到小
            }
        }

        // 现在往源文件末尾追加所有新 mixin。先定位"最后一个非空行之后"的位置做合适插入：
        val append = buildString {
            append("\n\n")
            for ((name, prettyDecls) in newMixinDefs.reversed()) {
                append(".$name {\n")
                for (prop in prettyDecls) append("    $prop;\n")
                append("}\n\n")
            }
        }
        sb.append(append)

        return sb.toString()
    }

    private fun detectIndentBefore(text: String, pos: Int): String {
        var i = pos - 1
        while (i >= 0 && text[i] != '\n') i--
        val start = i + 1
        var j = start
        while (j < pos && (text[j] == ' ' || text[j] == '\t')) j++
        return text.substring(start, j)
    }

    // mixin 命名：按"第一个 ruleset 里的原始声明顺序"取属性（而不是按字母排序后的），更符合用户写代码时的第一直觉。
    // 例：第一组 ruleset 写的顺序是 padding → color → 命名为 shared-padding-color（而不是 shared-color-padding）。
    // 前 1~3 个属性连缀；冲突自动加 2/3... 后缀。
    private fun nextMixinNameFromPretty(prettyDecls: List<String>, used: Collection<String>): String {
        val props = prettyDecls.mapNotNull {
            val colon = it.indexOf(':')
            if (colon <= 0) null else it.substring(0, colon).trim().lowercase()
        }.filter { it.isNotEmpty() }.distinct()

        val base = if (props.isEmpty()) "shared-block" else {
            val tail = props.drop(1).take(2).joinToString("-") { NamingUtil.kebabToCamel(it.replace('/', '-')) }
            val first = NamingUtil.kebabToCamel(props.first())
            if (tail.isEmpty()) "shared-${NamingUtil.camelToKebab(first)}" else "shared-${NamingUtil.camelToKebab(first)}-${NamingUtil.camelToKebab(tail)}"
        }.trimEnd('-').ifBlank { "shared-block" }

        if (base !in used) return base
        var i = 2
        while ("$base$i" in used) i++
        return "$base$i"
    }

    // 旧 API：基于签名（sorted props）命名，保留向后兼容（isAvailable 判断仍可以用）
    private fun nextMixinName(sign: List<String>, used: Collection<String>): String = nextMixinNameFromPretty(sign, used)

    // ================================================================
    // 文本级 CSS/Less/SCSS 规则解析：抓顶层 ruleset 的 selector 与 body 起止
    // ================================================================
    private data class RuleSpan(
        val start: Int, val end: Int,               // 全 ruleset：从选择器第一个字符到右花括号后（含）
        val selectorStart: Int, val selectorEnd: Int, // selector 文本（不含 {）
        val bodyStart: Int, val bodyEnd: Int        // { 和 } 之间的内容（不含括号自身）
    )

    private fun parseTopLevelRules(text: String, out: MutableList<RuleSpan>) {
        val len = text.length
        var i = 0
        while (i < len) {
            val c = text[i]
            when {
                c == '/' && i + 1 < len && text[i + 1] == '*' -> {
                    i = skipBlockComment(text, i)
                }
                c == '/' && i + 1 < len && text[i + 1] == '/' -> {
                    while (i < len && text[i] != '\n') i++
                }
                c.isWhitespace() -> i++
                c == ';' -> i++ // 顶层 @import/@media 之外的分号（纯 CSS 少见），安全跳过
                c == '@' -> {
                    // @规则：@media / @keyframes / @mixin 等，内部的 ruleset 我们不去动；跳过整段 @xxx { ... }
                    i = skipAtRuleOrDeclaration(text, i)
                }
                c == '}' -> {
                    // 遇到游离的右括号（嵌套语法下常见），跳过继续解析
                    i++
                }
                else -> {
                    // 可能是 ruleset 开头：找到到第一个 {，确保是顶层的
                    val selectorStart = i
                    val braceIdx = findTopLevelBrace(text, i)
                    if (braceIdx < 0) return // 剩下的文本构不成 ruleset，结束
                    val selectorEnd = braceIdx
                    val braceOpen = braceIdx + 1
                    val (bodyEnd, braceCloseEnd) = matchBraced(text, braceOpen) ?: break
                    val end = braceCloseEnd
                    out.add(RuleSpan(
                        start = selectorStart,
                        end = end,
                        selectorStart = selectorStart,
                        selectorEnd = selectorEnd,
                        bodyStart = braceOpen,
                        bodyEnd = bodyEnd
                    ))
                    i = end
                }
            }
        }
    }

    private fun skipBlockComment(text: String, start: Int): Int {
        val end = text.indexOf("*/", start + 2)
        return if (end < 0) text.length else end + 2
    }

    private fun skipAtRuleOrDeclaration(text: String, start: Int): Int {
        // @xxx 可能是单行（@import ...;）也可能是块（@media ... { ... }）
        var i = start + 1
        val len = text.length
        // 先跑到第一个 { 或 ;
        while (i < len) {
            val c = text[i]
            when {
                c == '/' && i + 1 < len && text[i + 1] == '*' -> i = skipBlockComment(text, i)
                c == '"' || c == '\'' -> i = skipString(text, i)
                c == ';' -> return i + 1
                c == '{' -> {
                    val (bodyEnd, after) = matchBraced(text, i + 1) ?: return len
                    return after
                }
                else -> i++
            }
        }
        return len
    }

    private fun skipString(text: String, start: Int): Int {
        val q = text[start]
        var i = start + 1
        val len = text.length
        while (i < len) {
            val c = text[i]
            if (c == '\\' && i + 1 < len) { i += 2; continue }
            if (c == q) return i + 1
            i++
        }
        return len
    }

    private fun findTopLevelBrace(text: String, start: Int): Int {
        var i = start
        val len = text.length
        while (i < len) {
            val c = text[i]
            when {
                c == '/' && i + 1 < len && text[i + 1] == '*' -> i = skipBlockComment(text, i)
                c == '/' && i + 1 < len && text[i + 1] == '/' -> while (i < len && text[i] != '\n') i++
                c == '"' || c == '\'' -> i = skipString(text, i)
                c == '{' -> return i
                c == ';' || c == '}' -> return -1 // 选择器里出现 ; / } → 不是合法 selector，放弃
                else -> i++
            }
        }
        return -1
    }

    // 从 openAfterBrace（第一个 { 的下个字符开始）匹配到对应 }；返回 bodyEnd（} 前 index）和 after（} 后 index）
    private fun matchBraced(text: String, openAfterBrace: Int): Pair<Int, Int>? {
        var depth = 1
        var i = openAfterBrace
        val len = text.length
        while (i < len) {
            val c = text[i]
            when {
                c == '/' && i + 1 < len && text[i + 1] == '*' -> i = skipBlockComment(text, i)
                c == '/' && i + 1 < len && text[i + 1] == '/' -> while (i < len && text[i] != '\n') i++
                c == '"' || c == '\'' -> i = skipString(text, i)
                c == '{' -> { depth++; i++ }
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        val bodyEnd = i
                        val after = i + 1
                        return bodyEnd to after
                    }
                    i++
                }
                else -> i++
            }
        }
        return null
    }

    // 把一块声明文本解析成 prop:value; 列表。
    // 返回 Pair(sortedSignature, prettyDeclsOriginalOrder)：
    //   - sortedSignature：排序后的 ["prop:norm_value"]，用于签名对比（顺序无关）
    //   - prettyDeclsOriginalOrder：按原始 ruleset 声明顺序写出的 ["prop: valueNorm"]，
    //     中间固定 1 空格（视觉统一），值做最小空白归一化；用于最终写回 mixin 定义以及 mixin 命名时的属性顺序。
    internal fun normalizeDeclsWithPretty(body: String): Pair<List<String>, List<String>> {
        val clean = stripComments(body)
        val len = clean.length
        var i = 0
        val sign = mutableListOf<String>()   // 签名：排序后的 prop:norm_value
        val pretty = mutableListOf<String>() // 写回：原文顺序的 prop: valueNorm（统一 1 空格）
        while (i < len) {
            // 跳过空白 / 分号
            while (i < len && (clean[i] == ' ' || clean[i] == '\t' || clean[i] == '\n' || clean[i] == '\r' || clean[i] == ';')) i++
            if (i >= len) break
            val start = i
            var colon = -1
            while (i < len) {
                val c = clean[i]
                if (c == '"' || c == '\'') {
                    i = skipString(clean, i)
                    continue
                }
                if (c == '(') {
                    var d = 1
                    i++
                    while (i < len && d > 0) {
                        val cc = clean[i]
                        if (cc == '"' || cc == '\'') i = skipString(clean, i)
                        else if (cc == '(') { d++; i++ }
                        else if (cc == ')') { d--; i++ }
                        else i++
                    }
                    continue
                }
                if (c == '{' || c == '}') {
                    val (_, after) = matchBraced(clean, i + 1) ?: return sign.sorted() to pretty
                    i = after
                    continue
                }
                if (c == ';' || c == '}' || c == '{') break
                if (c == ':' && colon < 0) colon = i
                i++
            }
            if (colon in start until i) {
                val prop = clean.substring(start, colon).trim().lowercase()
                val rawVal = clean.substring(colon + 1, i).trim()
                val valueNorm = DeclarationSignatureUtil.normalizeValue(rawVal)
                if (prop.isNotEmpty() && valueNorm.isNotEmpty()) {
                    sign.add("$prop:$valueNorm")
                    pretty.add("$prop: $valueNorm") // 统一 1 空格，视觉一致
                }
            }
            // 跳到下一个 ;
            while (i < len && clean[i] != ';') {
                val cc = clean[i]
                if (cc == '"' || cc == '\'') i = skipString(clean, i)
                else if (cc == '{') {
                    val (_, after) = matchBraced(clean, i + 1) ?: break
                    i = after
                } else i++
            }
            if (i < len && clean[i] == ';') i++
        }
        return sign.sorted() to pretty
    }

    // 旧 API：只返回签名（向后兼容）
    internal fun normalizeDecls(body: String): List<String> = normalizeDeclsWithPretty(body).first

    private fun stripComments(src: String): String {
        val len = src.length
        val sb = StringBuilder(len)
        var i = 0
        while (i < len) {
            val c = src[i]
            if (c == '/' && i + 1 < len && src[i + 1] == '*') {
                val end = src.indexOf("*/", i + 2)
                if (end < 0) return sb.toString()
                i = end + 2
            } else if (c == '/' && i + 1 < len && src[i + 1] == '/') {
                while (i < len && src[i] != '\n') i++
            } else if (c == '"' || c == '\'') {
                val end = skipString(src, i)
                sb.append(src, i, end)
                i = end
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
