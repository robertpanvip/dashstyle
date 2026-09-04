// =====================================================================
// _local_init.gradle.kts  —— 构建引导脚本
// 用法：gradle --init-script _local_init.gradle.kts <task>
// =====================================================================
//
// 构建经验总结（跑不起来时先看这里）：
//
// 1. JDK 版本坑
//    - 必须用 JDK 17，不能用 JDK 25+。
//      原因：Kotlin 2.1.0 自带的 JavaVersion.parse 解析 java.version 字符串时
//      不识别 Java 25 的 "25.0.2"，直接 IllegalArgumentException 崩溃。
//    - 在 gradle.properties 中通过 org.gradle.java.home 锁定 JDK 17 路径。
//    - 本地开发：mise 安装 17.0.2，路径固定。
//    - GitHub Actions：actions/setup-java 安装 temurin-17，在 CI 中 sed 替换路径。
//
// 2. 代理配置
//    - 本地开发需要代理时，通过 GRADLE_OPTS 注入（不要提交到仓库）：
//      export GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 \
//        -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080"
//    - 或在命令行显式设置：
//      GRADLE_OPTS="..." gradle --init-script _local_init.gradle.kts build
//    - 注意：GitHub Actions 海外机器不需要代理，不要把代理配置提交到仓库。
//
// 3. 仓库镜像（本脚本已处理）
//    - 腾讯镜像自动替换 mavenCentral / Gradle Plugin Portal。
//    - 追加 JetBrains releases/snapshots 专用仓（webstorm SDK / bundledPlugin）。
//    - 无需额外配置，直接使用即可。
//
// 4. 常见错误与解决
//    - "Could not find kotlin-gradle-plugin": 镜像没刷到最新版，加官仓兜底。
//    - "JavaVersion.parse IllegalArgumentException": JDK 版本 > 17，换成 17。
//    - "Connection refused / timeout": 本地开发需要代理，设置 GRADLE_OPTS。
//    - "buildSearchableOptions 崩溃": 已禁用该 task（见 build.gradle.kts）。
//    - "NoSuchMethodError in test": test runtime 排除插件端 kotlin-stdlib，
//      由 IDE 提供的 Kotlin 2.2 stdlib 提供（见 build.gradle.kts）。
//
// 5. 完整构建命令参考
//    # 本地（有代理）
//    GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 \
//      -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080" \
//      gradle --init-script _local_init.gradle.kts build
//
//    # 本地（无代理）
//    gradle --init-script _local_init.gradle.kts build
//
//    # 仅编译
//    gradle --init-script _local_init.gradle.kts compileKotlin
//
//    # 运行测试
//    gradle --init-script _local_init.gradle.kts test
//
// =====================================================================
// 本脚本功能说明：
// 1) settingsEvaluated 阶段：强制把 pluginManagement 所有仓库替换为「腾讯镜像 + 官仓兜底」
// 2) allprojects 阶段：
//      - buildscript 级别 把 mavenCentral/plugins.gradle.org 的 URL 改写成腾讯镜像
//      - afterEvaluate 追加 JetBrains snapshots/releases 专用仓（idea / bundled.*）
//      - 项目级 repositories 里的 mavenCentral / GPP URL 再改写成腾讯镜像
// =====================================================================

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
