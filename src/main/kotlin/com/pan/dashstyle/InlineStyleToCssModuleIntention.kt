package com.pan.dashstyle

import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.icons.AllIcons
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
import com.intellij.psi.css.CssStylesheet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.diagnostic.Logger
import javax.swing.Icon

/**
 * 核心快速修复：将 JSX/Vue 中的 `style={{ key: value }}` 或 Vue `:style="{ ... }"`
 * 内联对象 → 提取为 CSS Module 中的 class，支持：
 *   - 语义推断默认类名 + 输入框可重命名
 *   - 自动定位目标 CSS Module（Vue <style module> 或 React TSX 的 *.module.css import）
 *   - 生成标准 kebab-case class 并追加到 CSS 末尾
 *   - 原位置改为 `className={styles.xxx}` / `:class="$style.xxx"` / `:class="[$style.xxx]"`
 */
class InlineStyleToCssModuleIntention : BaseIntentionAction() {

    companion object {
        private val LOG = Logger.getInstance(InlineStyleToCssModuleIntention::class.java)
        private val VALID_JSX_STYLE_RE = Regex("""^\s*style\s*=\s*""")
        private val VALID_VUE_STYLE_RE = Regex("""^\s*(?:v-bind:)?style\s*=\s*""")
        // CSS 类名规则 (kebab-case + camelCase 都允许，但默认 kebab)
        private val CLASS_NAME_RE = Regex("""^[_a-zA-Z][_a-zA-Z0-9-]*$""")
    }

    override fun getText(): String = "Extract inline style to CSS Module (Rename)"
    override fun getFamilyName(): String = "DashStyle: Inline Style → CSS Module"
    override fun getIcon(element: PsiElement?): Icon = AllIcons.Actions.RefactoringBulb

    // ================================================================
    // 可用性探测：光标必须在 style={...} / :style="..." 属性上
    // ================================================================
    override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
        return locateStyleAttribute(element) != null
    }

    private fun locateStyleAttribute(cursor: PsiElement): StyleAttrLoc? {
        var cur: PsiElement? = cursor
        for (depth in 0..6) {
            if (cur == null) break
            val text = cur.text
            // JSX/TSX: JSXAttribute 节点，name==style
            if (cur is JSAttribute) {
                if (cur.name == "style" && cur.value != null) {
                    return StyleAttrLoc(
                        attrPsi = cur, attributeText = text,
                        sourceLanguage = StyleAttrLoc.Lang.JSX_TSX,
                        attributeElement = cur
                    )
                }
            }
            // Vue template: XmlAttribute :style / v-bind:style / style
            if (cur is XmlAttribute) {
                val attrName = cur.name
                if (attrName == "style" || attrName == ":style" || attrName == "v-bind:style") {
                    return StyleAttrLoc(
                        attrPsi = cur, attributeText = text,
                        sourceLanguage = StyleAttrLoc.Lang.VUE,
                        attributeElement = cur
                    )
                }
            }
            // 退而求其次：文本层面探测（用于 PSI 树不完整时）
            if (text != null && text.length in 6..20) {
                if (VALID_JSX_STYLE_RE.matches(text)) {
                    return StyleAttrLoc(
                        attrPsi = cur, attributeText = text,
                        sourceLanguage = StyleAttrLoc.Lang.JSX_TSX,
                        attributeElement = cur
                    )
                }
                if (VALID_VUE_STYLE_RE.matches(text)) {
                    return StyleAttrLoc(
                        attrPsi = cur, attributeText = text,
                        sourceLanguage = StyleAttrLoc.Lang.VUE,
                        attributeElement = cur
                    )
                }
            }
            cur = cur.parent
        }
        return null
    }

    data class StyleAttrLoc(
        val attrPsi: PsiElement,
        val attributeText: String,
        val sourceLanguage: Lang,
        val attributeElement: PsiElement
    ) {
        enum class Lang { JSX_TSX, VUE }
    }

    // ================================================================
    // 主执行：推断 + 重命名对话框 + 提取 + 写回
    // ================================================================
    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        val loc = locateStyleAttribute(element) ?: return

        val styleObjText = extractObjectLiteral(loc) ?: run {
            Messages.showWarningDialog(project,
                "Cannot extract style: attribute value isn't a plain object literal `{...}`.",
                "Extract to CSS Module")
            return
        }

        // (1) 转 CSS 声明块 (复用 JsonToCssCopyPastePreProcessor 的核心逻辑)
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

        // (2) 推断候选类名 + 对话框
        val candidates = SemanticClassNameInferrer.inferCandidates(
            styleAttrElement = loc.attrPsi,
            cssDeclarations = cssDeclarations,
            contextFileElement = element
        )
        val defaultName = SemanticClassNameInferrer.topCandidate(candidates)

        val hint = candidates.take(5).joinToString(", ") { c ->
            "${c.name} (${c.score} from ${c.source})"
        }
        val chosenName = askRenameDialog(project, defaultName, hint) ?: return

        // (3) 定位目标 CSS 容器 (文件或 Vue <style module> 段)
        val target = locateTargetCssModule(project, loc) ?: run {
            Messages.showErrorDialog(project,
                "Cannot find target CSS Module location.\n" +
                        "For .vue files, ensure `<style module>` or `<style module=\"style\">` exists.\n" +
                        "For React/TSX, add `import styles from './Xxx.module.css'` in the same file.",
                "Extract to CSS Module")
            return
        }

        // (4) 双写回：在目标 CSS 末尾追加 `.classname { cssDeclarations }`，
        //     然后把原 style={...} 属性替换为对应的 className/class 绑定。
        WriteCommandAction.writeCommandAction(project)
            .withName("Extract inline style to CSS Module")
            .run<Nothing> {
                target.appendClass(project, chosenName, cssDeclarations)
                replaceStyleAttributeWithClass(project, loc, chosenName, target.bindSyntax(loc.sourceLanguage))
            }

        // (5) 打开/聚焦目标文件 (仅针对真实 css/scss/less 文件，Vue 内部的 style 不跳转)
        if (target is FileTarget) {
            val vf = LocalFileSystem.getInstance().findFileByPath(target.absolutePath)
            if (vf != null && vf.isValid) {
                ApplicationManager.getApplication().invokeLater {
                    val fd = OpenFileDescriptor(project, vf, Math.max(0, target.approximateNewLines - 1), 0)
                    FileEditorManager.getInstance(project).openTextEditor(fd, true)
                }
            }
        }

        Messages.showInfoMessage(
            project,
            "Extracted `.${chosenName}` with ${cssDeclarations.lineSequence().filter { it.contains(':') }.count()} declarations:\n\n${cssDeclarations.trim()}",
            "Extract to CSS Module OK"
        )
    }

    // ================================================================
    // 步骤 1: 提取 style 属性的对象字面量文本（去 JSXExpressionContainer / Vue 字符串壳）
    // ================================================================
    private fun extractObjectLiteral(loc: StyleAttrLoc): String? {
        val attrEl = loc.attributeElement
        return when (attrEl) {
            is JSAttribute -> {
                val v = attrEl.value ?: return null
                // JSX: style={ {...} } —— 外层是 JSXExpressionContainer
                val exprContainer = PsiTreeUtil.findChildOfType(v, JSExpression::class.java)
                    ?: return null
                val inner = if (exprContainer is JSObjectLiteralExpression) exprContainer.text
                else PsiTreeUtil.findChildOfType(exprContainer, JSObjectLiteralExpression::class.java)?.text
                inner
            }
            is XmlAttribute -> {
                // Vue: :style="{ color:'red' }" 或 :style="{...}" —— 值为字符串或表达式
                val v = attrEl.valueElement ?: return null
                val raw = v.value ?: v.text?.trim('"', '\'')
                if (raw != null && raw.startsWith('{') && raw.endsWith('}')) raw else null
            }
            else -> {
                // Fallback：基于文本的正则提取
                val t = loc.attributeText
                val m = Regex("""\s*=\s*(\{[\s\S]*\})\s*$""").find(t)
                if (m != null) {
                    var s = m.groupValues[1]
                    // JSX style={{ ... }}，外面还有一层 {}
                    if (s.startsWith("{") && s.endsWith("}")) {
                        val inner = s.drop(1).dropLast(1)
                        val trimmed = inner.trim()
                        if (trimmed.startsWith("{")) return trimmed
                    }
                    return s
                } else null
            }
        }
    }

    // ================================================================
    // 步骤 2: 类名重命名输入框（Rename 风格 + 校验器 + 候选提示）
    // ================================================================
    private fun askRenameDialog(project: Project, default: String, hint: String): String? {
        return Messages.showInputDialog(
            project,
            "Choose a CSS class name (kebab-case recommended).\n\nSemantic suggestions by score:\n$hint",
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
                            "Invalid class name (use letters, digits, `-` or `_`, must start with letter/_)."
                        else -> null
                    }
            }
        )
    }

    // ================================================================
    // 步骤 3: 定位目标 CSS Module 位置
    // ================================================================
    sealed class CssModuleTarget {
        abstract fun appendClass(project: Project, className: String, declarations: String)
        abstract fun bindSyntax(sourceLang: StyleAttrLoc.Lang): String
    }

    data class FileTarget(
        val absolutePath: String,
        val importVariableName: String = "styles",
        var approximateNewLines: Int = 0
    ) : CssModuleTarget() {
        override fun appendClass(project: Project, className: String, declarations: String) {
            val vf = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return
            val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return
            appendClassToPsi(project, psiFile, className, declarations, alsoUpdate = { lines ->
                approximateNewLines = lines
            })
        }
        override fun bindSyntax(sourceLang: StyleAttrLoc.Lang): String =
            if (sourceLang == StyleAttrLoc.Lang.VUE) "$importVariableName.$className"
            else importVariableName
    }

    data class VueStyleModuleTarget(
        val styleTag: XmlTag,
        val styleVariableName: String = "\$style"  // `module="style"` → \$style，`module=""` → 其他
    ) : CssModuleTarget() {
        override fun appendClass(project: Project, className: String, declarations: String) {
            // 往 styleTag 的末尾（最后一个 XmlText 内）追加样式块
            val children = styleTag.children
            val anchor: PsiElement? = children.lastOrNull { it !is PsiWhiteSpace && it !is PsiComment }
            val stylePsiFile = styleTag.containingFile
            // 如果有 VueEmbedded CSS 文件，使用 CssStylesheet 来保证格式正确
            val embedded = findEmbeddedCssInStyleTag(styleTag)
            if (embedded != null) {
                appendClassToPsi(project, embedded, className, declarations, alsoUpdate = {})
            } else {
                // fallback: 在 style 的 XmlText 中插入
                val rawTag = styleTag
                val insertPoint = anchor ?: rawTag
                val snippet = "\n.${className} {\n${indentDeclarations(declarations, "  ")}}\n"
                val factory = PsiFileFactory.getInstance(project)
                val snippetPsi = factory.createFileFromText(
                    "__tmp__.css",
                    com.intellij.css.CssLanguage.INSTANCE, snippet
                )
                rawTag.addBefore(snippetPsi.firstChild, null)
            }
        }
        override fun bindSyntax(sourceLang: StyleAttrLoc.Lang): String = styleVariableName
    }

    private fun locateTargetCssModule(project: Project, loc: StyleAttrLoc): CssModuleTarget? {
        val srcFile = loc.attrPsi.containingFile
        val vFile = srcFile.virtualFile
        val ext = vFile?.extension?.lowercase()

        // Vue: 同文件中寻找 <style module> / <style module="...">
        if (ext == "vue") {
            val styleTags = PsiTreeUtil.findChildrenOfType(srcFile, XmlTag::class.java)
                .filter { it.name.equals("style", true) }
            // 优先 module 不为空的
            val withModule = styleTags.firstOrNull { it.getAttribute("module") != null }
            if (withModule != null) {
                val modName = withModule.getAttributeValue("module")
                val alias = if (modName.isNullOrBlank()) "\$style" else "\$$modName"
                return VueStyleModuleTarget(withModule, alias)
            }
            // 退化：任意 style (即使没 module 也可作为本地类)
            val anyStyle = styleTags.firstOrNull()
            if (anyStyle != null) return VueStyleModuleTarget(anyStyle, "\$style")
        }

        // React / TSX / JSX: 找 import styles from './Xxx.module.css' (或 .scss / .less)
        val moduleExts = listOf(".module.css", ".module.scss", ".module.sass", ".module.less")
        val imports = PsiTreeUtil.findChildrenOfType(srcFile, ES6ImportDeclaration::class.java)
        for (imp in imports) {
            val from = imp.from?.text?.trim('"', '\'') ?: continue
            if (moduleExts.none { from.endsWith(it, ignoreCase = true) }) continue
            // imp 的第一个 ImportSpecifier / defaultImport
            val alias = imp.importSpecifiers.firstOrNull()?.name
                ?: imp.firstChild?.children?.firstOrNull { it is JSVariable }?.text
                ?: imp.text.split(Regex("\\s+")).getOrNull(1)?.takeIf { it.firstOrNull()?.isLetter() == true }
                ?: "styles"
            val resolved = imp.importDeclarations.firstOrNull()?.reference?.resolve()?.containingFile
                ?: run {
                    // 按相对路径从源文件目录出发找
                    val parentDir = vFile?.parent ?: return@run null
                    val childPath = (parentDir.path + "/" + from).removePrefix("./").removePrefix(parentDir.path + "/../")
                    LocalFileSystem.getInstance().findFileByPath(
                        parentDir.path + "/" + from.trimStart('/')
                    )?.let { PsiManager.getInstance(project).findFile(it) }
                }
            if (resolved != null && resolved.virtualFile != null) {
                return FileTarget(resolved.virtualFile.path, alias)
            }
        }
        // 兜底：如果同目录有同名 Xxx.module.css，直接用
        if (vFile != null) {
            val parentDir = vFile.parent
            val base = vFile.nameWithoutExtension
            for (ext in moduleExts) {
                val candidate = parentDir?.findChild("$base$ext")
                if (candidate != null && candidate.isValid) {
                    return FileTarget(candidate.path, "styles")
                }
            }
        }
        return null
    }

    private fun findEmbeddedCssInStyleTag(styleTag: XmlTag): PsiFile? {
        // 向下一层一层找，Vue 通常把 CSS 包成 VirtualFile，父在 XmlTag 内部
        fun walk(el: PsiElement): PsiFile? {
            if (el is PsiFile && (el.fileType.defaultExtension == "css" ||
                        el is CssFile || el is CssStylesheet)) return el
            for (c in el.children) {
                val r = walk(c); if (r != null) return r
            }
            return null
        }
        return walk(styleTag)
    }

    // ================================================================
    // 步骤 4: 追加类到目标 PSI 文件 (纯 WriteAction)
    // ================================================================
    private fun appendClassToPsi(
        project: Project,
        targetPsi: PsiElement,
        className: String,
        declarations: String,
        alsoUpdate: (Int) -> Unit
    ) {
        val factory = PsiFileFactory.getInstance(project)
        val indented = indentDeclarations(declarations, "  ")
        val ruleText = "\n.${className} {\n${indented}}\n"
        // 用 CSS 语言来 parse，避免作为普通文本插错位置
        val lang = (targetPsi as? PsiFile)?.language
            ?: com.intellij.css.CssLanguage.INSTANCE
        val tmpPsi = try {
            factory.createFileFromText("__dashstyle_tmp__.css", lang, ruleText)
        } catch (_: Throwable) {
            factory.createFileFromText("__dashstyle_tmp__.css", PlainTextLanguage.INSTANCE, ruleText)
        }
        // 在目标尾部（最后一个非空规则或最后一个子节点）之后插入
        val leaf = targetPsi.lastChild
        if (leaf != null) targetPsi.addAfter(tmpPsi.firstChild, leaf)
        else targetPsi.add(tmpPsi.firstChild)
        alsoUpdate(ruleText.lineSequence().count())
    }

    private fun indentDeclarations(css: String, indent: String): String {
        return css.lineSequence().mapNotNull { line ->
            val t = line.trim()
            if (t.isBlank()) null else "$indent$t"
        }.joinToString("\n", postfix = "\n")
    }

    // ================================================================
    // 步骤 5: 把原 style 属性替换为 class/className 绑定
    // ================================================================
    private fun replaceStyleAttributeWithClass(
        project: Project,
        loc: StyleAttrLoc,
        className: String,
        bindVar: String
    ) {
        val attrEl = loc.attributeElement
        val file = attrEl.containingFile
        val factory = PsiFileFactory.getInstance(project)
        when (loc.sourceLanguage) {
            StyleAttrLoc.Lang.JSX_TSX -> {
                // className={styles.Xxx} 或 camelCase className={styles.fooBar}（变量用驼峰，CSS kebab 通过 []）
                val kebabIsSimple = CLASS_NAME_RE.matches(className) && !className.contains('-')
                val access = if (kebabIsSimple) "$bindVar.${className}" else "$bindVar[\"${className}\"]"
                val newAttr = "className={${access}}"
                // JSAttribute 直接操作：改 name + value
                if (attrEl is JSAttribute) {
                    // 通过 JSX file 生成 JSAttribute
                    val tmp = factory.createFileFromText(
                        "__tmp__.tsx", com.intellij.lang.javascript.JavascriptLanguage.INSTANCE,
                        "const _ = () => <div $newAttr/>"
                    )
                    val newAttrPsi = PsiTreeUtil.findChildOfType(tmp, JSAttribute::class.java)!!
                    attrEl.replace(newAttrPsi)
                    return
                }
                attrEl.replaceViaText(file, factory, newAttr, "jsx")
            }
            StyleAttrLoc.Lang.VUE -> {
                // Vue: class="xxx" 非 module，module 需写 :class="$style.xxx" / :class="[$style.xxx, ...]"
                val access = if (!className.contains('-')) "$bindVar.$className"
                else "$bindVar['$className']"
                val newAttr = ":class=\"$access\""
                if (attrEl is XmlAttribute) {
                    val tmpTag = "<div $newAttr/>"
                    val tmp = factory.createFileFromText(
                        "__tmp__.vue", com.intellij.lang.xml.XMLLanguage.INSTANCE, tmpTag
                    )
                    val newPsi = PsiTreeUtil.findChildOfType(tmp, XmlTag::class.java)?.attributes?.first()!!
                    attrEl.replace(newPsi)
                    return
                }
                attrEl.replaceViaText(file, factory, newAttr, "xml")
            }
        }
    }

    /** 对于 PSI 类型不可靠的情况，文本级替换后重新解析 */
    private fun PsiElement.replaceViaText(
        file: PsiFile,
        factory: PsiFileFactory,
        newAttrText: String,
        kind: String
    ) {
        try {
            val oldRange = this.textRangeInParent
            val parent = this.parent
            val parentText = parent.text
            val replacement = parentText.replaceRange(oldRange.startOffset, oldRange.endOffset, newAttrText)
            val lang = if (kind == "xml") com.intellij.lang.xml.XMLLanguage.INSTANCE
            else com.intellij.lang.javascript.JavascriptLanguage.INSTANCE
            val wrap = if (kind == "xml") "<div $replacement/>" else "const _ = () => <div $replacement/>"
            val tmp = factory.createFileFromText("__dashstyle_attr__.$kind", lang, wrap)
            val target = if (kind == "xml") PsiTreeUtil.findChildOfType(tmp, XmlTag::class.java)?.attributes?.first()
            else PsiTreeUtil.findChildOfType(tmp, JSAttribute::class.java)
            if (target != null) this.replace(target)
        } catch (t: Throwable) {
            LOG.warn("replaceViaText fallback failed", t)
        }
    }
}
