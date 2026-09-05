// 仓库/镜像策略由 `_local_init.gradle.kts`（--init-script 注入）统一管理：
//  - pluginManagement / allprojects.repositories 的 mavenCentral 与 GPP URL 自动改写成腾讯镜像
//  - 追加 JetBrains releases/snapshots 专用仓（专用于 bundledPlugin 与 webstorm SDK）
// 因此 build.gradle.kts 里只写常规的 repositories 占位（mavenCentral + intellijPlatform.defaultRepositories）
// 注：Gradle Kotlin DSL 里 `java` 会解析成 JavaPluginExtension 访问器，遮蔽 java.* 包名，
//     所以文件顶部必须显式 import 需要的 JDK 类。
import java.io.IOException
import java.nio.file.Files

plugins {
    id("java")
    // WebStorm-2025.3 自身是 Kotlin 2.2 metadata。plugin 端使用 2.1.0 编译器，
    // 配合 freeCompilerArgs `-Xsuppress-version-warnings` 容忍更老的 meta 读入。
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "com.pan"
version = "1.3.1"

repositories {
    // 这两个声明会被 init-script 里的 URL 改写落到腾讯镜像
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        webstorm("2025.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("JavaScript")
        // CSS / Vue bundled plugins：与 plugin.xml 的 <depends> 对齐，
        // 既保证编译 classpath 里有 CssRuleset / Vue SFC 等类，
        // 也保证 buildPlugin 阶段把 plugin.xml 里写的模块 ID 校验通过（不会提示缺模块）。
        bundledPlugin("com.intellij.css")
        // Vue：plugin.xml 里是 <depends optional="true">，但测试沙箱必须真实装上，
        // 否则 .vue 解析为 PsiPlainTextFileImpl，VueFile/模板表达式等 PSI 全部不可用。
        bundledPlugin("org.jetbrains.plugins.vue")
        // PostCSS：Vue 插件的 intellij.vuejs.backend 模块（VueFileType/VueParserDefinition 所在 jar）
        // 依赖 org.intellij.plugins.postcss，缺了它该模块被禁用，.vue 仍会解析为纯文本
        //（沙箱日志证据："Module intellij.vuejs.backend is not enabled because dependency
        //  org.intellij.plugins.postcss is not available"）。
        bundledPlugin("org.intellij.plugins.postcss")
        // Angular：发行版目录 plugins/angular，插件 ID 历史上一直叫 "AngularJS"。
        // 它对 css/tslint 的 depends 都是 optional，引入干净；测试沙箱带上后
        // @Component 装饰器、Angular 模板 PSI（AngularHtmlFile 等）能力可用。
        bundledPlugin("AngularJS")
        // LESS：DashStyleDocumentationProvider 对 LESS 语言注册了悬停文档（CSS Module 的 .module.less
        // 里 mixin 调用展开等），测试沙箱必须带上 LESS 语言支持，否则 .less 解析为纯文本。
        bundledPlugin("org.jetbrains.plugins.less")
    }

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.0")
    // ============= IDE 集成测试依赖（JUnit4/5 双栈兼容） =============
    // BasePlatformTestCase / LightPlatformCodeInsightFixtureTestCase 是 JUnit4 风格；
    // Gradle 这边已经 useJUnitPlatform()（JUnit5 platform），所以需要 vintage 引擎
    // 来桥接 JUnit4 的 @Test，否则 JUnit4 风格的集成测试永远不会被执行。
    // 注意：不要再加 kotlin-test-junit，因为它和 kotlin-test-junit5 同时声明了
    //   capability 'org.jetbrains.kotlin:kotlin-test-framework-impl'，Gradle 会报
    //   "Cannot select module with conflict on capability"。
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

// 关键：测试运行期必须用 WebStorm-2025.3 自带的 Kotlin 2.2 stdlib，而不是插件端的 2.1.0。
// 若把插件 2.1.0 的 kotlin-stdlib 放到 test runtime 最前面，IDE/平台代码（按 2.2 编译）
// 调用 SequencesKt.sequenceOf(Object) 等 2.2 新增/变更签名时就会 NoSuchMethodError，
// 整条集成测试被 TestLogger 当成失败（fixed 的 kotlin.stdlib.default.dependency=true 会把它带上）。
// test 编译 classpath 仍保留 stdlib（Kotlin 测试代码需要），只在 runtime 排除，改由 IDE 提供。
configurations {
    testRuntimeClasspath {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }
        changeNotes = """
<h3>1.3.1</h3>
<ul>
    <li>🔧 修复 <code>CreateMissingCssClassIntention</code>（缺失类快速创建）在 Intention 预览触发下可能出现的死锁：通过声明 <code>startInWriteAction = true</code>，让 <code>invoke()</code> 由框架在写锁内调用，避免 preview 的读锁与写锁竞争导致 UI 卡死</li>
    <li>📝 同步插件市场描述、README、FEATURES 说明到最新功能状态（补齐 member access、Inline 抽取、批量迁移等）</li>
</ul>
<h3>1.3.0</h3>
<ul>
    <li>🗑️ 移除 Flex/Grid 布局可视化预览（gutter LineMarker + 交互弹窗）及对应测试</li>
    <li>🗑️ 移除阴影预览（box-shadow / text-shadow gutter 预览）及对应测试</li>
    <li>🗑️ 移除全局 HighlightVisitor（StaticGlobalHighlightVisitor），避免干扰 TypeScript/JavaScript 高亮</li>
    <li>🔧 Java target 升级至 21；修复选择器展开缓存的失效范围，外部修改文件后不再被覆盖</li>
</ul>
    """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        // 本机/Gradle 用 JDK 17（见 gradle.properties 的 org.gradle.java.home），
        // jvm target 必须 ≤ 运行 JDK，否则 javac 报 "invalid source release: 21"。
        // 插件运行在 WebStorm 的 JBR 21 上，17 字节码向下兼容，不影响功能。
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            // -Xsuppress-version-warnings：容忍 IDE SDK 中 Kotlin 2.2 metadata 与插件 2.1.0 编译器的
            //   meta 版本差，仅降级为 warning 而不是 error。
            freeCompilerArgs = freeCompilerArgs + listOf(
                "-Xjvm-default=all",
                "-Xfriend-paths=classes/java/main",
                "-Xsuppress-version-warnings"
            )
        }
    }

    publishPlugin {
        token.set(providers.gradleProperty("intellijPublishToken"))
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }

    // DashStyle 插件目前没有需要搜索的 UI 选项页。buildSearchableOptions 任务需要起一个 headless IDE
    // 进程做索引，在内存有限的容器里非常容易 crash（JBR exit code 16 = SIGABRT）。
    // 这里直接禁用不影响插件功能：用户安装后在设置搜索框里搜不到 DashStyle 独有 option 而已
    // （DashStyle 目前也没有 configurable 页）。
    buildSearchableOptions {
        enabled = false
    }
}

// ============ Vue LSP 单测布局补丁（幂等，test 前自动执行） ============
// 背景：沙箱装上 vue 插件后，VFS 监听器/意图查询会触发 VueServicesKt.<clinit>：
//   PackageVersion.bundled(VueLspServerPackageDescriptor, "2.2.10",
//       pluginPath = "vuejs/vuejs-backend",                 ← JetBrains 源码仓布局名
//       localPath = "vue-language-tools/language-server/2.2.10")
// 单测模式下 JSPluginPathManager 会先拼 <ideaHome>/plugins/vuejs/vuejs-backend/...
// （存在即成功）；但发行版目录叫 plugins/vuejs-plugin，没有 plugins/vuejs/ 这层别名，
// 路径不存在时回退到 PluginManagerCoreKt.getPluginDistDirByClass()，后者要求 jar
// 直接位于 <plugin>/lib/ 下，而 intellij.vuejs.backend.jar 在 lib/modules/ 下 →
//   IllegalStateException: ".../vuejs-plugin/lib/modules should be lib directory"
// 该异常在 <clinit> 里发生：要么裸传播进测试方法（ExceptionInInitializerError），
// 要么在 EDT 被 TestLoggerFactory 记录并在 tearDown 补抛（TestLoggerAssertionError），
// LoggedErrorProcessor 过滤器拦不住后者 —— 唯一治本方案是让路径解析成功。
// 方案：在本地发行版里创建源码布局别名 plugins/vuejs/vuejs-backend -> plugins/vuejs-plugin
//（发行版里真实存在 vue-language-tools/language-server/2.2.10/bin/vue-language-server.js，
//  别名命中后 clinit 正常返回 BundledVersion，不再走抛 ISE 的回退分支）。
val patchVueLspPluginLayout by tasks.registering {
    doLast {
        val distDir = tasks
            .named<Test>("test").get()
            .classpath
            .mapNotNull { file ->
                val f = file.canonicalFile
                // app.jar（平台核心）位于 <dist>/lib/ 下，由它反推发行版根目录
                if (f.isFile && f.name == "app.jar" && f.parentFile?.name == "lib") f.parentFile?.parentFile else null
            }
            .firstOrNull()
            ?: error("无法从 test classpath 定位 IDE 发行版（lib/app.jar）")
        val alias = distDir.resolve("plugins/vuejs/vuejs-backend")
        if (alias.exists()) {
            logger.lifecycle("Vue LSP 源码布局别名已存在，跳过: $alias")
            return@doLast
        }
        val target = distDir.resolve("plugins/vuejs-plugin")
        require(target.isDirectory) { "发行版里找不到 plugins/vuejs-plugin: $target" }
        distDir.resolve("plugins/vuejs").mkdirs()
        try {
            Files.createSymbolicLink(alias.toPath(), target.toPath())
            logger.lifecycle("已创建 Vue LSP 源码布局别名(软链): $alias -> $target")
        } catch (e: IOException) {
            // Windows 无符号链接权限等场景：退化为只建空目录 —— 对 clinit 而言
            // Files.exists(...) 为真即可通过（BundledVersion 仅记录路径字符串）
            alias.mkdirs()
            logger.lifecycle("符号链接创建失败(${e.message})，已退化为空目录: $alias")
        }
    }
}
tasks.named("test") { dependsOn(patchVueLspPluginLayout) }
