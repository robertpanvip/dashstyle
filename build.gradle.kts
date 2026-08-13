// 仓库/镜像策略由 `_local_init.gradle.kts`（--init-script 注入）统一管理：
//  - pluginManagement / allprojects.repositories 的 mavenCentral 与 GPP URL 自动改写成腾讯镜像
//  - 追加 JetBrains releases/snapshots 专用仓（专用于 bundledPlugin 与 webstorm SDK）
// 因此 build.gradle.kts 里只写常规的 repositories 占位（mavenCentral + intellijPlatform.defaultRepositories）
plugins {
    id("java")
    // WebStorm-2025.3 SDK 里的 kotlin-stdlib 是 Kotlin 2.2 metadata；必须用 2.0+ 的编译器才能读取。
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "com.pan"
version = "1.1.1"

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
    }

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.0.21")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }
        changeNotes = """
Initial version
    """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = freeCompilerArgs + listOf(
                "-Xjvm-default=all",
                "-Xfriend-paths=classes/java/main"
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
}
