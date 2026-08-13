package com.pan.dashstyle

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RawText
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import org.jetbrains.annotations.NotNull
import java.util.concurrent.atomic.AtomicReference

/**
 * #7. 拷贝 TSX 片段时把对应的 class CSS 也拷到目标文件。
 *
 * 原理（只使用 CopyPastePreProcessor 双钩子 + invokeLater 调度，不依赖 CopyPastePostProcessor<T> 复杂泛型接口）：
 *  - `preprocessOnCopy`: 遍历被选中的 PSI，找到所有 className={styles.xxx} / styles["xxx"] /
 *    :class="$style.xxx" 等引用点，找到对应的 ruleset，序列化出 class + declarations。
 *    在复制文本末尾附一段注释 `/* __DS_CSS_BUNDLE__: <base64 json> */`
 *  - `preprocessOnPaste`: 检测 marker → 解析 JSON → 先从文本里剥离 marker 注释返回 →
 *    通过 invokeLater 等文档粘贴落地后，再用 WriteCommandAction 执行 CSS bundle 注入：
 *      a) 如果目标文件已经有对应 CSS Module import（相同 from 路径 或 相同 binding + 候选解析）→
 *         append CSS rules 到目标 Module 文件末尾
 *      b) 如果没有，就在源文件同目录找 <BaseName>.module.* → 有就追加，没有就新建并在目标文件顶部 prepend import
 */
class CssBundleCopyPastePostProcessor : CopyPastePreProcessor {

    companion object {
        private val LOG = Logger.getInstance(CssBundleCopyPastePostProcessor::class.java)
        private const val MARKER_BEGIN = "/* __DS_CSS_BUNDLE__:"
        private const val MARKER_END = " */"
        private val MARKER_RE = Regex("""/\* __DS_CSS_BUNDLE__:([A-Za-z0-9+/=\n\r]+?) \*/""")
        private val gson = Gson()

        data class CssClassRule(
            @SerializedName("selector") val selector: String,
            @SerializedName("declarations") val declarations: List<String>
        )

        data class Bundle(
            @SerializedName("rules") val rules: List<CssClassRule>,
            @SerializedName("sourceModule") val sourceModule: ImportMeta?
        )

        data class ImportMeta(
            @SerializedName("binding") val binding: String,
            @SerializedName("from") val from: String
        )
    }

    override fun preprocessOnCopy(
        file: PsiFile?,
        starts: IntArray?,
        ends: IntArray?,
        text: String?
    ): String? {
        if (file == null || starts == null || ends == null || text == null) return null
        val bundle = collectBundle(file, starts, ends) ?: return text
        if (bundle.rules.isEmpty()) return text
        val json = gson.toJson(bundle)
        val base64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return text + "\n" + MARKER_BEGIN + base64 + MARKER_END
    }

    @NotNull
    override fun preprocessOnPaste(
        project: Project?,
        file: PsiFile?,
        editor: Editor?,
        text: String?,
        rawText: RawText?
    ): String {
        if (project == null || file == null || editor == null || text == null) return text ?: ""

        val m = MARKER_RE.find(text)
        if (m == null) return text

        // 1. 解析 bundle（解析失败至少先把 marker strip 掉避免污染粘贴内容）
        val jsonStr = runCatching {
            String(java.util.Base64.getDecoder().decode(m.groupValues[1]), Charsets.UTF_8)
        }.getOrNull()
        val bundle = jsonStr?.let { runCatching { gson.fromJson(it, Bundle::class.java) }.getOrNull() }

        // 2. 无论成功失败，先从粘贴文本里去掉 marker
        val stripped = text.removeRange(m.range.first, m.range.last + 1).trimEnd() + "\n"

        // 3. 如果有 bundle，调度：等粘贴提交到文档之后，再注入 CSS rules
        //    （用 AtomicReference 把 context 搬过去，避免闭包可变变量警告）
        if (bundle != null) {
            val ctxRef = AtomicReference(Triple(project, file, bundle))
            ApplicationManager.getApplication().invokeLater {
                val (p, f, b) = ctxRef.get() ?: return@invokeLater
                if (!p.isDisposed && f.isValid && b.rules.isNotEmpty()) {
                    runCatching {
                        WriteCommandAction.writeCommandAction(p)
                            .withName("Append pasted CSS Module rules")
                            .run<Nothing> { applyBundle(p, f, b) }
                    }.onFailure { t ->
                        LOG.warn("DashStyle #7 applyBundle failed", t)
                    }
                }
            }
        }

        return stripped
    }

    // ================================================================
    // copy 端：收集 CSS bundle
    // ================================================================
    private fun collectBundle(file: PsiFile, starts: IntArray, ends: IntArray): Bundle? {
        val rules = mutableMapOf<String, CssClassRule>()
        var importMeta: ImportMeta? = null

        val candidates = collectUsageSites(file, starts, ends)
        for (site in candidates) {
            val requestedName = when (site) {
                is JSLiteralExpression -> site.stringValue ?: continue
                is JSReferenceExpression -> site.referenceName ?: continue
                else -> continue
            }
            val resolved = CssModuleResolver.resolveClassName(site, requestedName) ?: continue
            val expandedCls = ".${resolved.kebabName}"
            if (expandedCls in rules) continue
            val block = resolved.ruleset.block ?: continue
            val decls = PsiTreeUtil.findChildrenOfType(block, CssDeclaration::class.java)
                .map { d ->
                    val p = d.propertyName ?: ""
                    val v = d.value?.text ?: ""
                    "  $p: $v;"
                }
            rules[expandedCls] = CssClassRule(expandedCls, decls)

            if (importMeta == null) {
                when (val c = resolved.container) {
                    is CssModuleResolver.CssContainer.ImportedFile -> {
                        val from = computeRelPath(file.virtualFile, c.virtualFile) ?: continue
                        importMeta = ImportMeta(c.importBindingName, from)
                    }
                    else -> {}
                }
            }
        }
        return Bundle(rules.values.toList(), importMeta)
    }

    private fun computeRelPath(
        srcVf: com.intellij.openapi.vfs.VirtualFile?,
        tgtVf: com.intellij.openapi.vfs.VirtualFile
    ): String? {
        val parent = srcVf?.parent ?: return null
        val rel = java.nio.file.Path.of(parent.path)
            .relativize(java.nio.file.Path.of(tgtVf.path)).toString()
        val normalized = rel.replace('\\', '/')
        return if (normalized.startsWith("../") || normalized.startsWith("./"))
            normalized else "./$normalized"
    }

    private fun collectUsageSites(
        file: PsiFile,
        starts: IntArray,
        ends: IntArray
    ): List<PsiElement> {
        val out = mutableListOf<PsiElement>()
        for (i in starts.indices) {
            val s = starts[i]
            val e = ends[i]
            val startEl = file.findElementAt(s) ?: continue
            val endEl = file.findElementAt((e - 1).coerceAtLeast(s)) ?: startEl
            val root = PsiTreeUtil.findCommonParent(startEl, endEl) ?: file
            PsiTreeUtil.processElements(root) { el ->
                val tr = el.textRange
                val inRange = tr.startOffset in s..e ||
                    tr.endOffset in s..e ||
                    (tr.startOffset <= s && tr.endOffset >= e)
                if (inRange) {
                    if (el is JSLiteralExpression) {
                        val idx = PsiTreeUtil.getParentOfType(
                            el, JSIndexedPropertyAccessExpression::class.java
                        )
                        if (idx != null && idx.indexExpression == el) out += el
                    }
                    if (el is JSReferenceExpression && el.qualifier != null) {
                        val p = el.parent
                        if (p !is JSIndexedPropertyAccessExpression &&
                            (p !is JSCallExpression || p.methodExpression != el)
                        ) out += el
                    }
                }
                true
            }
        }
        return out
    }

    // ================================================================
    // paste 端：应用 bundle
    // ================================================================
    private fun applyBundle(project: Project, file: PsiFile, bundle: Bundle) {
        if (bundle.rules.isEmpty()) return
        val cssText = bundle.rules.joinToString("\n") { rule ->
            "${rule.selector} {\n${rule.declarations.joinToString("\n")}\n}"
        } + "\n"

        // 策略 1：如果 sourceModule 存在，且目标文件里已经有 "同 from" 或 "同 binding+候选文件" → 追加到那个文件
        if (bundle.sourceModule != null) {
            val container = resolveImportedContainer(project, file, bundle.sourceModule)
            if (container != null) {
                appendRulesToContainer(project, container, cssText)
                return
            }
        }

        // 策略 2：找目标文件旁边已有的同名模块文件 BaseName.module.*
        val existing = CssModuleResolver.findCandidateModuleFiles(file).firstOrNull()
        if (existing != null) {
            val psi = PsiManager.getInstance(project).findFile(existing.first)
            if (psi != null) {
                appendRulesToContainer(
                    project,
                    CssModuleResolver.CssContainer.ImportedFile(
                        psi, existing.first, existing.second, null
                    ),
                    cssText
                )
                ensureStylesImported(project, file, existing.first, existing.second)
                return
            }
        }

        // 策略 3：新建 BaseName.module.css，然后注入 import
        val vf = file.virtualFile ?: return
        val parent = vf.parent ?: return
        val newFileName = vf.nameWithoutExtension + ".module.css"
        val newFile = runCatching {
            parent.findOrCreateChildData(this, newFileName)
        }.getOrNull() ?: run {
            try {
                parent.createChildData(this, newFileName)
            } catch (t: Throwable) {
                LOG.warn("failed to create new CSS module file", t)
                return
            }
        }
        newFile.setBinaryContent(cssText.toByteArray(Charsets.UTF_8))
        ApplicationManager.getApplication().runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(newFile.toNioPath())
        }
        ensureStylesImported(project, file, newFile, "styles")
    }

    private fun appendRulesToContainer(
        project: Project,
        container: CssModuleResolver.CssContainer,
        cssText: String
    ) {
        val targetPsiFile: PsiFile? = when (container) {
            is CssModuleResolver.CssContainer.ImportedFile -> container.psiFile
            is CssModuleResolver.CssContainer.VueStyleTag -> container.containingFile
            is CssModuleResolver.CssContainer.LocalObjectLiteral -> null
        }
        val factory = PsiFileFactory.getInstance(project)
        val tmp = factory.createFileFromText(
            "__dashstyle_bundle__.css",
            com.intellij.lang.css.CSSLanguage.INSTANCE,
            cssText
        )
        if (container is CssModuleResolver.CssContainer.VueStyleTag) {
            val last = container.styleTag.lastChild
            if (last != null) container.styleTag.addAfter(tmp.firstChild, last)
            else container.styleTag.add(tmp.firstChild)
        } else if (targetPsiFile != null) {
            val last = targetPsiFile.lastChild
            if (last != null) targetPsiFile.addAfter(tmp.firstChild, last)
            else targetPsiFile.add(tmp.firstChild)
        }
    }

    private fun resolveImportedContainer(
        project: Project,
        file: PsiFile,
        srcMeta: ImportMeta
    ): CssModuleResolver.CssContainer.ImportedFile? {
        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        for (imp in imports) {
            val from = (imp.importModuleText ?: continue).trim('"', '\'')
            if (!pathsEquivalent(from, srcMeta.from)) continue
            val named = imp.namedImports
            val defaultBinding = imp.importedBindings.firstOrNull { b ->
                named == null || !PsiTreeUtil.isAncestor(named, b, false)
            } ?: continue
            val viaRef = defaultBinding.reference?.resolve()?.containingFile
            val vf = viaRef?.virtualFile ?: run {
                val parent = file.virtualFile?.parent ?: return@run null
                val rel = from.trimStart('/')
                val f = parent.findFileByRel(rel)
                    ?: parent.findChild(rel.substringAfterLast('/'))
                f?.let { PsiManager.getInstance(project).findFile(it)?.virtualFile }
            } ?: continue
            val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
            return CssModuleResolver.CssContainer.ImportedFile(
                psi, vf, defaultBinding.name ?: srcMeta.binding, imp
            )
        }
        return null
    }

    private fun ensureStylesImported(
        project: Project,
        file: PsiFile,
        targetCssVf: com.intellij.openapi.vfs.VirtualFile,
        bindingName: String
    ) {
        val from = computeRelPath(file.virtualFile, targetCssVf) ?: return
        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        if (imports.any { imp ->
                pathsEquivalent(
                    (imp.importModuleText ?: return@any false).trim('"', '\''),
                    from
                )
            }) return

        val text = "import $bindingName from '$from'\n"
        val factory = PsiFileFactory.getInstance(project)
        val dummy = factory.createFileFromText(
            "__dashstyle_import__.js",
            com.intellij.lang.Language.findInstance(com.intellij.lang.javascript.JavascriptLanguage::class.java),
            text + "export {}"
        )
        val newImport = PsiTreeUtil.findChildrenOfType(dummy, ES6ImportDeclaration::class.java)
            .firstOrNull() ?: return
        if (file is XmlFile && file.name.endsWith(".vue")) {
            val script = Util.findScriptTag(file)
            if (script != null) {
                if (imports.isEmpty()) {
                    val first = script.firstChild
                    if (first != null) script.addBefore(newImport, first)
                    else script.add(newImport)
                } else {
                    imports.last().parent.addAfter(newImport, imports.last())
                }
            } else {
                val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
                doc.insertString(0, "\n<script setup>\n$text</script>\n")
            }
        } else {
            if (imports.isEmpty()) {
                val first = file.firstChild
                if (first != null) file.addBefore(newImport, first)
                else file.add(newImport)
            } else {
                imports.last().parent.addAfter(newImport, imports.last())
            }
        }
    }

    private fun pathsEquivalent(a: String, b: String): Boolean {
        val na = a.removePrefix("./").replace('\\', '/').trimEnd('/')
        val nb = b.removePrefix("./").replace('\\', '/').trimEnd('/')
        return na == nb
    }

    private fun com.intellij.openapi.vfs.VirtualFile.findFileByRel(
        rel: String
    ): com.intellij.openapi.vfs.VirtualFile? {
        var cur: com.intellij.openapi.vfs.VirtualFile? = this
        for (seg in rel.replace('\\', '/').split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") {
                cur = cur?.parent
                continue
            }
            cur = cur?.findChild(seg) ?: return null
        }
        return cur
    }
}
