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

    /** 当前文件所属的前端框架（决定 JSX 属性名 class/className 与合并策略）。 */
    internal enum class Framework { VUE, REACT, UNKNOWN }

    companion object {
        private val LOG = Logger.getInstance(InlineStyleToCssModuleIntention::class.java)
        private val VALID_JSX_STYLE_RE = Regex("""^\s*style\s*=\s*""")
        private val VALID_VUE_STYLE_RE = Regex("""^\s*(?:v-bind:)?style\s*=\s*""")
        private val CLASS_NAME_RE = Regex("""^[_a-zA-Z][_a-zA-Z0-9-]*$""")

        // 文件内证据：import 来源 / 框架标志性 API
        private val VUE_EVIDENCE_RE = Regex(
            """\bfrom\s+['"](?:vue|@vue/[^'"]+|vue-router|pinia|vuex)['"]|\bdefineComponent\s*\(|\buseCssModule\s*\("""
        )
        private val REACT_EVIDENCE_RE = Regex(
            """\bfrom\s+['"](?:react|react-dom|react-router|react-router-dom|next/[^'"]+)['"]|\bReact\.Component\b"""
        )
        private val CLSX_IMPORT_RE = Regex("""\bfrom\s+['"](?:clsx|classnames)['"]""")
        private val PACKAGE_JSON_VUE_RE = Regex(""""vue"\s*:""")
        private val PACKAGE_JSON_REACT_RE = Regex(""""react"\s*:""")

        /**
         * 框架探测（区分 Vue 的 TSX 和 React 的 TSX）：
         *  1. .vue 文件 → VUE；
         *  2. 文件内证据（import vue 系 / import react 系）——vue 优先，
         *     混用 react 类型工具的场景以组件框架为准；
         *  3. 都没有 → 从文件目录向上找 package.json 按 dependencies 判断；
         *     当前 package.json 两者都没有（或都有，多为 monorepo 根）→ 继续向上。
         */
        internal fun detectFramework(file: PsiFile): Framework {
            if (file.virtualFile?.extension?.lowercase() == "vue") return Framework.VUE
            val text = file.text
            if (VUE_EVIDENCE_RE.containsMatchIn(text)) return Framework.VUE
            if (REACT_EVIDENCE_RE.containsMatchIn(text)) return Framework.REACT
            return detectFrameworkFromPackageJson(file)
        }

        private fun detectFrameworkFromPackageJson(file: PsiFile): Framework {
            var dir = file.virtualFile?.parent ?: return Framework.UNKNOWN
            var depth = 0
            while (depth < 20) {
                val pkg = dir.findChild("package.json")
                if (pkg != null && pkg.isValid && !pkg.isDirectory) {
                    val text = runCatching { String(pkg.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
                    if (text != null) {
                        val result = when {
                            PACKAGE_JSON_VUE_RE.containsMatchIn(text) &&
                                !PACKAGE_JSON_REACT_RE.containsMatchIn(text) -> Framework.VUE
                            PACKAGE_JSON_REACT_RE.containsMatchIn(text) &&
                                !PACKAGE_JSON_VUE_RE.containsMatchIn(text) -> Framework.REACT
                            else -> null
                        }
                        if (result != null) return result
                    }
                }
                dir = dir.parent ?: return Framework.UNKNOWN
                depth++
            }
            return Framework.UNKNOWN
        }
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
            // PSI 类型检查代替 javaClass.name 字符串匹配
            when (cur) {
                is JSAttributeNameValuePair -> {
                    val attrName = cur.name
                    if (attrName == "style" || (attrName?.isBlank() == true && cur.text.trimStart().startsWith("style"))) {
                        val hasValue = cur.valueNode != null || cur.text.contains('=')
                        if (hasValue) return StyleAttrLoc(cur, StyleAttrLoc.Lang.JSX_TSX, jsxAttribute = cur)
                    }
                }
                is XmlAttribute -> {
                    val n = cur.name
                    if (n == "style" || n == ":style" || n == "v-bind:style") {
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
                                // PSI 类型检查代替 javaClass.name/reflection
                                when (c) {
                                    is XmlAttribute -> {
                                        val n = c.name
                                        if (n == "style" || n == ":style" || n == "v-bind:style")
                                            return@locate StyleAttrLoc(c, StyleAttrLoc.Lang.VUE, xmlAttribute = c)
                                    }
                                    is JSAttributeNameValuePair -> {
                                        if (c.name == "style")
                                            return@locate StyleAttrLoc(c, StyleAttrLoc.Lang.JSX_TSX, jsxAttribute = c)
                                    }
                                }
                                if (c.text.trimStart().startsWith("style"))
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
            // PSI 方式：通过 JSAttributeNameValuePair 接口直接访问 valueNode/values，
            // 不再使用 javaClass.methods reflection
            val attr = loc.jsxAttribute
            if (attr is JSAttributeNameValuePair) {
                // valueNode 返回 ASTNode，通过 .psi 获取 PsiElement
                val valueNode = attr.valueNode
                if (valueNode != null) {
                    val valueElement = valueNode.psi
                    val obj = PsiTreeUtil.findChildOfType(valueElement, JSObjectLiteralExpression::class.java)
                    if (obj != null) return obj.text
                    return unwrapBracesOnce(valueElement.text)
                }
            }
            // Regex 兜底：针对非 JSAttributeNameValuePair 类型的 PSI
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
            val (styleTag, alias) = CssModuleFileResolver.findVueStyleModule(file) ?: return null
            return VueStyleModuleTarget(styleTag, alias)
        }

        // React/TSX：已有 import 指向 module 文件
        CssModuleFileResolver.findExistingModuleImport(file)?.let { (vf, alias) ->
            return FileTarget(vf.path, alias)
        }

        // 兜底：同目录下同名 Xxx.module.* 文件
        CssModuleFileResolver.findSameNameModuleFile(file)?.let { vf ->
            return FileTarget(vf.path, "styles")
        }

        return null
    }

    // ================================================================
    // 把原 style={...} 替换成 class / className
    // （纯 PSI：属性节点整体 replace，由 CssModuleFileResolver 的 dummy-file
    //  工厂创建新节点；仅 PSI 替换失败时才退回 Document 兜底）
    // ================================================================
    private fun replaceStyleAttributeWithClass(
        loc: StyleAttrLoc,
        className: String,
        target: CssModuleTarget
    ) {
        val access = target.classNameAccessExpr(className)
        val project = loc.attrPsi.project
        val file = loc.attrPsi.containingFile ?: return

        // 根据文件扩展名确认语言类型，避免因 PSI 检测错误导致在 React 中生成 Vue 语法
        val ext = file.virtualFile?.extension?.lowercase()
        val effectiveLang = when {
            ext == "vue" -> StyleAttrLoc.Lang.VUE
            ext in listOf("tsx", "jsx", "ts", "js") -> StyleAttrLoc.Lang.JSX_TSX
            else -> loc.sourceLanguage
        }

        when (effectiveLang) {
            StyleAttrLoc.Lang.JSX_TSX -> {
                // React 的 tsx / Vue 的 tsx：按框架探测决定 class vs className
                handleJsxReplacement(project, file, loc.attrPsi, access, detectFramework(file))
            }
            StyleAttrLoc.Lang.VUE -> {
                // .vue 内 <script lang="tsx"> 的 JSX 块：jsxAttribute 命中 → 同样走 Vue JSX 语法
                if (loc.jsxAttribute != null && ext == "vue") {
                    handleJsxReplacement(project, file, loc.attrPsi, access, Framework.VUE)
                    return
                }
                // Vue 模板属性：class 和 :class 可以共存，直接替换 :style 为 :class
                if (!replaceAttrPsi(project, file, loc.attrPsi, ":class=\"$access\"", xml = true)) {
                    replaceAttrViaDocument(project, file, loc.attrPsi, ":class=\"$access\"")
                }
            }
        }
    }

    /**
     * JSX 路径（React 的 tsx / Vue 的 tsx / .vue 内 JSX 块）：
     *  - React → className（React JSX 惯例）；
     *  - Vue → class（Vue JSX 惯例，className 不生效）；
     *  - UNKNOWN → 保守用 className（与旧行为一致）。
     * 已有 class 属性 → 合并（见 [mergeIntoExistingClass]）；没有 → style 属性整体替换。
     */
    internal fun handleJsxReplacement(
        project: Project,
        file: PsiFile,
        styleAttr: PsiElement,
        access: String,
        framework: Framework
    ) {
        val classAttrName = if (framework == Framework.VUE) "class" else "className"
        val existingClass = findClassAttr(styleAttr.parent, classAttrName)
        if (existingClass != null) {
            // 合并到已有 class 属性中，然后删除 style 属性
            mergeIntoExistingClass(existingClass, access, styleAttr, framework)
            return
        }
        val newAttrText = "$classAttrName={$access}"
        if (!replaceAttrPsi(project, file, styleAttr, newAttrText, xml = false)) {
            replaceAttrViaDocument(project, file, styleAttr, newAttrText)
        }
    }

    /**
     * 在父元素中查找已有的 class / className 属性。
     * 双类型兼容：WS-2025.3 的 JSX 属性是 e4x JSXmlAttribute（继承 XmlAttribute），
     * 部分版本是 JSAttributeNameValuePair —— 只搜后者会导致 merge 永不触发、
     * style 被替换成第二个 className（与原有 className 重复，JSX 编译报错）。
     */
    internal fun findClassAttr(parent: PsiElement, attrName: String): PsiElement? {
        return PsiTreeUtil.findChildrenOfType(parent, XmlAttribute::class.java)
            .firstOrNull { it !== parent && it.name == attrName }
            ?: PsiTreeUtil.findChildrenOfType(parent, JSAttributeNameValuePair::class.java)
                .firstOrNull { it !== parent && it.name == attrName }
    }

    /**
     * 将新的 class 访问表达式合并到已有 class 属性中，然后删除 style 属性。
     * 纯 PSI：先整体 replace class 节点，再 delete style 节点及其前置空白。
     * 按框架选择合并策略（避免盲目生成 clsx —— 未安装 clsx 的项目会编译错误）：
     *  - Vue JSX：class={[old, new]}（数组语法，Vue 原生支持，无第三方依赖）；
     *  - React 已 import clsx/classnames：className={clsx(old, new)}；
     *  - React 无 clsx：className={`old ${new}`} 模板字符串（纯字符串字面量直接平铺）。
     */
    internal fun mergeIntoExistingClass(
        existingClass: PsiElement,
        newAccess: String,
        styleAttr: PsiElement,
        framework: Framework
    ) {
        runCatching {
            val existingText = existingClass.text
            val eqIdx = existingText.indexOf('=')
            if (eqIdx < 0) return@runCatching
            var valuePart = existingText.substring(eqIdx + 1).trim()
            // class={expr} → 取 expr 本体，避免生成 {clsx({expr}, ...)} 双层花括号
            if (valuePart.startsWith("{") && valuePart.endsWith("}")) {
                valuePart = valuePart.substring(1, valuePart.length - 1).trim()
            }
            val classAttrName = if (framework == Framework.VUE) "class" else "className"
            val newClassText = when {
                framework == Framework.VUE ->
                    "$classAttrName={[$valuePart, $newAccess]}"
                CLSX_IMPORT_RE.containsMatchIn(existingClass.containingFile?.text.orEmpty()) ->
                    "$classAttrName={clsx($valuePart, $newAccess)}"
                isPlainJsStringLiteral(valuePart) -> {
                    // 字符串字面量（"foo"）直接平铺进模板，避免 ${"foo"} 的丑陋内插
                    val raw = valuePart.substring(1, valuePart.length - 1)
                    "$classAttrName={`$raw \${$newAccess}`}"
                }
                else ->
                    "$classAttrName={\${$valuePart} \${$newAccess}}"
            }
            val project = existingClass.project
            val file = existingClass.containingFile ?: return@runCatching
            val newAttr = CssModuleFileResolver.createJsxAttributePsi(project, file, newClassText)
            if (newAttr != null) {
                existingClass.replace(newAttr)
            }
            // 删除 style 属性及其前置空白（不含换行，保留行结构）
            val prevWs = styleAttr.prevSibling
            if (prevWs is PsiWhiteSpace && !prevWs.textContains('\n')) prevWs.delete()
            styleAttr.delete()
        }.onFailure { LOG.warn("mergeIntoExistingClass failed", it) }
    }

    private fun isPlainJsStringLiteral(s: String): Boolean =
        s.length >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))

    /**
     * 纯 PSI 属性替换：Vue 模板（xml = true）走 XML dummy 工厂；
     * JSX（xml = false）走 JSX dummy 工厂（不能用 attrPsi 类型判断 ——
     * WS-2025.3 的 JSX 属性 JSXmlAttribute 同样继承 XmlAttribute）。
     * 成功返回 true；失败（节点创建失败 / replace 抛异常）返回 false。
     */
    private fun replaceAttrPsi(
        project: Project,
        file: PsiFile,
        attrPsi: PsiElement,
        newAttrText: String,
        xml: Boolean
    ): Boolean {
        return runCatching {
            val newAttr: PsiElement = if (xml) {
                CssModuleFileResolver.createXmlAttributePsi(project, newAttrText)
                    ?: return@runCatching false
            } else {
                CssModuleFileResolver.createJsxAttributePsi(project, file, newAttrText)
                    ?: return@runCatching false
            }
            attrPsi.replace(newAttr)
            true
        }.getOrDefault(false)
    }

    /** Document 兜底替换（仅 PSI 替换失败的极端场景，保持旧行为）。 */
    private fun replaceAttrViaDocument(project: Project, file: PsiFile, attrPsi: PsiElement, newAttrText: String) {
        runCatching {
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val range = attrPsi.textRange
            document.replaceString(range.startOffset, range.endOffset, newAttrText)
        }
    }
}
