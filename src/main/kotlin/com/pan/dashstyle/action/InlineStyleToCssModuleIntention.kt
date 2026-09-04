package com.pan.dashstyle.action

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.css.CssFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeNameValuePair
import com.intellij.lang.css.CSSLanguage
import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.Logger

/**
 * Alt+Enter 快速修复：把 JSX/Vue 里的 inline style 对象提取为 CSS Module class。
 */
@Suppress("UnstableApiUsage")
class InlineStyleToCssModuleIntention : BaseIntentionAction() {

    companion object {
        private val LOG = Logger.getInstance(InlineStyleToCssModuleIntention::class.java)
        private val VALID_JSX_STYLE_RE = Regex("""^\s*style\s*=\s*""")
        private val VALID_VUE_STYLE_RE = Regex("""^\s*(?:v-bind:)?style\s*=\s*""")
        private val CLASS_NAME_RE = Regex("""^[_a-zA-Z][_a-zA-Z0-9-]*$""")
    }

    override fun getText(): String = "Extract inline style to CSS Module..."
    override fun getFamilyName(): String = "DashStyle: Inline Style → CSS Module"

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        val offset = editor.caretModel.offset
        val at = file.findElementAt(offset)
        // 兜底：取 offset-1 / offset+1 的元素避免光标夹在两个 Psi 之间
        if (at != null && locateStyleAttribute(at, file, offset) != null) return true
        val prev = (offset - 1).coerceAtLeast(0).let { file.findElementAt(it) }
        if (prev != null && prev !== at && locateStyleAttribute(prev, file, offset) != null) return true
        val next = (offset + 1).coerceAtMost((file.textLength - 1).coerceAtLeast(0)).let { file.findElementAt(it) }
        if (next != null && next !== at && next !== prev && locateStyleAttribute(next, file, offset) != null) return true
        return false
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        // 意图可能在后台线程被调用（例如对文件副本做意图预览/分析时，
        // 当前线程可能是 DefaultDispatcher-worker-N）。PSI 访问、对话框、
        // 写操作都要求 EDT，因此统一切到 EDT 再执行。
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            // 当在后台线程且有 read-access 时（文件副本上的意图预览/分析），
            // 直接返回，不执行任何操作。实际意图执行会在 EDT 上重新调用。
            // 不能使用 invokeLater，因为框架会将其视为 side effect 并报错。
            if (app.isReadAccessAllowed) return
            app.invokeAndWait { invoke(project, editor, file) }
            return
        }
        val offset = editor.caretModel.offset
        val loc = listOf(offset, (offset - 1).coerceAtLeast(0), (offset + 1).coerceAtMost((file.textLength - 1).coerceAtLeast(0)))
            .asSequence().mapNotNull { file.findElementAt(it) }
            .firstNotNullOfOrNull { locateStyleAttribute(it, file, offset) }
            ?: run { Messages.showWarningDialog(project,
                "No style attribute found under cursor.", "Extract to CSS Module"); return }

        val styleObjText = extractObjectLiteral(loc) ?: run {
            Messages.showWarningDialog(project,
                "Cannot extract: the cursor must be on a plain object style attribute (`style={{...}}` or `:style=\"{...}\"`).",
                "Extract to CSS Module")
            return
        }

        val cssDeclarations = try {
            JsonToCssCopyPastePreProcessor.Util.convertJsonToCss(styleObjText)
        } catch (t: Throwable) {
            // 兜底：extractObjectLiteral 有时剥壳不干净（例如光标在 value 内部时
            // attrPsi.text 只是局部片段）。而 convertJsonToCss 的 normalizeStyleExpression
            // 本身支持 `name={{...}}` / `name={...}` / `{...}` 全形式，因此直接用
            // 完整属性文本再试一次，尽量避免误报 "Not a recognized style object"。
            val retry = runCatching {
                JsonToCssCopyPastePreProcessor.Util.convertJsonToCss(loc.attrPsi.text)
            }.getOrNull()
            if (!retry.isNullOrBlank()) retry
            else {
                LOG.warn("convertJsonToCss failed", t)
                Messages.showErrorDialog(project,
                    "Failed to convert style object to CSS: ${t.message}",
                    "Extract to CSS Module")
                return
            }
        }
        if (cssDeclarations.isBlank()) return

        val candidates = SemanticClassNameInferrer.inferCandidates(
            styleAttrElement = loc.attrPsi,
            cssDeclarations = cssDeclarations,
            contextFileElement = file
        )
        val defaultName = SemanticClassNameInferrer.topCandidate(candidates)
        val hint = candidates.take(5).joinToString("\n  - ", prefix = "  - ") { c ->
            "${c.name}  (${c.score} pts, from ${c.source})"
        }

        val chosenName = askRenameDialog(project, defaultName, hint) ?: return

        val target = locateTargetCssModule(project, file, loc) ?: run {
            Messages.showErrorDialog(project,
                "Cannot find target CSS Module location.\n" +
                        "Vue: add a `<style module>` block to the SFC.\n" +
                        "React/TSX: add `import styles from './Xxx.module.(css|scss|less)'` to the file.",
                "Extract to CSS Module")
            return
        }

        WriteCommandAction.writeCommandAction(project)
            .withName("Extract inline style to CSS Module")
            .run<Nothing> {
                val ruleText = formatRule(chosenName, cssDeclarations)
                target.appendRule(project, chosenName, ruleText)
                replaceStyleAttributeWithClass(loc, chosenName, target)
            }

        if (target is FileTarget) {
            val vf = LocalFileSystem.getInstance().findFileByPath(target.absolutePath)
            if (vf != null && vf.isValid) {
                ApplicationManager.getApplication().invokeLater {
                    val fd = OpenFileDescriptor(project, vf, 0)
                    FileEditorManager.getInstance(project).openTextEditor(fd, true)
                }
            }
        }

        val declCount = cssDeclarations.lineSequence().filter { it.contains(':') }.count()
        Messages.showInfoMessage(
            project,
            "Extracted `.${chosenName}` ($declCount declarations) to $target.\n\n${cssDeclarations.trim()}",
            "Extract to CSS Module OK"
        )
    }

    // ================================================================
    // 共用 helper：先于 inner class 声明，避免前向引用问题
    // ================================================================
    private fun appendRuleToPsi(project: Project, target: PsiElement, ruleText: String) {
        val factory = PsiFileFactory.getInstance(project)
        val lang = (target as? PsiFile)?.language ?: CSSLanguage.INSTANCE
        val tmp = try {
            factory.createFileFromText("__dashstyle_tmp__.css", lang, ruleText)
        } catch (_: Throwable) {
            factory.createFileFromText("__dashstyle_tmp__.css", PlainTextLanguage.INSTANCE, ruleText)
        }
        val last = target.lastChild
        if (last != null) target.addAfter(tmp.firstChild, last) else target.add(tmp.firstChild)
    }

    private fun findEmbeddedCssInStyleTag(el: PsiElement): PsiFile? {
        if (el is PsiFile && (el is CssFile || el.virtualFile?.extension == "css")) return el
        for (c in el.children) {
            val r = findEmbeddedCssInStyleTag(c)
            if (r != null) return r
        }
        return null
    }

    // ================================================================
    // 定位 style 属性：兼容三层
    //   ① 新版 WebStorm-2025.3 JSX：属性 Psi 是 JSAttribute/JSXAttribute 或其自定义子类，
    //      统一用 attr.name == "style" + PsiTreeUtil.safe text 识别；
    //   ② Vue XmlAttribute（:style / v-bind:style）；
    //   ③ 纯文本兜底（用户可能光标选中 style={{...}} 中间的 property，Psi 是标识符）。
    // ================================================================
    data class StyleAttrLoc(
        val attrPsi: PsiElement,
        val sourceLanguage: Lang,
        val jsxAttribute: PsiElement? = null,
        val xmlAttribute: XmlAttribute? = null
    ) {
        enum class Lang { JSX_TSX, VUE }
    }

    private fun locateStyleAttribute(cursor: PsiElement, file: PsiFile? = null, caretOffset: Int = -1): StyleAttrLoc? {
        var cur: PsiElement? = cursor
        for (depth in 0..30) {
            if (cur == null) break
            val className = cur.javaClass.name
            when {
                // JSX/TSX 属性：新版 JSX 有多种具体 class（JSAttribute/JSXAttribute/JsxAttribute/JsxAttributeImpl...），
                // 统一反射获取 name；如果拿不到但 className 命中关键字段，也进一步按 .text 前缀判断。
                className.contains("JSAttribute", ignoreCase = true) ||
                    className.contains("JSXAttribute", ignoreCase = true) ||
                    className.contains("JsxAttribute", ignoreCase = true) -> {
                    val attrName = runCatching {
                        cur.javaClass.methods.firstOrNull { m ->
                            m.name == "getName" && m.parameterCount == 0
                        }?.invoke(cur) as? CharSequence
                    }?.getOrNull()?.toString()
                    if (attrName == "style" || attrName?.endsWith(":style") == true || (attrName.isNullOrBlank() && cur.text.trimStart().startsWith("style"))) {
                        val hasValue = runCatching {
                            val valuesM = cur.javaClass.methods.firstOrNull { m ->
                                m.name == "getValues" && m.parameterCount == 0
                            }
                            val v = valuesM?.invoke(cur) as? List<*>
                            (v?.size ?: 0) > 0
                        }.getOrDefault(false) || cur.text.contains('=')
                        if (hasValue) return StyleAttrLoc(cur, StyleAttrLoc.Lang.JSX_TSX, jsxAttribute = cur)
                    }
                }
                cur is XmlAttribute -> {
                    val n = cur.name
                    if (n == "style" || n == ":style" || n == "v-bind:style") {
                        // 某些 IntelliJ 版本中 JSX 属性可能被解析为 XmlAttribute，
                        // 此时需根据文件扩展名判断语言类型，避免 React 项目误生成 Vue 语法
                        val ext = file?.virtualFile?.extension?.lowercase()
                        val lang = when {
                            ext == "vue" -> StyleAttrLoc.Lang.VUE
                            n == ":style" || n == "v-bind:style" -> StyleAttrLoc.Lang.VUE
                            ext in listOf("tsx", "jsx", "ts", "js") -> StyleAttrLoc.Lang.JSX_TSX
                            else -> StyleAttrLoc.Lang.VUE
                        }
                        return StyleAttrLoc(cur, lang, xmlAttribute = cur)
                    }
                }
            }
            // 兜底：当前节点文本或其祖先文本包含 style=...（支持光标在 value 内部节点深处）
            val t = cur.text
            if (!t.isNullOrEmpty() && t.length in 4..4096) {
                val trimFirst = t.trimIndent().lines().firstOrNull().orEmpty()
                if (VALID_JSX_STYLE_RE.containsMatchIn(trimFirst) || VALID_JSX_STYLE_RE.containsMatchIn(t))
                    return StyleAttrLoc(cur, StyleAttrLoc.Lang.JSX_TSX)
                if (VALID_VUE_STYLE_RE.containsMatchIn(trimFirst) || VALID_VUE_STYLE_RE.containsMatchIn(t))
                    return StyleAttrLoc(cur, StyleAttrLoc.Lang.VUE)
            }
            cur = cur.parent
        }

        // 最后兜底：如果 caretOffset 合法，在 file 的 Document 文本里按字符范围找覆盖 offset 的 style=... 片段，
        // 再按那个范围的 startOffset 反查 PsiElement 再跑一次 locateStyleAttribute。
        if (caretOffset >= 0 && file != null) {
            val doc = PsiDocumentManager.getInstance(file.project).getDocument(file)
            if (doc != null && caretOffset < doc.textLength) {
                val rawText = doc.text
                // 往左找最近的 'style' 起始（最多往前查 80 个字符）
                val leftBound = (caretOffset - 120).coerceAtLeast(0)
                val rightBound = (caretOffset + 40).coerceAtMost(rawText.length)
                val window = rawText.substring(leftBound, rightBound)
                val jsxMatch = Regex("""\bstyle\s*=""").find(window)
                    ?: Regex("""\bstyle\s*=\s*\{""").find(window)
                val vueMatch = Regex("""(v-bind)?:style\s*=""").find(window)
                val match = jsxMatch ?: vueMatch
                if (match != null) {
                    val absStart = leftBound + match.range.first
                    val psi = file.findElementAt(absStart)
                    if (psi != null) {
                        val found = run locate@{
                            var c: PsiElement? = psi
                            for (d in 0..20) {
                                if (c == null) break
                                val attrName = runCatching {
                                    c.javaClass.methods.firstOrNull { m -> m.name == "getName" && m.parameterCount == 0 }?.invoke(c) as? CharSequence
                                }?.getOrNull()?.toString()
                                if (c is XmlAttribute) return@locate StyleAttrLoc(c, StyleAttrLoc.Lang.VUE, xmlAttribute = c)
                                if (attrName == "style") return@locate StyleAttrLoc(c, StyleAttrLoc.Lang.JSX_TSX, jsxAttribute = c)
                                val rc = c.javaClass.name
                                if ((rc.contains("JSAttribute", true) || rc.contains("JsxAttribute", true)) && c.text.trimStart().startsWith("style"))
                                    return@locate StyleAttrLoc(c, StyleAttrLoc.Lang.JSX_TSX, jsxAttribute = c)
                                c = c.parent
                            }
                            null
                        }
                        if (found != null) return found
                    }
                }
            }
        }
        return null
    }

    // ================================================================
    // 提取 { key: value, ... } 文本（同时兼容新版 JSX 的 valueNode API）
    // ================================================================
    private fun extractObjectLiteral(loc: StyleAttrLoc): String? {
        if (loc.jsxAttribute != null) {
            val pair: PsiElement? = runCatching {
                val c = loc.jsxAttribute.javaClass
                val m = c.methods.firstOrNull {
                    it.parameterCount == 1 && it.name == "getValueByName"
                }
                val defCls = Class.forName(
                    "com.intellij.lang.javascript.psi.ecmal4.JSAttributeNameValuePair",
                    false, c.classLoader
                )
                val defField = runCatching {
                    defCls.fields.firstOrNull { f ->
                        "DEFAULT" == f.name || "default" == f.name
                    }?.get(null)
                }.getOrNull()
                if (m != null && defField != null) m.invoke(loc.jsxAttribute, defField) as? PsiElement else null
            }.getOrNull() ?: run {
                val values = runCatching {
                    val m = loc.jsxAttribute.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && it.name == "getValues"
                    }
                    (m?.invoke(loc.jsxAttribute) as? List<*>)?.firstOrNull() as? PsiElement
                }.getOrNull()
                values
            }
            if (pair != null) {
                val valueNode = runCatching {
                    pair.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name == "getValueNode" }
                        ?.invoke(pair) as? PsiElement
                }.getOrNull()
                if (valueNode != null) {
                    val obj = PsiTreeUtil.findChildOfType(valueNode, JSObjectLiteralExpression::class.java)
                    if (obj != null) return obj.text
                    return unwrapBracesOnce(valueNode.text)
                }
            }
            // 最后兜底：直接 attr.text 正则拉
            val t = loc.attrPsi.text
            val m = Regex("""=\s*(\{[\s\S]*\})\s*$""").find(t) ?: return null
            return unwrapBracesOnce(m.groupValues[1])
        }
        if (loc.xmlAttribute != null) {
            val v = loc.xmlAttribute.value
                ?: loc.xmlAttribute.valueElement?.text?.trim('"', '\'')
            if (v != null && v.startsWith('{') && v.endsWith('}')) return v
        }
        val t = loc.attrPsi.text
        val m = Regex("""=\s*(\{[\s\S]*\})\s*$""").find(t) ?: return null
        return unwrapBracesOnce(m.groupValues[1])
    }

    private fun unwrapBracesOnce(s: String): String {
        val t = s.trim()
        if (t.startsWith("{") && t.endsWith("}")) {
            val inner = t.substring(1, t.length - 1).trim()
            if (inner.startsWith("{") && inner.endsWith("}")) return inner
        }
        return t
    }

    // ================================================================
    // Rename 对话框
    // ================================================================
    private fun askRenameDialog(project: Project, default: String, hint: String): String? {
        return Messages.showInputDialog(
            project,
            "Choose a CSS class name (kebab-case recommended).\n\nSemantic candidates:\n$hint",
            "Rename extracted CSS class",
            Messages.getQuestionIcon(),
            default,
            object : InputValidatorEx {
                override fun checkInput(input: String): Boolean = canClose(input)
                override fun canClose(input: String?): Boolean =
                    !input.isNullOrBlank() && CLASS_NAME_RE.matches(input)
                override fun getErrorText(input: String?): String? =
                    when {
                        input.isNullOrBlank() -> "Class name cannot be empty."
                        !CLASS_NAME_RE.matches(input) ->
                            "Invalid class name (use letters/digits/-/_; must start with letter/_)."
                        else -> null
                    }
            }
        )
    }

    private fun formatRule(className: String, declarations: String): String {
        val indented = declarations.lineSequence().mapNotNull { line ->
            val t = line.trim()
            if (t.isBlank()) null else "  $t"
        }.joinToString("\n", postfix = "\n")
        return "\n.${className} {\n${indented}}\n"
    }

    // ================================================================
    // 目标 CSS 定位
    // ================================================================
    sealed class CssModuleTarget {
        protected var ruleCount = 0
        abstract fun appendRule(project: Project, className: String, ruleText: String)
        abstract fun classNameAccessExpr(className: String): String
    }

    inner class FileTarget(
        val absolutePath: String,
        val importVariableName: String
    ) : CssModuleTarget() {
        override fun appendRule(project: Project, className: String, ruleText: String) {
            val vf = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return
            val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return
            this@InlineStyleToCssModuleIntention.appendRuleToPsi(project, psiFile, ruleText)
            ruleCount++
        }
        override fun classNameAccessExpr(className: String): String =
            if (className.all { it.isLetterOrDigit() || it == '_' })
                "$importVariableName.$className"
            else
                "$importVariableName[\"$className\"]"
        override fun toString(): String = "file $absolutePath (via $importVariableName)"
    }

    inner class VueStyleModuleTarget(
        val styleTag: XmlTag,
        val styleVar: String = "\$style"
    ) : CssModuleTarget() {
        override fun appendRule(project: Project, className: String, ruleText: String) {
            val embedded = this@InlineStyleToCssModuleIntention.findEmbeddedCssInStyleTag(styleTag)
            if (embedded != null) {
                this@InlineStyleToCssModuleIntention.appendRuleToPsi(project, embedded, ruleText)
            } else {
                // fallback：以文本形式拼到 styleTag 内部末尾
                val factory = PsiFileFactory.getInstance(project)
                val tmp = factory.createFileFromText(
                    "__tmp__.css", CSSLanguage.INSTANCE, ruleText
                )
                val ruleElem = tmp.firstChild ?: return
                styleTag.add(ruleElem)
            }
            ruleCount++
        }
        override fun classNameAccessExpr(className: String): String =
            if (className.all { it.isLetterOrDigit() || it == '_' })
                "$styleVar.$className"
            else
                "$styleVar['$className']"
        override fun toString(): String = "Vue <style module> ($styleVar)"
    }

    private fun locateTargetCssModule(project: Project, file: PsiFile, loc: StyleAttrLoc): CssModuleTarget? {
        val vFile = file.virtualFile
        val ext = vFile?.extension?.lowercase()

        // Vue：优先同文件 <style module>，其次任何 <style>
        if (ext == "vue") {
            val (styleTag, alias) = CssModuleResolver.findVueStyleModule(file) ?: return null
            return VueStyleModuleTarget(styleTag, alias)
        }

        // React/TSX：已有 import 指向 module 文件
        CssModuleResolver.findExistingModuleImport(file)?.let { (vf, alias) ->
            return FileTarget(vf.path, alias)
        }

        // 兜底：同目录下同名 Xxx.module.* 文件
        CssModuleResolver.findSameNameModuleFile(file)?.let { vf ->
            return FileTarget(vf.path, "styles")
        }

        return null
    }

    // ================================================================
    // 把原 style={...} 替换成 className=... / :class=...
    // ================================================================
    private fun replaceStyleAttributeWithClass(
        loc: StyleAttrLoc,
        className: String,
        target: CssModuleTarget
    ) {
        val access = target.classNameAccessExpr(className)
        val project = loc.attrPsi.project
        val file = loc.attrPsi.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return

        // 根据文件扩展名确认语言类型，避免因 PSI 检测错误导致在 React 中生成 Vue 语法
        val ext = file.virtualFile?.extension?.lowercase()
        val effectiveLang = when {
            ext == "vue" -> StyleAttrLoc.Lang.VUE
            ext in listOf("tsx", "jsx", "ts", "js") -> StyleAttrLoc.Lang.JSX_TSX
            else -> loc.sourceLanguage
        }

        when (effectiveLang) {
            StyleAttrLoc.Lang.JSX_TSX -> {
                val parent = loc.attrPsi.parent
                val existingClassName = findClassNameAttr(parent)
                if (existingClassName != null) {
                    // 合并到已有 className 中，然后删除 style 属性
                    mergeIntoExistingClassName(existingClassName, access, loc.attrPsi, document)
                    return
                }
                // 没有已有 className，直接替换 style 为 className
                val range = loc.attrPsi.textRange
                document.replaceString(range.startOffset, range.endOffset, "className={$access}")
            }
            StyleAttrLoc.Lang.VUE -> {
                // Vue 中 class 和 :class 可以共存，直接替换 :style 为 :class
                val range = loc.attrPsi.textRange
                document.replaceString(range.startOffset, range.endOffset, ":class=\"$access\"")
            }
        }
    }

    /**
     * 在父元素中查找已有的 className 属性。
     * 搜索所有子节点中名字为 "className" 的 JSX/JSAttribute。
     */
    private fun findClassNameAttr(parent: PsiElement): PsiElement? {
        return PsiTreeUtil.collectElements(parent) { el ->
            val c = el.javaClass.name
            (c.contains("JSAttribute", ignoreCase = true) || c.contains("JSXAttribute", ignoreCase = true)) &&
                el != parent &&
                runCatching {
                    el.javaClass.methods.firstOrNull { m -> m.name == "getName" && m.parameterCount == 0 }
                        ?.invoke(el) == "className"
                }.getOrDefault(false)
        }.firstOrNull()
    }

    /**
     * 将新的 class 访问表达式合并到已有的 className 属性中，然后删除 style 属性。
     * 处理 className="foo" → className={clsx("foo", styles.newClass)} 和
     * className={expr} → className={clsx(expr, styles.newClass)} 两种形式。
     */
    private fun mergeIntoExistingClassName(
        existingClassName: PsiElement,
        newAccess: String,
        styleAttr: PsiElement,
        document: com.intellij.openapi.editor.Document
    ) {
        try {
            val existingText = existingClassName.text
            val eqIdx = existingText.indexOf('=')
            if (eqIdx < 0) return
            val valuePart = existingText.substring(eqIdx + 1).trim()
            val newClassNameText = "className={clsx($valuePart, $newAccess)}"
            val existingRange = existingClassName.textRange
            val styleRange = styleAttr.textRange

            // 先替换 className，再删除 style（顺序：先替换 className 不会改变 style 的 offset）
            document.replaceString(existingRange.startOffset, existingRange.endOffset, newClassNameText)
            // style 的 offset 在 className 替换后可能变化（如果 className 在 style 之前）
            val styleShift = newClassNameText.length - existingRange.length
            val adjustedStyleStart = if (styleRange.startOffset > existingRange.startOffset) {
                styleRange.startOffset + styleShift
            } else {
                styleRange.startOffset
            }
            val adjustedStyleEnd = if (styleRange.endOffset > existingRange.startOffset) {
                styleRange.endOffset + styleShift
            } else {
                styleRange.endOffset
            }
            // 删除 style 属性（包括前置空格/逗号）
            val beforeStyle = document.getText(com.intellij.openapi.util.TextRange(0, document.textLength))
                .substring(0, adjustedStyleStart)
            val trailingSpace = if (beforeStyle.endsWith(" ") || beforeStyle.endsWith("\t")) 1 else 0
            document.deleteString(adjustedStyleStart - trailingSpace, adjustedStyleEnd)
        } catch (t: Throwable) {
            LOG.warn("mergeIntoExistingClassName failed", t)
        }
    }
}
