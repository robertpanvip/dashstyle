// _local_init.gradle.kts —— 通过 `gradle --init-script _local_init.gradle.kts ...` 调用
//
// 说明：本脚本负责两件事
// 1) settingsEvaluated 阶段：强制把 pluginManagement 所有仓库替换为「腾讯镜像 + 官仓兜底」
// 2) allprojects 阶段：
//      - buildscript 级别 把 mavenCentral/plugins.gradle.org 的 URL 改写成腾讯镜像
//      - afterEvaluate 追加 JetBrains snapshots/releases 专用仓（idea / bundled.*）
//      - 项目级 repositories 里的 mavenCentral / GPP URL 再改写成腾讯镜像

import org.gradle.api.artifacts.repositories.MavenArtifactRepository

// ---------------- pluginManagement 走腾讯 ----------------
settingsEvaluated {
    pluginManagement {
        repositories.clear()
        repositories {
            maven {
                name = "TencentGradlePluginsInit"
                url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
            }
            maven {
                name = "TencentMavenPublicInit"
                url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
            }
            // 最后官仓兜底
            gradlePluginPortal()
            mavenCentral()
        }
    }
}

// ---------------- 所有项目级 repo 替换成腾讯 + JetBrains 专用仓 ----------------
allprojects {
    buildscript {
        repositories.withType<MavenArtifactRepository>().configureEach repo@{
            val u = this.url.toString()
            when {
                u.startsWith("https://repo.maven.apache.org/maven2") ->
                    this.setUrl("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
                u.startsWith("https://plugins.gradle.org/m2") ->
                    this.setUrl("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
            }
        }
    }

    afterEvaluate {
        // + 专门加一个 JetBrains snapshots 仓，只服务 com.jetbrains.intellij.* / bundled.* 组
        repositories {
            maven {
                name = "JetbrainsSnapshotsInit"
                url = uri("https://www.jetbrains.com/intellij-repository/snapshots")
                content {
                    includeGroupByRegex("""com\.jetbrains\.intellij.*""")
                    includeGroupByRegex("""com\.jetbrains.*""")
                    includeGroupByRegex("""org\.jetbrains\.intellij.*""")
                    includeGroupByRegex("""bundled.*""")
                }
            }
            maven {
                name = "JetbrainsReleasesInit"
                url = uri("https://www.jetbrains.com/intellij-repository/releases")
                content {
                    includeGroupByRegex("""com\.jetbrains\.intellij.*""")
                    includeGroupByRegex("""com\.jetbrains.*""")
                    includeGroupByRegex("""org\.jetbrains\.intellij.*""")
                    includeGroupByRegex("""bundled.*""")
                }
            }
        }
        // 最后再把项目级 mavenCentral / GPP 的 URL 替换一次
        repositories.withType<MavenArtifactRepository>().configureEach repo@{
            val u = this.url.toString()
            when {
                u.startsWith("https://repo.maven.apache.org/maven2") ->
                    this.setUrl("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
                u.startsWith("https://plugins.gradle.org/m2") ->
                    this.setUrl("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
            }
        }
    }
}
