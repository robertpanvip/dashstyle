package com.pan.dashstyle.action

import com.pan.dashstyle.DashStyleBundle.message
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
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.css.CSSLanguage
import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

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

        // 框架标志性 API 证据（非 import 语句，PSI 化成本高收益低，仅作次级文本证据）
        private val VUE_API_RE = Regex("""\bdefineComponent\s*\(|\buseCssModule\s*\(""")
        private val REACT_API_RE = Regex("""\bReact\.Component\b""")
        private val PACKAGE_JSON_VUE_RE = Regex(""""vue"\s*:""")
        private val PACKAGE_JSON_REACT_RE = Regex(""""react"\s*:""")

        /** 探测结果 + 命中的 package.json（用作缓存依赖，内容修改会失效缓存）。 */
        private data class FrameworkDetection(val framework: Framework, val pkgPsi: PsiFile?)

        /**
         * 框架探测（区分 Vue 的 TSX 和 React 的 TSX），带 CachedValue 缓存：
         * Alt+Enter 的 isAvailable 每次光标移动都会触发探测，不缓存的话
         * 每次都要做最多 20 层 package.json 磁盘 IO。
         * 缓存依赖：项目级 PSI 修改 tracker + 命中的 package.json PsiFile
         * （两者任一变化 → 失效重算）。
         */
        internal fun detectFramework(file: PsiFile): Framework =
            CachedValuesManager.getCachedValue(file) {
                val detection = detectFrameworkUncached(file)
                val deps: MutableList<Any> = mutableListOf(PsiModificationTracker.getInstance(file.project))
                if (detection.pkgPsi != null) deps.add(detection.pkgPsi)
                CachedValueProvider.Result.create(detection.framework, deps)
            }

        private fun detectFrameworkUncached(file: PsiFile): FrameworkDetection {
            if (file.virtualFile?.extension?.lowercase() == "vue") return FrameworkDetection(Framework.VUE, null)

            // 1. PSI 遍历 import 声明（结构化判断，不做全文正则 containsMatchIn）。
            //    vue 优先：混用 react 类型工具的场景以组件框架为准。
            var hasVueImport = false
            var hasReactImport = false
            for (imp in PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)) {
                when (frameworkOfImportedModule(importedModuleName(imp))) {
                    Framework.VUE -> hasVueImport = true
                    Framework.REACT -> hasReactImport = true
                    else -> {}
                }
            }
            if (hasVueImport) return FrameworkDetection(Framework.VUE, null)
            if (hasReactImport) return FrameworkDetection(Framework.REACT, null)

            // 2. 框架标志性 API 证据（次级）
            val text = file.text
            if (VUE_API_RE.containsMatchIn(text)) return FrameworkDetection(Framework.VUE, null)
            if (REACT_API_RE.containsMatchIn(text)) return FrameworkDetection(Framework.REACT, null)

            // 3. package.json 兜底
            return detectFrameworkFromPackageJson(file)
        }

        /** 取 import 声明的模块名（PSI API 优先；兼容 importModuleText 不返回时回退文本解析）。 */
        private fun importedModuleName(imp: ES6ImportDeclaration): String? =
            imp.importModuleText?.trim('"', '\'')
                ?: CssModuleFileResolver.extractModulePathFromText(imp.text)

        /** 精确模块名匹配（scoped 包用前缀判断，其余全等比较，不用 contains）。 */
        private fun frameworkOfImportedModule(module: String?): Framework? = when (module) {
            "vue", "vue-router", "pinia", "vuex" -> Framework.VUE
            "react", "react-dom", "react-router", "react-router-dom" -> Framework.REACT
            else -> when {
                module != null && module.startsWith("@vue/") -> Framework.VUE
                module != null && module.startsWith("next/") -> Framework.REACT
                else -> null
            }
        }

        /**
         * 从文件目录向上找 package.json（最多 20 层）。
         * 经 PsiManager 读取（而非 VirtualFile 字节流）：读到的 PsiFile 会作为
         * CachedValue 依赖，package.json 内容修改可正确失效缓存。
         * 当前 package.json 两者都没有（或都有，多为 monorepo 根）→ 继续向上。
         */
        private fun detectFrameworkFromPackageJson(file: PsiFile): FrameworkDetection {
            var dir = file.virtualFile?.parent ?: return FrameworkDetection(Framework.UNKNOWN, null)
            var depth = 0
            while (depth < 20) {
                val pkgVf = dir.findChild("package.json")
                if (pkgVf != null && pkgVf.isValid && !pkgVf.isDirectory) {
                    val pkgPsi = PsiManager.getInstance(file.project).findFile(pkgVf)
                    if (pkgPsi != null) {
                        val text = pkgPsi.text
                        val hasVue = PACKAGE_JSON_VUE_RE.containsMatchIn(text)
                        val hasReact = PACKAGE_JSON_REACT_RE.containsMatchIn(text)
                        val result = when {
                            hasVue && !hasReact -> Framework.VUE
                            hasReact && !hasVue -> Framework.REACT
                            else -> null
                        }
                        if (result != null) return FrameworkDetection(result, pkgPsi)
                    }
                }
                dir = dir.parent ?: return FrameworkDetection(Framework.UNKNOWN, null)
                depth++
            }
            return FrameworkDetection(Framework.UNKNOWN, null)
        }

        /** 提供 clsx 语义的包（默认/命名导出均可直接调用）。 */
        private val CLSX_MODULES = setOf("clsx", "classnames")

        /**
         * clsx 可用性 = 能解析出可直接调用的本地绑定名。
         * （仅模块被 import 不够 —— 副作用导入 `import 'clsx'` / namespace 导入
         * `import * as x` 都不能生成 `x(...)` 调用。）
         */
        internal fun hasClsxImport(file: PsiFile): Boolean = clsxLocalName(file) != null

        /**
         * PSI 解析 clsx/classnames 的「本地调用名」：
         *  - `import cn from 'clsx'` → cn（不能硬编码 clsx，否则 cn 场景生成未定义标识符）
         *  - `import classNames from 'classnames'` → classNames
         *  - `import { clsx } from 'clsx'` / `import { clsx as c } from 'clsx'` → clsx / c
         * 优先级：名为 clsx 的命名导入 > 默认导入 > 其它命名导入；
         * namespace（* as）与副作用导入返回 null（调用方回退模板字符串策略）。
         */
        internal fun clsxLocalName(file: PsiFile): String? {
            for (imp in PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)) {
                if (importedModuleName(imp) !in CLSX_MODULES) continue
                val named = imp.importSpecifiers.mapNotNull { specifierLocalName(it) }
                    .filter { it.isNotBlank() }
                named.firstOrNull { it == "clsx" }?.let { return it }
                imp.importedBindings.firstOrNull { !it.isNamespaceImport }
                    ?.name?.takeIf { it.isNotBlank() }?.let { return it }
                named.firstOrNull()?.let { return it }
            }
            return null
        }

        /** 命名导入本地名：`{ clsx as c }` 的名字在 alias 节点上（specifier.name 为 null），优先取 alias。 */
        private fun specifierLocalName(spec: ES6ImportSpecifier): String? =
            spec.alias?.name?.takeIf { it.isNotBlank() } ?: spec.name
    }

    override fun getText(): String = message("intention.extract.inline.style.text")
    override fun getFamilyName(): String = message("intention.extract.inline.style.family")

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
                message("intention.extract.no.style.attribute.warning"),
                message("intention.extract.dialog.title")); return }

        val styleObjText = extractObjectLiteral(loc) ?: run {
            Messages.showWarningDialog(project,
                message("intention.extract.not.object.warning"),
                message("intention.extract.dialog.title"))
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
                    message("intention.extract.convert.failed.error", t.message ?: ""),
                    message("intention.extract.dialog.title"))
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
        val suggestions = candidates.take(5).map { it.name }.toTypedArray()

        val chosenName = askRenameDialog(suggestions, defaultName) ?: return

        val target = locateTargetCssModule(project, file, loc) ?: run {
            Messages.showErrorDialog(project,
                message("intention.extract.no.target.error"),
                message("intention.extract.dialog.title"))
            return
        }

        WriteCommandAction.writeCommandAction(project)
            .withName(message("command.extract.inline.style"))
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
            message("intention.extract.success.message", chosenName, declCount, target.toString(), cssDeclarations.trim()),
            message("intention.extract.success.title")
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
    // Rename 对话框：可编辑下拉框 —— 候选名作为下拉项，默认填 Top 候选，
    // 也允许直接输入自定义类名（校验规则不变）。
    // ================================================================
    private fun askRenameDialog(suggestions: Array<String>, default: String): String? {
        return Messages.showEditableChooseDialog(
            message("intention.extract.rename.dialog.message"),
            message("intention.extract.rename.dialog.title"),
            Messages.getQuestionIcon(),
            suggestions,
            default,
            object : InputValidatorEx {
                override fun checkInput(input: String): Boolean = canClose(input)
                override fun canClose(input: String?): Boolean =
                    !input.isNullOrBlank() && CLASS_NAME_RE.matches(input)
                override fun getErrorText(input: String?): String? =
                    when {
                        input.isNullOrBlank() -> message("intention.extract.class.name.empty.error")
                        !CLASS_NAME_RE.matches(input) -> message("intention.extract.class.name.invalid.error")
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
        override fun toString(): String = message("target.file.description", absolutePath, importVariableName)
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
        override fun toString(): String = message("target.vue.style.module.description", styleVar)
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
                // Vue 模板属性：先处理已有 :class 的合并（避免重复 :class）
                handleVueTemplateReplacement(project, file, loc.attrPsi, access)
            }
        }
    }

    /**
     * Vue 模板（XmlAttribute）路径：`:style` → `:class`。
     *  - 已有 `:class`/`v-bind:class` → **必须合并**：直接替换会产生第二个 `:class`，
     *    Vue 编译器报 Duplicate attribute；合并为 `:class="[old, $style.x]"`
     *    （Vue 数组绑定原生接受 字符串/对象/数组 混排并递归展平）。
     *  - 只有静态 `class="foo"` → 生成新 `:class` 与之共存（Vue 自动合并静态+动态 class）。
     */
    internal fun handleVueTemplateReplacement(
        project: Project,
        file: PsiFile,
        styleAttr: PsiElement,
        access: String
    ): Boolean {
        val tag = styleAttr.parent
        val existingBind = tag?.children
            ?.asSequence()
            ?.filterIsInstance<XmlAttribute>()
            ?.firstOrNull { it.name == ":class" || it.name == "v-bind:class" }
        if (existingBind != null && mergeVueTemplateClass(existingBind, access)) {
            deleteAttrWithLeadingWs(styleAttr)
            return true
        }
        val newAttrText = ":class=\"$access\""
        return if (replaceAttrPsi(project, file, styleAttr, newAttrText, xml = true)) {
            true
        } else {
            replaceAttrViaDocument(project, file, styleAttr, newAttrText)
            false
        }
    }

    /**
     * Vue 模板 `:class` 值合并：
     *  - `dyn` / `{active: x}` 等任意表达式 → `[{...}, $style.x]`；
     *  - 已是 `[a, b]` 数组 → 平铺 `[a, b, $style.x]`；
     *  - 空值 → 直接 `$style.x`。
     * 纯 PSI：Xml 工厂造新节点整体 replace，成功返回 true。
     */
    private fun mergeVueTemplateClass(existing: XmlAttribute, access: String): Boolean {
        val value = existing.value
            ?: existing.valueElement?.text?.trim('"', '\'')
            ?: return false
        val newValue = when {
            value.isBlank() -> access
            isWrappedArrayLiteral(value) -> {
                val inner = value.substring(1, value.length - 1).trim()
                if (inner.isEmpty()) access else "[$inner, $access]"
            }
            else -> "[$value, $access]"
        }
        val newAttr = CssModuleFileResolver.createXmlAttributePsi(
            existing.project, ":class=\"$newValue\""
        ) ?: return false
        return runCatching { existing.replace(newAttr); true }.getOrDefault(false)
    }

    /**
     * JSX 路径（React 的 tsx / Vue 的 tsx / .vue 内 JSX 块）：
     *  - React → className（React JSX 惯例）；
     *  - Vue → class（Vue JSX 惯例，className 不生效）；
     *  - UNKNOWN → 保守用 className（与旧行为一致）。
     * 已有 class 属性 → 合并（见 [mergeIntoExistingClass]）；没有（或合并失败，
     * 例如属性节点创建异常）→ style 属性整体替换，保证不丢样式信息。
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
        if (existingClass != null && mergeIntoExistingClass(existingClass, access, styleAttr, framework)) {
            return
        }
        val newAttrText = "$classAttrName={$access}"
        if (!replaceAttrPsi(project, file, styleAttr, newAttrText, xml = false)) {
            replaceAttrViaDocument(project, file, styleAttr, newAttrText)
        }
    }

    /**
     * 在同一标签的兄弟属性中查找已有的 class / className 属性。
     * 只扫 [parent] 的**直接属性子节点**（JSX e4x 的 JSAttributeList / Vue 的 XmlTag
     * 均把属性作为直接子级），不递归后代 —— 递归会把子元素或属性值内嵌 JSX 的
     * className 误当兄弟，把新 class 合并进错误的元素。
     * 双类型兼容：WS-2025.3 的 JSX 属性是 e4x JSXmlAttribute（继承 XmlAttribute），
     * 部分版本是 JSAttributeNameValuePair。
     */
    internal fun findClassAttr(parent: PsiElement, attrName: String): PsiElement? {
        val direct = parent.children.asSequence()
            .filter { it is XmlAttribute || it is JSAttributeNameValuePair }
        direct.firstOrNull { attributeNameOf(it) == attrName }?.let { return it }
        return null
    }

    private fun attributeNameOf(el: PsiElement): String? = when (el) {
        is XmlAttribute -> el.name
        is JSAttributeNameValuePair -> el.name
        else -> null
    }

    /**
     * 将新的 class 访问表达式合并到已有 class 属性中，然后删除 style 属性。
     * 纯 PSI：先整体 replace class 节点，再 delete style 节点及其前置空白。
     * 按框架与已有值形态选择合并策略（避免盲目生成 clsx —— 未安装/别名导入的项目会编译错误）：
     *  - Vue JSX：class={[old, new]}（数组语法，Vue 原生支持，无第三方依赖）；
     *    已是数组字面量则平铺为 [a, b, new]，避免 [[a, b], new] 双层嵌套。
     *  - React 已 import clsx/classnames：className={本地名(old, new)}；
     *    已是同名调用则平铺参数 local(a, b, new)，避免 clsx(clsx(...)) 嵌套。
     *  - React 无 clsx 且旧值是纯字符串字面量 → 平铺进模板 `` `foo ${new}` ``；
     *    旧值已是模板字面量 → 在其内部追加，避免 `` `${`...`}` `` 嵌套；
     *    其余动态表达式（三元/拼接/函数调用）→ 模板字符串包裹 `` `${old} ${new}` ``。
     * 返回是否成功；失败（节点创建/替换异常）时**不删除 style 属性**，
     * 由调用方 [handleJsxReplacement] 回退为整体替换，保证原样式信息不丢失。
     */
    internal fun mergeIntoExistingClass(
        existingClass: PsiElement,
        newAccess: String,
        styleAttr: PsiElement,
        framework: Framework
    ): Boolean {
        val existingText = existingClass.text
        val eqIdx = existingText.indexOf('=')
        if (eqIdx < 0) return false
        var valuePart = existingText.substring(eqIdx + 1).trim()
        // class={expr} → 取 expr 本体，避免生成 {clsx({expr}, ...)} 双层花括号
        if (valuePart.startsWith("{") && valuePart.endsWith("}")) {
            valuePart = valuePart.substring(1, valuePart.length - 1).trim()
        }
        val classAttrName = if (framework == Framework.VUE) "class" else "className"
        val file = existingClass.containingFile ?: return false
        val clsxName = if (framework == Framework.VUE) null else clsxLocalName(file)
        val newClassText = when {
            // 空值（class={}/class=""）：等价于没有旧类，直接写新值
            valuePart.isEmpty() -> "$classAttrName={$newAccess}"
            // Vue JSX：已是数组字面量 → 平铺，避免 [[a, b], new]
            // prefix/close 要同时补数组括号与 JSX 表达式容器括号（缺任一都会
            // 生成不平衡文本 → dummy 解析错误恢复吞掉尾部 ` />;`）
            framework == Framework.VUE && isWrappedArrayLiteral(valuePart) ->
                flattenInto("$classAttrName={[", valuePart, newAccess, "]}")
            framework == Framework.VUE ->
                "$classAttrName={[$valuePart, $newAccess]}"
            // React + clsx 可用：已是同名调用 → 平铺参数，避免 clsx(clsx(a, b), c)
            // flattenInto 的 wrappedValue 约定是「包装壳」（[args] / (args)），必须传
            // 去掉函数名后的参数括号段，否则首字符（函数名首字母）会被当壳误剥；
            // close 必须同时闭合调用括号与 JSX 表达式容器 `}`，缺 `}` 会让 dummy
            // 片段解析错误恢复、把 ` />;` 尾部吞进属性节点（历史 bug）
            clsxName != null && isCallOf(valuePart, clsxName) ->
                flattenInto(
                    "$classAttrName={$clsxName(",
                    valuePart.substring(valuePart.indexOf('(')),
                    newAccess, ")}"
                )
            clsxName != null ->
                "$classAttrName={$clsxName($valuePart, $newAccess)}"
            // 旧值已是模板字面量 → 内部追加，避免嵌套模板 `${`...`}`
            isTemplateLiteral(valuePart) ->
                "$classAttrName={${valuePart.dropLast(1)} \${$newAccess}`}"
            // 纯字符串字面量（内部无同类引号，排除 "a"+"b" 拼接）→ 平铺进模板
            isPlainJsStringLiteral(valuePart) ->
                "$classAttrName={`${valuePart.substring(1, valuePart.length - 1)} \${$newAccess}`}"
            // 动态表达式（三元/拼接/调用等）→ 模板字符串包裹
            else ->
                "$classAttrName={`\${$valuePart} \${$newAccess}`}"
        }
        val project = existingClass.project
        val newAttr = CssModuleFileResolver.createJsxAttributePsi(project, file, newClassText)
        if (newAttr == null) {
            LOG.warn("mergeIntoExistingClass: attribute node creation failed: $newClassText")
            return false
        }
        return runCatching {
            existingClass.replace(newAttr)
            deleteAttrWithLeadingWs(styleAttr)
            true
        }.getOrElse {
            LOG.warn("mergeIntoExistingClass failed", it)
            false
        }
    }

    /** 平铺合并：`prefix + 旧值去壳 + ", new" + suffix`（数组 [args] / 调用 (args) 共用）。 */
    private fun flattenInto(prefix: String, wrappedValue: String, newAccess: String, close: String): String {
        val inner = wrappedValue.substring(1, wrappedValue.length - 1).trim()
        return if (inner.isEmpty()) "$prefix$newAccess$close" else "$prefix$inner, $newAccess$close"
    }

    /** 删除属性节点及其前置空白（不含换行，保留行结构）。 */
    private fun deleteAttrWithLeadingWs(attr: PsiElement) {
        val prevWs = attr.prevSibling
        if (prevWs is PsiWhiteSpace && !prevWs.textContains('\n')) prevWs.delete()
        attr.delete()
    }

    /** `[a, b]` 形态的数组字面量（表达式只有以 `[` 开头才可能是数组字面量）。 */
    private fun isWrappedArrayLiteral(s: String): Boolean =
        s.length >= 2 && s.startsWith("[") && s.endsWith("]")

    /** `` `foo ${x}` `` 形态的单一模板字面量（内部无裸反引号，可安全内接）。 */
    private fun isTemplateLiteral(s: String): Boolean =
        s.length >= 2 && s.startsWith("`") && s.endsWith("`") &&
            !s.substring(1, s.length - 1).contains('`')

    /** `name(args...)` 形态的指定函数调用（贪婪匹配到末尾 `)`，内层括号保留在 args 里）。 */
    private fun isCallOf(s: String, name: String): Boolean =
        Regex("""^$name\s*\([\s\S]*\)$""").matches(s)

    private fun isPlainJsStringLiteral(s: String): Boolean {
        if (s.length < 2) return false
        val q = s.first()
        if ((q != '"' && q != '\'') || s.last() != q) return false
        // 内部不能再现同类引号：`"a" + "b"` 拼接不是单一字面量，平铺会产生悬挂引号
        return !s.substring(1, s.length - 1).contains(q)
    }

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
