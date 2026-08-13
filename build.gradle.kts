plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "com.pan"
version = "1.1.1"

repositories {
    // 用户要求：腾讯镜像第一优先
    maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
    maven { url = uri("https://mirrors.cloud.tencent.com/gradle/") }
    // 备用：阿里云（腾讯同步不完整时回退）
    maven { url = uri("https://maven.aliyun.com/repository/public/") }
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

    // 单元测试依赖
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.24")
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
            // 默认 jvm-default=all + 测试模块的 friend-path（让 test 访问 internal）
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
