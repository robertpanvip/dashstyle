package com.pan.dashstyle

import com.intellij.ide.ClipboardSynchronizer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.indexing.FileBasedIndex
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.event.ItemEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * 🎨 新增功能：提取项目/选区公共颜色到 CSS 变量对话框，点击"确认"后：
 *   - 把生成的 :root { --color-x: #xxx; ... } 复制到剪贴板
 *   - 把所有涉及的颜色值就地替换为 `var(--color-x)`
 *
 * 触发入口：Code 菜单 / Help → Find Action → "DashStyle: Extract Colors as CSS Variables"
 * 或在 CSS 文件右键菜单、ProjectView 选中 CSS/LESS/SCSS 文件时触发。
 */
@Suppress("UnstableApiUsage")
class ExtractColorsAction : AnAction("DashStyle: Extract Colors as CSS Variables") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()
        val editor = e.getData(CommonDataKeys.EDITOR)
        val enabled = project != null && (files.isNotEmpty() || editor != null)
        e.presentation.isEnabledAndVisible = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()

        // 1) 确定要扫描的目标文件集合 + 选区范围（若有编辑器选区只处理选区）
        val targets: List<ScanTarget> = ApplicationManager.getApplication().runReadAction(
            Computable {
                buildTargets(project, editor, selFiles)
            }
        )
        if (targets.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "No CSS/SCSS/LESS content found to scan. Open a CSS file or select CSS/SCSS/LESS files in the Project view first.",
                "Extract Colors"
            )
            return
        }

        // 2) 扫描颜色 → 按归一化分组
        val scanResult: ScanResult = ApplicationManager.getApplication().runReadAction(
            Computable { scanColors(targets, project) }
        )
        if (scanResult.groups.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No colors (HEX/RGB/HSL/named) were found in the target scope.",
                "Extract Colors"
            )
            return
        }

        // 3) 弹对话框，用户可以修改变量名 / 选择是否替换
        val dlg = ExtractColorsDialog(project, scanResult)
        if (!dlg.showAndGet()) return

        // 4) OK 按下 → 复制 var 声明到剪贴板 + 原地替换
        val replacements = dlg.buildReplacements()
        val clipboardText = dlg.buildVariablesDeclaration()
        ClipboardSynchronizer.getInstance().setContent(
            StringSelection(clipboardText), StringSelection(clipboardText)
        )
        if (dlg.shouldReplaceInPlace()) {
            applyReplacements(project, targets, replacements)
        }

        Messages.showInfoMessage(
            project,
            buildString {
                append("CSS variables copied to clipboard (${replacements.size} unique colors).")
                append("\nPaste them into your theme file / :root {} block.")
                if (dlg.shouldReplaceInPlace()) append("\nOriginal references were replaced with var(--x) in place.")
            },
            "Extract Colors OK"
        )
    }

    // ================================================================
    // Step 1: 收集 ScanTarget 列表（每个 Document 可选带 range 限制）
    // ================================================================
    private data class ScanTarget(
        val virtualFile: VirtualFile,
        val document: Document,
        /** 若非空则只扫描这些 offset 范围（通常对应编辑器选区） */
        val restrictedRanges: List<IntRange> = emptyList()
    ) {
        fun text(): String = document.text
        fun offsetInScope(offset: Int): Boolean =
            restrictedRanges.isEmpty() || restrictedRanges.any { offset in it }
    }

    private fun buildTargets(
        project: Project,
        editor: com.intellij.openapi.editor.Editor?,
        selFiles: Array<VirtualFile>
    ): List<ScanTarget> {
        val out = mutableListOf<ScanTarget>()
        val fdm = FileDocumentManager.getInstance()
        val seen = hashSetOf<VirtualFile>()

        // 优先级 A: 当前编辑器 + 选区
        if (editor != null) {
            val doc = editor.document
            val vf = fdm.getFile(doc)
            if (vf != null && looksLikeStyleFile(vf)) {
                val sel: SelectionModel = editor.selectionModel
                val ranges = if (sel.hasSelection())
                    listOf(sel.selectionStart until sel.selectionEnd)
                else emptyList()
                if (seen.add(vf)) out += ScanTarget(vf, doc, ranges)
            }
        }

        // 优先级 B: 选中的文件 / 目录（递归）
        for (vf in selFiles) {
            collectStyleFiles(vf, seen).forEach { f ->
                val doc = fdm.getDocument(f) ?: return@forEach
                if (seen.add(f)) out += ScanTarget(f, doc)
            }
        }

        // 都没指定 → 扫描整个 project 的 CSS/SCSS/LESS 文件
        if (out.isEmpty()) {
            val css = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                .getFileTypeByExtension("css")
            val scss = try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                    .getFileTypeByExtension("scss")
            } catch (_: Throwable) { null }
            val less = try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                    .getFileTypeByExtension("less")
            } catch (_: Throwable) { null }
            val types = listOfNotNull(css, scss, less).toSet()
            val scope = GlobalSearchScope.projectScope(project)
            val allVfs = FileBasedIndex.getInstance()
                .getContainingFiles(FileTypeIndex.NAME, css, scope)
                .toMutableList()
            // 额外再手动扫项目根目录下的 scss/less
            project.basePath?.let { base ->
                addByExtRecursive(java.io.File(base), listOf(".scss", ".sass", ".less")) { f ->
                    runCatching { com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .findFileByIoFile(f) }.getOrNull()
                }?.forEach { allVfs += it }
            }
            for (f in allVfs) {
                if (!looksLikeStyleFile(f)) continue
                val doc = fdm.getDocument(f) ?: continue
                if (seen.add(f)) out += ScanTarget(f, doc)
            }
        }
        return out
    }

    private fun collectStyleFiles(root: VirtualFile, seen: MutableSet<VirtualFile>): List<VirtualFile> {
        val out = mutableListOf<VirtualFile>()
        val stack = ArrayDeque<VirtualFile>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            when {
                f.isDirectory -> {
                    for (child in f.children) stack.add(child)
                }
                looksLikeStyleFile(f) && seen.add(f) -> out += f
            }
        }
        return out
    }

    private fun addByExtRecursive(
        dir: java.io.File,
        exts: List<String>,
        vfResolver: (java.io.File) -> VirtualFile?
    ): List<VirtualFile> {
        val out = mutableListOf<VirtualFile>()
        val stack = ArrayDeque<java.io.File>()
        if (dir.isDirectory) stack.add(dir)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (!f.isDirectory) {
                if (exts.any { f.name.endsWith(it, true) }) {
                    vfResolver(f)?.let { out += it }
                }
                continue
            }
            // 跳过 node_modules / build / .git
            if (f.name == "node_modules" || f.name.startsWith(".") || f.name == "build" || f.name == "dist") continue
            f.listFiles()?.forEach { stack.add(it) }
        }
        return out
    }

    private fun looksLikeStyleFile(vf: VirtualFile): Boolean {
        if (vf.isDirectory) return false
        val n = vf.name.lowercase()
        if (n.endsWith(".css") || n.endsWith(".scss") || n.endsWith(".less") || n.endsWith(".sass")) return true
        // 非样式后缀的文件 → 检查它是不是 Vue SFC（把 <style> 里当作用域）
        return n.endsWith(".vue")
    }

    // ================================================================
    // Step 2: 扫描所有 target → 颜色分组
    // ================================================================
    private data class GroupedColor(
        val normalized: String,
        /** 第一个发现的原始文本（用作预览） */
        val sampleOriginal: String,
        /** 出现次数 */
        var count: Int = 0,
        /** 所有发生点：(target, range, original) */
        val occurrences: MutableList<Occurrence> = mutableListOf()
    )

    private data class Occurrence(
        val target: ScanTarget,
        val range: IntRange,
        val original: String
    )

    private data class ScanResult(
        val groups: List<GroupedColor>,
        val targets: List<ScanTarget>
    )

    private fun scanColors(targets: List<ScanTarget>, project: Project): ScanResult {
        val groups = linkedMapOf<String, GroupedColor>()
        for (t in targets) {
            // Vue 文件：只扫 <style> 内部，避免 script/template 里字符串被错当作颜色
            val styleRanges: List<IntRange> = if (t.virtualFile.name.endsWith(".vue", true))
                extractVueStyleRanges(t.virtualFile, project)
            else emptyList()

            val fullText = t.text()
            val tokens = Util.scanColorsInText(fullText)
            for ((orig, norm, range) in tokens) {
                if (t.restrictedRanges.isNotEmpty() && !t.offsetInScope(range.first)) continue
                if (styleRanges.isNotEmpty() && styleRanges.none { range.first in it }) continue
                // 已替换成 var(--x) 的颜色要排除（避免二次提取）
                val pre = fullText.subSequence(
                    (range.first - 20).coerceAtLeast(0), range.first
                ).toString()
                if (pre.endsWith("var(--") || pre.endsWith("var( --")) continue
                val g = groups.getOrPut(norm) { GroupedColor(norm, orig) }
                g.count++
                g.occurrences += Occurrence(t, range, orig)
            }
        }
        // 按出现次数降序，次数相同按 sample 字典序
        val sorted = groups.values.sortedWith(
            compareByDescending<GroupedColor> { it.count }.thenBy { it.normalized }
        )
        return ScanResult(sorted, targets)
    }

    private fun extractVueStyleRanges(vf: VirtualFile, project: Project): List<IntRange> {
        val psi = PsiManager.getInstance(project).findFile(vf) as? XmlFile ?: return emptyList()
        val out = mutableListOf<IntRange>()
        val styles = PsiTreeUtil.findChildrenOfType(psi, XmlTag::class.java)
            .filter { it.name.equals("style", ignoreCase = true) }
        for (s in styles) {
            val value = s.value
            if (value != null) {
                val tr = value.textRange
                if (tr.length > 0) out += tr.startOffset until tr.endOffset
            } else {
                // fallback: 从 tagText 里定位 '>' 之后到最后一个 '</style' 之前
                val tr = s.textRange
                val txt = s.text
                val first = txt.indexOf('>')
                val last = txt.lastIndexOf('<')
                if (first in 0 until last) {
                    out += (tr.startOffset + first + 1) until (tr.startOffset + last)
                }
            }
        }
        return out
    }

    // ================================================================
    // Step 3: 对话框（DialogWrapper）
    // ================================================================
    private class ExtractColorsDialog(
        project: Project,
        private val result: ScanResult
    ) : DialogWrapper(project, true) {

        private data class Row(
            var varName: String,
            val normalized: String,
            val sample: String,
            val count: Int,
            val checked: Boolean // 是否应用替换（默认全部 true）
        )

        private val rows: MutableList<Row>
        private val chkReplace = JCheckBox("Replace color references with var(--x) in source files", true)
        private val previewArea = JBTextArea(6, 48).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }

        init {
            title = "Extract Project Colors as CSS Variables"
            // 建议初始变量名（按出现频率排序，index 从 0 开始）
            val used = mutableSetOf<String>()
            rows = result.groups.mapIndexed { idx, g ->
                val name = Util.suggestColorVarName(g.normalized, used, idx)
                used += name
                Row(name, g.normalized, g.sampleOriginal, g.count, true)
            }.toMutableList()
            init()
            refreshPreview()
        }

        override fun createCenterPanel(): JComponent {
            val root = JPanel(BorderLayout(8, 8))
            root.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

            // 顶部提示
            val hint = JBLabel(
                "<html><b>${rows.size} unique colors</b> across ${result.targets.size} file(s). " +
                    "Edit the variable names in the table. Click OK to copy the CSS variables to clipboard.</html>"
            )
            root.add(hint, BorderLayout.NORTH)

            // 中部：颜色表格
            val model = ColorTableModel()
            val table = JBTable(model)
            table.rowHeight = 30
            table.columnModel.getColumn(0).also { col ->
                col.preferredWidth = 60
                col.minWidth = 50
                col.cellRenderer = SwatchRenderer()
            }
            table.columnModel.getColumn(1).preferredWidth = 260
            table.columnModel.getColumn(2).also { col ->
                col.preferredWidth = 110
                col.minWidth = 100
                col.cellRenderer = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.CENTER }
            }
            table.columnModel.getColumn(3).preferredWidth = 140
            table.columnModel.getColumn(4).also { col ->
                col.preferredWidth = 160
                col.cellRenderer = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.CENTER }
                col.cellEditor = DefaultCellEditor(ComboBox(arrayOf(Scope.FILE, Scope.SELECTION, Scope.PROJECT)))
                col.maxWidth = 160
            }
            // 单元格变化 → 刷新预览
            table.cellEditor?.addCellEditorListener(object : javax.swing.event.CellEditorListener {
                override fun editingStopped(e: javax.swing.event.ChangeEvent?) { refreshPreview() }
                override fun editingCanceled(e: javax.swing.event.ChangeEvent?) {}
            })

            val sp = JBScrollPane(table).apply {
                preferredSize = Dimension(760, 360)
            }
            root.add(sp, BorderLayout.CENTER)

            // 下部：替换选项 + 预览
            val bottom = JPanel(BorderLayout(6, 6))
            bottom.add(chkReplace, BorderLayout.NORTH)
            bottom.add(JBLabel("<html><b>Generated CSS variables (will be copied to clipboard on OK):</b></html>"),
                BorderLayout.CENTER)
            val spPrev = JBScrollPane(previewArea).apply { preferredSize = Dimension(760, 140) }
            bottom.add(spPrev, BorderLayout.SOUTH)
            root.add(bottom, BorderLayout.SOUTH)

            return root
        }

        private fun refreshPreview() { previewArea.text = buildVariablesDeclaration() }

        fun buildVariablesDeclaration(): String {
            val body = rows.filter { it.checked }.joinToString("\n") { r ->
                "  ${r.varName}: ${r.normalized};"
            }
            return ":root {\n$body\n}\n"
        }

        fun shouldReplaceInPlace(): Boolean = chkReplace.isSelected

        /** 把 occurrences group 展开为替换计划：按 (document, offsetStart) 排序，从后向前 apply。 */
        fun buildReplacements(): List<Pair<Occurrence, String>> {
            val normToVar = rows.filter { it.checked }.associate { it.normalized to it.varName }
            val out = mutableListOf<Pair<Occurrence, String>>()
            for (g in result.groups) {
                val v = normToVar[g.normalized] ?: continue
                val varText = "var($v)"
                for (occ in g.occurrences) out += (occ to varText)
            }
            return out
        }

        // --- TableModel + Renderer ---
        private enum class Scope { PROJECT, FILE, SELECTION }

        private inner class ColorTableModel : AbstractTableModel() {
            private val columns = listOf("Swatch", "Variable name (--color-xxx)", "Occurrences", "Sample value", "Scope")
            override fun getRowCount(): Int = rows.size
            override fun getColumnCount(): Int = columns.size
            override fun getColumnName(col: Int): String = columns[col]
            override fun getColumnClass(col: Int): Class<*> = when (col) {
                0, 2, 4, 3 -> String::class.java
                1 -> String::class.java
                else -> Any::class.java
            }
            override fun isCellEditable(row: Int, col: Int): Boolean = col == 1 // 变量名可编辑
            override fun getValueAt(row: Int, col: Int): Any {
                val r = rows[row]
                return when (col) {
                    0 -> r.normalized
                    1 -> r.varName
                    2 -> r.count.toString()
                    3 -> r.sample
                    4 -> Scope.FILE.toString()
                    else -> ""
                }
            }
            override fun setValueAt(aValue: Any?, row: Int, col: Int) {
                if (col == 1 && aValue is String) {
                    val newName = aValue.trim()
                    if (newName.isEmpty()) return
                    val final = if (newName.startsWith("--")) newName else "--$newName"
                    rows[row].varName = final
                    fireTableCellUpdated(row, col)
                    refreshPreview()
                }
            }
        }

        private class SwatchRenderer : JLabel(), TableCellRenderer {
            init { isOpaque = true; border = BorderFactory.createEmptyBorder(3, 6, 3, 6) }
            override fun getTableCellRendererComponent(
                table: JTable?, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): Component {
                // 背景跟随当前 LaF：选中 → table.selectionBackground；未选中 → table.background
                val norm = (value as? String)
                if (norm == null) {
                    background = if (isSelected) table?.selectionBackground else table?.background
                    icon = null
                    return this
                }
                background = if (isSelected) table?.selectionBackground else table?.background
                val color: java.awt.Color = try {
                    parseColorForSwatch(norm)
                } catch (_: Throwable) {
                    JBColor.GRAY
                }
                icon = SwatchIcon(20, 16, color)
                toolTipText = norm
                return this
            }
        }

        private class SwatchIcon(
            private val w: Int, private val h: Int, private val color: java.awt.Color
        ) : javax.swing.Icon {
            override fun paintIcon(c: Component?, g: java.awt.Graphics, x: Int, y: Int) {
                val g2 = g as java.awt.Graphics2D
                val old = g2.color
                // 外边框（灰白相间）
                g2.color = JBColor.border()
                g2.drawRect(x, y, w - 1, h - 1)
                // 颜色填充
                g2.color = color
                g2.fillRect(x + 1, y + 1, w - 2, h - 2)
                g2.color = old
            }
            override fun getIconWidth(): Int = w
            override fun getIconHeight(): Int = h
        }

        companion object {
            /** 把归一化颜色值转成 java.awt.Color（swatch 预览用），解析失败返回灰。 */
            private fun parseColorForSwatch(normalized: String): java.awt.Color {
                try {
                    return when {
                        normalized.startsWith("#") && normalized.length >= 7 -> {
                            val r = normalized.substring(1,3).toInt(16)
                            val g = normalized.substring(3,5).toInt(16)
                            val b = normalized.substring(5,7).toInt(16)
                            val a = if (normalized.length >= 9) normalized.substring(7,9).toInt(16) else 255
                            java.awt.Color(r, g, b, a)
                        }
                        normalized.startsWith("rgb") -> {
                            val m = Regex("""\(([^)]+)\)""").find(normalized)?.groupValues?.get(1)
                                ?: return JBColor.GRAY
                            val parts = m.split(Regex(""",\s*""")).map { it.trim() }
                            fun toIntOrPct(s: String): Int {
                                if (s.endsWith('%')) return (s.dropLast(1).toDoubleOrNull()?.times(2.55))?.toInt()?.coerceIn(0,255) ?: 0
                                return s.toDoubleOrNull()?.toInt()?.coerceIn(0,255) ?: 0
                            }
                            val r = toIntOrPct(parts[0]); val g = toIntOrPct(parts[1]); val b = toIntOrPct(parts[2])
                            val a = (parts.getOrNull(3)?.toDoubleOrNull()?.times(255))?.toInt()?.coerceIn(0,255) ?: 255
                            java.awt.Color(r, g, b, a)
                        }
                        else -> {
                            val byName = try {
                                com.intellij.ui.ColorUtil.fromHex(normalized)
                            } catch (_: Throwable) {
                                null
                            }
                            if (byName != null) byName else return JBColor.GRAY
                        }
                    }
                } catch (_: Throwable) {
                    return JBColor.GRAY
                }
            }
        }
    }

    // ================================================================
    // Step 4: 在文档中执行替换
    // ================================================================
    private fun applyReplacements(
        project: Project,
        targets: List<ScanTarget>,
        replacements: List<Pair<Occurrence, String>>
    ) {
        val byDoc = replacements.groupBy { it.first.target.document }
        WriteCommandAction.writeCommandAction(project)
            .withName("Replace color literals with CSS variables")
            .run<Nothing> {
                for ((doc, list) in byDoc) {
                    // 按 offset 从大到小替换，避免小 index 先改导致后续 range 移位
                    val sorted = list.sortedByDescending { it.first.range.first }
                    for ((occ, varText) in sorted) {
                        val r = occ.range
                        val len = r.last - r.first + 1
                        if (r.first < 0 || r.first + len > doc.textLength) continue
                        // sanity 校验：当前位置文本是否仍然匹配（避免中间被别人改了）
                        val cur = doc.immutableCharSequence.subSequence(r.first, r.first + len).toString()
                        val normCur = Util.normalizeColor(cur)
                        val normOcc = Util.normalizeColor(occ.original)
                        if (normCur != normOcc) {
                            LOG.warn("Skip replacement at offset ${r.first} in ${occ.target.virtualFile.name}: expected ${occ.original}, got $cur")
                            continue
                        }
                        doc.replaceString(r.first, r.last + 1, varText)
                    }
                }
                // 提交 PSI 同步
                val pdm = PsiDocumentManager.getInstance(project)
                for (t in targets) pdm.commitDocument(t.document)
            }
    }

    companion object {
        private val LOG = Logger.getInstance(ExtractColorsAction::class.java)
    }
}
