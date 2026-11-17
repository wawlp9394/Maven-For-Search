import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.mavensearch"
version = "1.0.9"

// 仓库配置: 阿里云镜像加速 + Maven Central + IntelliJ Platform 仓库
repositories {
    maven("https://maven.aliyun.com/repository/public")
    maven("https://maven.aliyun.com/repository/gradle-plugin")
    mavenCentral()
    gradlePluginPortal()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA Community 2024.2 (最低兼容版本, 保证向下兼容 2024.2+)
        create("IC", "2024.2")
        // 插件签名工具
        pluginVerifier()
        // 测试框架
        testFramework(TestFrameworkType.Platform)
    }
    // Gson 用于 JSON 解析 (IntelliJ Platform 内置,但显式声明确保编译可用)
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

// 构建配置
tasks {
    // 编译字符编码
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    // 插件打包配置
    patchPluginXml {
        // 向下兼容: 最低支持 2024.2 (build 242)
        sinceBuild.set("242")
        // 向上不设上限: 显式置空以移除 until-build 属性,
        // 避免生成 until-build="" 导致 IDEA 2026.3 等新版本判定兼容范围异常
        untilBuild.set(provider { null })
    }

    // 插件验证
    verifyPlugin {
        // 跳过签名验证 (开发阶段)
    }

    test {
        useJUnitPlatform()
    }
}
