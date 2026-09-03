package com.pan.dashstyle

import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 诊断用：启动时把 WebStorm 里所有已注册 Language 的 getID() 打印到 idea.log。
 *       这样能确认 plugin.xml 里写的 <language>TypeScript JSX</language> 是否匹配真实 id。
 */
class DashStyleLanguageDumpActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val log = Logger.getInstance("DashStyle-LangDump")
        ApplicationManager.getApplication().invokeLater {
            val all = Language.getRegisteredLanguages()
            log.info("========== DashStyle Language Dump ==========")
            for (lang in all.sortedBy { it.id }) {
                val parent = lang.baseLanguage
                log.info("  [LANG] id='${lang.id}'  display='${lang.displayName}'  class=${lang.javaClass.name}  parent=${parent?.id}")
            }
            log.info("============================================")
        }
    }
}
