package com.pan.dashstyle

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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.css.CssFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.ecmal4.JSAttribute
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeNameValuePair
import com.intellij.lang.css.CSSLanguage
import com.intellij.lang.javascript.JavascriptLanguage
import com.intellij.lang.xml.XMLLanguage
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
        private val MODULE_EXTS = listOf(".module.css", ".module.scss", ".module.sass", ".module.less")
    }

    override fun getText(): String = "Extract inline style to CSS Module..."
    override fun getFamilyName(): String = "DashStyle: Inline Style → CSS Module"

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        val offset = editor.caretModel.offset
        val at = file.findElementAt(offset) ?: return false
        return locateStyleAttribute(at) != null
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val offset = editor.caretModel.offset
        val at = file.findElementAt(offset) ?: return
        val loc = locateStyleAttribute(at) ?: return

        val styleObjText = extractObjectLiteral(loc) ?: run {
            Messages.showWarningDialog(project,
                "Cannot extract: the cursor must be on a plain object style attribute (`style={{...}}` or `:style=\"{...}\"`).",
                "Extract to CSS Module")
            return
        }

        val cssDeclarations = try {
            JsonToCssCopyPastePreProcessor.Util.convertJsonToCss(styleObjText)
        } catch (t: Throwable) {
            LOG.warn("convertJsonToCss failed", t)
            Messages.showErrorDialog(project,
                "Failed to convert style object to CSS: ${t.message}",
                "Extract to CSS Module")
            return
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

    private fun locateStyleAttribute(cursor: PsiElement): StyleAttrLoc? {
        var cur: PsiElement? = cursor
        for (depth in 0..10) {
            if (cur == null) break
            val className = cur.javaClass.name
            when {
                // JSX/TSX 属性：新版 JSX 有多种具体 class（JSAttribute/JSXAttribute/impl.*JsxAttribute），
                // 不直接 is 类型判断，统一用反射式属性名判定，兼容 WebStorm 2025.2+ 的 JSX PSI 结构差异。
                className.contains("JSAttribute", ignoreCase = true) ||
                    className.contains("JSXAttribute", ignoreCase = true) -> {
                    val attrName = runCatching {
                        cur.javaClass.methods.firstOrNull { m ->
                            m.name == "getName" && m.parameterCount == 0
                        }?.invoke(cur) as? String
                    }.getOrNull()
                    if (attrName == "style") {
                        // values.isNotEmpty（存在 value）就直接返回。
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
                    if (n == "style" || n == ":style" || n == "v-bind:style")
                        return StyleAttrLoc(cur, StyleAttrLoc.Lang.VUE, xmlAttribute = cur)
                }
            }
            // 兜底：如果整个 attr 的文本以 style= 开头 → 直接认（防止用户光标落内部 value 节点 Psi）
            val t = cur.text
            if (!t.isNullOrEmpty()) {
                val isJsxLike = t.startsWith("style") && VALID_JSX_STYLE_RE.matches(t.trimIndent().lines().firstOrNull() ?: "")
                if (isJsxLike) return StyleAttrLoc(cur, StyleAttrLoc.Lang.JSX_TSX)
                val isVueLike = VALID_VUE_STYLE_RE.matches(t.trimIndent().lines().firstOrNull() ?: "")
                if (isVueLike) return StyleAttrLoc(cur, StyleAttrLoc.Lang.VUE)
            }
            cur = cur.parent
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
            val styles = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
                .filter { it.name.equals("style", true) }
            val mod = styles.firstOrNull { it.getAttribute("module") != null }
            if (mod != null) {
                val modValue = mod.getAttributeValue("module")
                val alias = if (modValue.isNullOrBlank()) "\$style" else "\$$modValue"
                return VueStyleModuleTarget(mod, alias)
            }
            val any = styles.firstOrNull()
            if (any != null) return VueStyleModuleTarget(any, "\$style")
        }

        // React/TSX：ES6 import 默认绑定 styles from './xxx.module.css|scss|less|sass'
        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        for (imp in imports) {
            val moduleText = imp.importModuleText ?: continue
            val from = moduleText.trim('"', '\'')
            if (MODULE_EXTS.none { from.endsWith(it, ignoreCase = true) }) continue

            // 取默认绑定：优先 named import 之前的默认 import 部分
            val named = imp.namedImports
            val bindings = imp.importedBindings
            val defaultBinding = bindings.firstOrNull { b ->
                named == null || !PsiTreeUtil.isAncestor(named, b, false)
            } ?: bindings.firstOrNull()
            val alias = defaultBinding?.name
                ?: imp.importSpecifiers.firstOrNull()?.name
                ?: "styles"

            val resolvedPsi: PsiFile? = run {
                // 先通过默认绑定解析
                val viaRef = defaultBinding?.reference?.resolve()?.containingFile
                if (viaRef != null) return@run viaRef
                // 按相对路径解析
                val parent = vFile?.parent ?: return@run null
                val normFrom = from.trimStart('/')
                val candidate = findFileByRelativePath_(parent, normFrom)
                    ?: parent.findChild(normFrom.substringAfterLast('/'))
                candidate?.let { PsiManager.getInstance(project).findFile(it) }
            }
            if (resolvedPsi?.virtualFile != null) {
                return FileTarget(resolvedPsi.virtualFile!!.path, alias)
            }
        }

        // 兜底：同目录下有没有 Xxx.module.* 文件（和源文件同名）
        if (vFile != null) {
            val parent = vFile.parent
            val base = vFile.nameWithoutExtension
            if (parent != null) {
                for (suf in MODULE_EXTS) {
                    val c = parent.findChild("$base$suf")
                    if (c != null && c.isValid) return FileTarget(c.path, "styles")
                }
            }
        }
        return null
    }

    private fun findFileByRelativePath_(base: VirtualFile, rel: String): VirtualFile? {
        var cur: VirtualFile? = base
        for (seg in rel.replace('\\', '/').split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") { cur = cur?.parent; continue }
            cur = cur?.findChild(seg) ?: return null
        }
        return cur
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
        val factory = PsiFileFactory.getInstance(project)

        when (loc.sourceLanguage) {
            StyleAttrLoc.Lang.JSX_TSX -> {
                val newAttrText = "className={$access}"
                val snippet = "const _ = () => <div $newAttrText/>"
                val jsLang = Language.findInstance(JavascriptLanguage::class.java)
                val tmp = factory.createFileFromText("__tmp__.tsx", jsLang, snippet)
                // 新版 WebStorm JSX 属性可能不是 JSAttribute，搜索所有"名字 == className"属性 Psi。
                val newAttr = run {
                    val fromJsAttribute = PsiTreeUtil.findChildOfType(tmp, JSAttribute::class.java)
                    fromJsAttribute ?: PsiTreeUtil.collectElements(tmp) {
                        val c = it.javaClass.name
                        (c.contains("JSAttribute", ignoreCase = true) || c.contains("JSXAttribute", ignoreCase = true)) &&
                        runCatching {
                            it.javaClass.methods.firstOrNull { m -> m.name == "getName" && m.parameterCount == 0 }
                                ?.invoke(it) == "className"
                        }.getOrDefault(false)
                    }.firstOrNull()
                }
                if (newAttr != null) loc.attrPsi.replace(newAttr)
                else fallbackReplace(loc.attrPsi, newAttrText)
            }
            StyleAttrLoc.Lang.VUE -> {
                val newAttrText = ":class=\"$access\""
                val snippet = "<template><div $newAttrText/></template>"
                val xmlLang = Language.findInstance(XMLLanguage::class.java)
                val tmp = factory.createFileFromText("__tmp__.vue", xmlLang, snippet)
                val tag = PsiTreeUtil.findChildOfType(tmp, XmlTag::class.java)
                val newAttr = tag?.attributes?.firstOrNull()
                if (newAttr != null) loc.attrPsi.replace(newAttr)
                else fallbackReplace(loc.attrPsi, newAttrText)
            }
        }
    }

    /** PSI 替换失败时的最后兜底：按照 attr 在 parent 中的文本区间替换 */
    private fun fallbackReplace(attrPsi: PsiElement, newAttr: String) {
        try {
            val parent = attrPsi.parent ?: return
            val range = attrPsi.textRangeInParent
            if (range.length <= 0) return
            val parentNewText = parent.text.replaceRange(
                IntRange(range.startOffset, range.endOffset - 1), newAttr
            )
            // 用新文本临时生成 psi，替换 parent
            val factory = PsiFileFactory.getInstance(attrPsi.project)
            val lang = parent.containingFile?.language ?: PlainTextLanguage.INSTANCE
            val isVue = parent.containingFile?.virtualFile?.extension?.lowercase() == "vue"
            val tmp = factory.createFileFromText(
                "__fallback__.txt", lang,
                if (isVue) "<div $parentNewText/>" else "const _ = () => <div $parentNewText/>"
            )
            val attrJavaClass = attrPsi.javaClass.name
            val targetReplacement = when {
                attrPsi is JSAttribute || attrJavaClass.let {
                    it.contains("JSAttribute", ignoreCase = true) || it.contains("JSXAttribute", ignoreCase = true)
                } -> {
                    PsiTreeUtil.findChildOfType(tmp, JSAttribute::class.java)
                        ?: PsiTreeUtil.collectElements(tmp) {
                            val c = it.javaClass.name
                            (c.contains("JSAttribute", ignoreCase = true) || c.contains("JSXAttribute", ignoreCase = true)) &&
                                runCatching {
                                    it.javaClass.methods.firstOrNull { m -> m.name == "getName" && m.parameterCount == 0 }
                                        ?.invoke(it)
                                }.getOrNull() == "className"
                        }.firstOrNull()
                }
                attrPsi is XmlAttribute ->
                    PsiTreeUtil.findChildOfType(tmp, XmlTag::class.java)?.attributes?.firstOrNull()
                else -> null
            }
            if (targetReplacement != null) attrPsi.replace(targetReplacement)
        } catch (t: Throwable) {
            LOG.warn("fallbackReplace failed", t)
        }
    }
}
