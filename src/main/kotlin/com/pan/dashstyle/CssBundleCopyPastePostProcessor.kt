package com.pan.dashstyle

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.codeInsight.editorActions.TextBlockTransferableData
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.jetbrains.annotations.NotNull
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/**
 * #7. 拷贝 TSX 片段时把对应的 class CSS 也拷到目标文件。
 *
 * 采用 IDEA 原生做法：实现 CopyPastePostProcessor<TextTransferableData>，
 * 把 CSS bundle 作为独立的 DataFlavor 挂在剪贴板（不污染复制文本）。
 *  - collectTransferableData: 复制时遍历选区 PSI，收集 styles.xxx 对应的 ruleset，
 *    序列化成一个 CssBundleData 附加为剪贴板里的额外 DataFlavor。复制文本本身保持原样，
 *    因此复制到普通文本编辑器不会有任何多余内容。
 *  - extractTransferableData: 粘贴时从剪贴板读取该 DataFlavor。
 *  - processTransferableData: 等粘贴落地后，用 WriteCommandAction 把 CSS rules 注入目标。
 */
class CssBundleCopyPastePostProcessor : CopyPastePostProcessor<CssBundleCopyPastePostProcessor.CssBundleData>() {

    class CssBundleData(val json: String) : TextBlockTransferableData {
        override fun getFlavor(): DataFlavor = CSS_BUNDLE_FLAVOR

        companion object {
            @JvmStatic
            val CSS_BUNDLE_FLAVOR: DataFlavor = DataFlavor(
                "text/dashstyle-css-bundle;class=java.lang.String",
                "DashStyle CSS Bundle"
            )
        }
    }

    override fun collectTransferableData(
        file: @NotNull PsiFile,
        editor: @NotNull Editor,
        startOffsets: IntArray,
        endOffsets: IntArray
    ): List<CssBundleData> {
        val bundle = collectBundle(file, startOffsets, endOffsets) ?: return emptyList()
        if (bundle.rules.isEmpty()) return emptyList()
        return listOf(CssBundleData(gson.toJson(bundle)))
    }

    override fun extractTransferableData(content: @NotNull Transferable): List<CssBundleData> {
        val obj = runCatching {
            content.getTransferData(CssBundleData.CSS_BUNDLE_FLAVOR)
        }.getOrNull() ?: return emptyList()
        val data = when (obj) {
            is CssBundleData -> obj
            is String -> CssBundleData(obj)
            else -> return emptyList()
        }
        if (data.json.isBlank()) return emptyList()
        return listOf(data)
    }

    override fun processTransferableData(
        project: @NotNull Project,
        editor: @NotNull Editor,
        bounds: @NotNull RangeMarker,
        caretOffset: Int,
        indented: @NotNull Ref<in Boolean>,
        value: @NotNull List<CssBundleData>
    ) {
        val data = value.firstOrNull() ?: return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val bundle = runCatching {
            gson.fromJson(data.json, Bundle::class.java)
        }.getOrNull() ?: return
        if (bundle.rules.isEmpty()) return

        // 等粘贴完全落地后再注入，避免与平台粘贴写入冲突
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || !file.isValid) return@invokeLater
            runCatching {
                WriteCommandAction.writeCommandAction(project)
                    .withName("Append pasted CSS Module rules")
                    .run<Nothing> { applyBundle(project, file, bundle) }
            }.onFailure { t ->
                LOG.warn("DashStyle #7 applyBundle failed", t)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CssBundleCopyPastePostProcessor::class.java)
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

        val fileName = file.name.orEmpty()
        val isJsTsLike = fileName.endsWith(".js") || fileName.endsWith(".jsx") ||
            fileName.endsWith(".ts") || fileName.endsWith(".tsx")

        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        if (imports.any { imp ->
                pathsEquivalent(
                    (imp.importModuleText ?: return@any false).trim('"', '\''),
                    from
                )
            }) return

        val importText = "import $bindingName from '$from'\n"

        if (!isJsTsLike && file is XmlFile && fileName.endsWith(".vue")) {
            val script = Util.findScriptTag(file)
            if (script != null) {
                val existing = PsiTreeUtil.findChildrenOfType(script, ES6ImportDeclaration::class.java)
                if (existing.isEmpty()) {
                    val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
                    val embedded = findEmbeddedContent(script)
                    val startInDoc = embedded?.textRange?.startOffset
                        ?: (script.value?.textRange?.startOffset
                            ?: script.textRange.startOffset + "<script>".length)
                    doc.insertString(startInDoc, "\n$importText")
                    PsiDocumentManager.getInstance(project).commitDocument(doc)
                } else {
                    existing.last().parent.addAfter(
                        makeImportPsi(project, importText) ?: return, existing.last()
                    )
                }
            } else {
                val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
                doc.insertString(0, "\n<script setup>\n$importText</script>\n")
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
            return
        }

        val doc = PsiDocumentManager.getInstance(project).getDocument(file)
        if (doc != null) {
            val topLevelImports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
                .filter { it.parent == file || it.parent?.parent == file }
            if (topLevelImports.isEmpty()) {
                doc.insertString(0, importText)
            } else {
                val last = topLevelImports.maxByOrNull { it.textRange.endOffset } ?: return
                doc.insertString(last.textRange.endOffset, "\n$importText")
            }
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        } else {
            val newImport = makeImportPsi(project, importText) ?: return
            file.addBefore(newImport, file.firstChild)
        }
    }

    private fun makeImportPsi(project: Project, importText: String): ES6ImportDeclaration? {
        val factory = PsiFileFactory.getInstance(project)
        val dummy = factory.createFileFromText(
            "__dashstyle_import__.js",
            com.intellij.lang.Language.findInstance(com.intellij.lang.javascript.JavascriptLanguage::class.java),
            importText + "export {}"
        )
        return PsiTreeUtil.findChildrenOfType(dummy, ES6ImportDeclaration::class.java).firstOrNull()
    }

    private fun findEmbeddedContent(scriptTag: XmlTag): PsiElement? {
        return PsiTreeUtil.collectElements(scriptTag) { ele ->
            ele.parent.javaClass.simpleName.contains("VueScript", ignoreCase = true) ||
                ele.javaClass.simpleName.contains("EmbeddedContent", ignoreCase = true)
        }.maxByOrNull { it.textRange.length }
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