package com.pan.dashstyle.action

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import java.util.regex.Pattern

/**
 * 在 TSX 文件中 → Refactor → Convert className to CSS Module.
 *
 * 一键迁移传统 CSS 项目到 CSS Modules。
 *
 * 流程：
 * 1. 无选区 → 扫描整个文件；有选区 → 只扫描选中区域
 * 2. 收集所有 className="xxx" / className={'xxx'} 字符串字面量中的类名
 * 3. 查找或创建 CSS Module 文件（*.module.css / .less / .scss）
 * 4. 生成 import styles 语句（如果缺失）
 * 5. 将 className="foo" 替换为 className={styles.foo}
 * 6. 在 CSS Module 文件中生成 .foo { } 规则
 */
class ConvertClassNameToCssModuleAction : AnAction(
    "Convert className to CSS Module"
) {

    companion object {
        // 匹配 className="..." 或 className='...' 中的类名
        private val CLASS_NAME_SPLIT = Regex("""\s+""")
    }

    // ================================================================
    // 可见性：
    //   - 在顶层菜单始终显示（Refactor / 右键 RefactorThis / 键盘快捷键），避免"菜单里找不到"的体验
    //   - 真正可用仅当：有项目 + 有编辑器 + 文件是 TSX / JSX / Vue（JSX 风格也能解析
    //     className="..."）。其他场景置为禁用 + 附 tooltip 原因
    // ================================================================

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)

        val ext = file?.virtualFile?.extension?.lowercase()
        val supportedFile = ext == "tsx" || ext == "jsx" || ext == "vue"

        val hasContext = project != null && editor != null && file != null
        val enabled = hasContext && supportedFile

        e.presentation.isEnabledAndVisible = hasContext || project != null
        // 没有项目时直接隐藏；有项目但不满足条件也显示（置灰），让用户知道有这个功能
        if (hasContext) {
            e.presentation.isEnabled = enabled
            e.presentation.isVisible = true
        }
        e.presentation.text = "Convert className to CSS Module..."
        if (!enabled && hasContext) {
            e.presentation.description = when (ext) {
                "ts", "js" -> "This action requires JSX markup — switch to a .tsx / .jsx file"
                "vue" -> "Vue SFCs are supported experimentally; only JSX-style `className=\"...\"` attributes inside `<script setup lang=\"tsx\">` or JSX blocks will be converted"
                null -> "Open a .tsx / .jsx file first"
                else -> "Unsupported file type: $ext (expected .tsx / .jsx / .vue)"
            }
        }
    }

    // ================================================================
    // 主入口
    // ================================================================

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val selection = editor.selectionModel
        val (selStart, selEnd) = if (selection.hasSelection()) {
            selection.selectionStart to selection.selectionEnd
        } else {
            // 没有选区 → 扫描整个文件
            0 to file.textRange.endOffset
        }

        // 1. 扫描整个选区/文件，收集 className 字面量中的类名
        val rawClassNames = collectClassNameValues(file, selStart, selEnd)
        if (rawClassNames.isEmpty()) {
            val scope = if (selStart == 0 && selEnd == file.textRange.endOffset) "the whole file" else "selected code"
            Messages.showInfoMessage(
                project,
                "No className string literals found in $scope.\n" +
                        "Make sure you have JSX with className=\"...\" attributes.",
                "Convert className to CSS Module"
            )
            return
        }

        // 去重、排序
        val uniqueNames = rawClassNames.toSortedSet()

        // 2. 确认用户想要转换的类名
        val preview = uniqueNames.joinToString("\n  ", prefix = "  ")
        val confirm = Messages.showYesNoDialog(
            project,
            "Found ${uniqueNames.size} class name(s) to convert:\n$preview\n\n" +
                    "Do you want to proceed?",
            "Convert className to CSS Module",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        // 3. 查找或创建 CSS Module 文件
        val (moduleFile, isNewFile) = resolveModuleFile(project, file) ?: return

        // 4. 生成 import（如果缺失）
        val importBinding = CssModuleResolver.ensureImportExists(project, file, moduleFile)

        // 5. 替换选中区域中的 className 字面量
        replaceClassNames(project, editor, file, selStart, selEnd, uniqueNames, importBinding)

        // 6. 在 CSS Module 文件中追加规则 → 只对新建空文件追加，复制的文件已有内容不需要追加
        if (isNewFile) {
            appendCssRules(project, moduleFile, uniqueNames)
        }

        Messages.showInfoMessage(
            project,
            "Converted ${uniqueNames.size} class name(s) to CSS Module.\n" +
                    "Import: `import $importBinding from './${moduleFile.name}'`\n" +
                    "Target: ${moduleFile.path}",
            "Convert className to CSS Module"
        )
    }

    // ================================================================
    // 步骤 1：收集 className 属性值中的类名
    // ================================================================

    /**
     * 扫描 [start, end) 范围内的 PSI 元素，收集所有 className 属性的字符串值。
     * 支持：
     *   - className="foo bar"  →  ["foo", "bar"]
     *   - className={'foo'}    →  ["foo"]
     *   - className={`foo`}    →  fallback 提取静态部分
     */
    data class ClassNameSite(
        val literal: PsiElement, // 字符串字面量 PSI
        val value: String,       // 完整的字符串内容
        val startOffset: Int,    // 在文件中的 textOffset
        val endOffset: Int       // 结束偏移
    )

    private fun collectClassNameValues(file: PsiFile, start: Int, end: Int): Set<String> {
        val result = mutableSetOf<String>()

        // 方法 1：通过 PSI 查找 JSXAttribute / JSAttribute
        PsiTreeUtil.findChildrenOfType(file, JSLiteralExpression::class.java).forEach { literal ->
            // 跳过不在选中范围内的
            val textRange = literal.textRange
            if (textRange.startOffset < start || textRange.endOffset > end) return@forEach

            // 检查父节点是否是 className 属性
            val parent = literal.parent ?: return@forEach
            if (!isClassNameAttribute(parent)) return@forEach

            // 提取字符串值
            val strValue = literal.stringValue ?: return@forEach
            val parts = strValue.split(CLASS_NAME_SPLIT).filter { it.isNotBlank() }
            result += parts
        }

        // 方法 2：文本级 fallback（当 PSI 结构不完整时）
        // 扫描选中文本中的 className="xxx" 或 className='xxx'
        if (result.isEmpty()) {
            val selectedText = file.text.substring(start, end)
            val textPattern = Pattern.compile("""className\s*=\s*["']([^"']*)["']""")
            val matcher = textPattern.matcher(selectedText)
            while (matcher.find()) {
                val value = matcher.group(1).trim()
                if (value.isNotBlank()) {
                    val parts = value.split(CLASS_NAME_SPLIT).filter { it.isNotBlank() }
                    result += parts
                }
            }
        }

        return result
    }

    /**
     * 判断 PSI 元素是否是 className 属性（兼容多种 JSX/JSAttribute 实现）。
     */
    private fun isClassNameAttribute(element: PsiElement): Boolean {
        val className = element.javaClass.name
        // 新版 JSAttribute / JsxAttribute 等
        if (className.contains("JSAttribute", ignoreCase = true) ||
            className.contains("JsxAttribute", ignoreCase = true) ||
            className.contains("JSXAttribute", ignoreCase = true)
        ) {
            val attrName = runCatching {
                element.javaClass.methods.firstOrNull { m ->
                    m.name == "getName" && m.parameterCount == 0
                }?.invoke(element) as? CharSequence
            }.getOrNull()?.toString()
            return attrName == "className"
        }
        // XmlAttribute（某些版本将 JSX 解析为 XML）
        if (element is XmlAttribute) {
            return element.name == "className"
        }
        // 文本匹配（兜底）
        val text = element.text.trimStart()
        return text.startsWith("className") || text.startsWith("className=")
    }

    // ================================================================
    // 步骤 3：查找或创建 CSS Module 文件（交互逻辑保留，文件解析委托 CssModuleResolver）
    // ================================================================

    /**
     * 流程：
     * 1. 已有 import 指向 module 文件 → 直接用
     * 2. 同目录已有 *.module.* 文件 → 直接用
     * 3. 扫描 import './xxx.less'（非 module）→ 重命名或复制
     * 4. 同目录有 plain CSS → 重命名或复制
     * 5. 都没有 → 新建空 module 文件
     */
    private fun resolveModuleFile(project: Project, sourceFile: PsiFile): Pair<VirtualFile, Boolean>? {
        val vf = sourceFile.virtualFile ?: return null
        val parent = vf.parent ?: return null
        val sourceExt = vf.extension?.lowercase()
        val R = CssModuleResolver

        // 1. 已有 import 指向 module 文件
        R.findExistingModuleImport(sourceFile)?.let { return Pair(it.first, false) }

        // 2. 同目录已有 *.module.* 文件
        val existingModules = parent.children.filter { child ->
            R.MODULE_EXTS.any { child.name.endsWith(it, ignoreCase = true) }
        }.filter { it.isValid && !it.isDirectory }

        if (existingModules.isNotEmpty()) {
            if (existingModules.size == 1) return Pair(existingModules[0], false)
            val candidates = existingModules.map { it.name }.toTypedArray()
            val idx = Messages.showChooseDialog(
                project,
                "Multiple CSS Module files found. Choose one:",
                "Convert className to CSS Module",
                Messages.getQuestionIcon(),
                candidates,
                candidates.firstOrNull() ?: ""
            )
            if (idx < 0) return null
            return Pair(existingModules[idx], false)
        }

        // 3. 扫描 import 的 plain CSS 文件
        val importedPlainFiles = R.collectImportedPlainFiles(sourceFile, parent)
        when {
            importedPlainFiles.size == 1 -> {
                val (file, refCount) = importedPlainFiles.first()
                val newName = R.renameToModule(file.name)
                if (refCount == 1) {
                    val ans = Messages.showYesNoDialog(
                        project,
                        "Found imported file `${file.name}`.\n" +
                                "It's only imported in this file. Rename it to `$newName`?",
                        "Convert className to CSS Module",
                        Messages.getQuestionIcon()
                    )
                    if (ans == Messages.YES) {
                        val renamed = runCatching { file.rename(null, newName); file }.getOrNull()
                        if (renamed != null) return Pair(renamed, false)
                    }
                    return R.copyToModule(parent, file, vf.nameWithoutExtension, sourceExt)
                } else {
                    val ans = Messages.showYesNoDialog(
                        project,
                        "Found imported file `${file.name}`.\n" +
                                "It's imported in $refCount files (other than this one), so we can't rename it.\n" +
                                "Create a copy `$newName` for CSS Module?",
                        "Convert className to CSS Module",
                        Messages.getQuestionIcon()
                    )
                    if (ans != Messages.YES) return null
                    return R.copyToModule(parent, file, vf.nameWithoutExtension, sourceExt)
                }
            }
            importedPlainFiles.size > 1 -> {
                val candidates = importedPlainFiles.map { "${it.first.name} (${it.second} imports)" }.toTypedArray()
                val rawFiles = importedPlainFiles.map { it.first }.toList()
                val idx = Messages.showChooseDialog(
                    project,
                    "Found multiple imported CSS files. Which one to use for CSS Module?",
                    "Convert className to CSS Module",
                    Messages.getQuestionIcon(),
                    candidates,
                    candidates[0]
                )
                if (idx < 0) return null
                val chosen = rawFiles[idx]
                val (_, refCount) = importedPlainFiles[idx]
                val newName = R.renameToModule(chosen.name)
                if (refCount == 1) {
                    val ans = Messages.showYesNoDialog(
                        project,
                        "`${chosen.name}` is only imported in this file. Rename it to `$newName`?",
                        "Convert className to CSS Module",
                        Messages.getQuestionIcon()
                    )
                    if (ans == Messages.YES) {
                        val renamed = runCatching { chosen.rename(null, newName); chosen }.getOrNull()
                        if (renamed != null) return Pair(renamed, false)
                    }
                }
                return R.copyToModule(parent, chosen, vf.nameWithoutExtension, sourceExt)
            }
        }

        // 4. 同目录 plain CSS 文件
        val plainFiles = parent.children.filter { child ->
            R.PLAIN_EXTS.any { child.name.endsWith(it, ignoreCase = true) } &&
                    !R.MODULE_EXTS.any { child.name.endsWith(it, ignoreCase = true) }
        }
        if (plainFiles.isNotEmpty()) {
            val candidates = plainFiles.map { it.name }
            val idx = Messages.showChooseDialog(
                project,
                "No imported CSS found. Would you like to rename one to *.module.*?\n" +
                        "Choose a file:",
                "Convert className to CSS Module",
                Messages.getQuestionIcon(),
                candidates.toTypedArray(),
                candidates.firstOrNull() ?: ""
            )
            if (idx < 0) return null
            val chosen = plainFiles[idx]
            val refCount = R.countReferences(chosen, project)
            val newName = R.renameToModule(chosen.name)
            if (refCount <= 1) {
                val renamed = runCatching { chosen.rename(null, newName); chosen }.getOrNull()
                if (renamed != null) return Pair(renamed, false)
            }
            return R.copyToModule(parent, chosen, vf.nameWithoutExtension, sourceExt)
        }

        // 5. 新建空 module 文件
        val newFile = R.createModuleFile(parent, vf.nameWithoutExtension, sourceExt)
        return if (newFile != null) Pair(newFile, true) else null
    }

    // ================================================================
    // 步骤 5：替换 className 字面量
    // ================================================================

    private fun replaceClassNames(
        project: Project,
        editor: Editor,
        file: PsiFile,
        selStart: Int,
        selEnd: Int,
        classNames: Set<String>,
        importBinding: String
    ) {
        // 收集所有需要替换的 site
        val sites = mutableListOf<ClassNameSite>()

        PsiTreeUtil.findChildrenOfType(file, JSLiteralExpression::class.java).forEach { literal ->
            val textRange = literal.textRange
            if (textRange.startOffset < selStart || textRange.endOffset > selEnd) return@forEach
            val parent = literal.parent ?: return@forEach
            if (!isClassNameAttribute(parent)) return@forEach

            val strValue = literal.stringValue ?: return@forEach
            val parts = strValue.split(CLASS_NAME_SPLIT).filter { it.isNotBlank() }
            val matched = parts.filter { it in classNames }
            if (matched.isNotEmpty()) {
                sites.add(
                    ClassNameSite(
                        literal = literal,
                        value = strValue,
                        startOffset = textRange.startOffset,
                        endOffset = textRange.endOffset
                    )
                )
            }
        }

        // 如果 PSI 没有找到，回退到文本搜索
        if (sites.isEmpty()) {
            val selectedText = file.text.substring(selStart, selEnd)
            val textPattern = Pattern.compile("""className\s*=\s*["']([^"']*)["']""")
            val matcher = textPattern.matcher(selectedText)
            while (matcher.find()) {
                val value = matcher.group(1).trim()
                if (value.isNotBlank()) {
                    val parts = value.split(CLASS_NAME_SPLIT).filter { it.isNotBlank() }
                    val matched = parts.filter { it in classNames }
                    if (matched.isNotEmpty()) {
                        val absStart = selStart + matcher.start(1)
                        val absEnd = selStart + matcher.end(1)
                        sites.add(
                            ClassNameSite(
                                literal = file.findElementAt(absStart) ?: file,
                                value = value,
                                startOffset = absStart - 1, // 包含引号
                                endOffset = absEnd + 1
                            )
                        )
                    }
                }
            }
        }

        // 执行替换
        if (sites.isEmpty()) return

        WriteCommandAction.writeCommandAction(project, file)
            .withName("Convert className to CSS Module")
            .run<Nothing> {
                val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return@run
                // 从后往前替换，避免 offset 漂移
                val sortedSites = sites.sortedByDescending { it.startOffset }
                for (site in sortedSites) {
                    val parts = site.value.split(CLASS_NAME_SPLIT).filter { it.isNotBlank() }
                    val matched = parts.filter { it in classNames }

                    if (matched.size == 1) {
                        // className="foo" → className={styles.foo}
                        val name = matched.first()
                        val kebab = NamingUtil.camelToKebab(name)
                        val access = if (kebab.contains("-")) {
                            "$importBinding[\"$kebab\"]"
                        } else {
                            "$importBinding.$name"
                        }
                        // 替换整个属性值部分
                        val attrStart = findAttributeStart(file, site.startOffset)
                        val attrEnd = findAttributeEnd(file, site.endOffset)
                        if (attrStart >= 0 && attrEnd > attrStart) {
                            val attrText = document.getText(com.intellij.openapi.util.TextRange(attrStart, attrEnd))
                            val newAttr = attrText.replaceFirst(
                                Regex("""["'].*?["']"""),
                                "{$access}"
                            )
                            document.replaceString(attrStart, attrEnd, newAttr)
                        }
                    } else {
                        // 多个 class：className="foo bar" → className={clsx(styles.foo, styles.bar)}
                        val accessParts = matched.map { name ->
                            val kebab = NamingUtil.camelToKebab(name)
                            if (kebab.contains("-")) {
                                "$importBinding[\"$kebab\"]"
                            } else {
                                "$importBinding.$name"
                            }
                        }
                        val clsxArgs = accessParts.joinToString(", ")
                        val attrStart = findAttributeStart(file, site.startOffset)
                        val attrEnd = findAttributeEnd(file, site.endOffset)
                        if (attrStart >= 0 && attrEnd > attrStart) {
                            val attrText = document.getText(com.intellij.openapi.util.TextRange(attrStart, attrEnd))
                            val newAttr = attrText.replaceFirst(
                                Regex("""["'].*?["']"""),
                                "{clsx($clsxArgs)}"
                            )
                            document.replaceString(attrStart, attrEnd, newAttr)
                        }
                    }
                }
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
    }

    private fun findAttributeStart(file: PsiFile, literalStart: Int): Int {
        val text = file.text
        var pos = literalStart - 1
        while (pos >= 0 && text[pos] != '=' && text[pos] != ' ' && text[pos] != '\t' && text[pos] != '\n') {
            pos--
        }
        // 找到 className 的开头
        while (pos >= 0 && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n')) {
            pos--
        }
        // 从 = 往前找属性名
        val eqPos = text.indexOf('=', pos)
        if (eqPos < 0) return -1
        // 找到 className 的开头
        var nameStart = eqPos - 1
        while (nameStart >= 0 && text[nameStart] != ' ' && text[nameStart] != '\t' && text[nameStart] != '\n' && text[nameStart] != '<') {
            nameStart--
        }
        return nameStart + 1
    }

    private fun findAttributeEnd(file: PsiFile, literalEnd: Int): Int {
        val text = file.text
        var pos = literalEnd
        // 跳过引号闭合
        if (pos < text.length && (text[pos] == '"' || text[pos] == '\'')) pos++
        // 跳过 > 或 / 或空格
        return pos
    }

    // ================================================================
    // 步骤 6：在 CSS Module 文件中追加规则
    // ================================================================

    private fun appendCssRules(project: Project, moduleVf: VirtualFile, classNames: Set<String>) {
        WriteCommandAction.writeCommandAction(project)
            .withName("Add CSS Module rules")
            .run<Nothing> {
                val psiFile = PsiManager.getInstance(project).findFile(moduleVf) ?: return@run
                val document = FileDocumentManager.getInstance().getDocument(moduleVf) ?: return@run

                val sb = StringBuilder()
                // 如果文件已有内容，先加空行
                if (document.textLength > 0 && !document.text.endsWith("\n")) {
                    sb.append("\n")
                }

                for (name in classNames) {
                    val kebab = if (name.contains("-")) name else NamingUtil.camelToKebab(name)
                    sb.append("\n.$kebab {\n\n}\n")
                }

                val insertOffset = document.textLength
                document.insertString(insertOffset, sb.toString())
                FileDocumentManager.getInstance().saveDocument(document)
            }
    }
}