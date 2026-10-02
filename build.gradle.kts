import com.github.jengelman.gradle.plugins.shadow.ShadowApplicationPlugin.SHADOW_INSTALL_TASK_NAME
import com.github.jengelman.gradle.plugins.shadow.ShadowApplicationPlugin.SHADOW_SCRIPTS_TASK_NAME
import org.jetbrains.kotlin.incremental.deleteRecursivelyOrThrow

plugins {
    id("overwatcheat-kotlin-project")

    application

    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "org.jire.overwatcheat"
version = "5.1.0"

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(19))
    }
}

dependencies {
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    implementation(libs.fastutil)
    implementation(libs.javacv.platform)

    implementation(libs.affinity)
    implementation(libs.chronicle.core)

    implementation(libs.jna)
    implementation(libs.jna.platform)
}

application {
    applicationName = "Overwatcheat"
    mainClass.set("org.jire.overwatcheat.Main")
    applicationDefaultJvmArgs += arrayOf(
        "-Xmx4g",
        "-Xms1g",

        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseZGC",

        "--enable-native-access=ALL-UNNAMED",

        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.time=ALL-UNNAMED",

        "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED"
    )
}

tasks {
    configureShadowJar()
    configureOverwatcheat()
}

fun TaskContainerScope.configureShadowJar() {
    shadowJar {
        archiveBaseName.set("overwatcheat")
        archiveClassifier.set("")
        archiveVersion.set("")

        isZip64 = true
        //minimize() // needs to be updated for Java 19 support

        // ==========================================
        // 1. 自动瘦身：排除非 Windows 64 位的平台库
        // ==========================================
        exclude("org/bytedeco/**/linux*/**")
        exclude("org/bytedeco/**/macosx*/**")
        exclude("org/bytedeco/**/android*/**")
        exclude("org/bytedeco/**/ios*/**")
        exclude("org/bytedeco/**/windows-x86/**")    // 排除32位Windows
        exclude("org/bytedeco/**/windows-arm*/**")   // 排除ARM架构Windows

        // ==========================================
        // 2. 极限瘦身：排除当前项目用不到的庞大组件
        // ==========================================
        exclude("org/bytedeco/opencv/**")      // 排除 OpenCV (计算机视觉库极其庞大)
        exclude("org/bytedeco/openblas/**")    // 排除 OpenBLAS (底层矩阵数学库)
        exclude("org/bytedeco/tesseract/**")   // 排除 OCR 文字识别库
        exclude("org/bytedeco/leptonica/**")   // 排除图像处理辅助库
        exclude("org/bytedeco/videoinput/**")  // 排除无用的视频输入支持
        exclude("org/bytedeco/hdf5/**")        // 排除 HDF5 数据存储库
    }
    named<Zip>("distZip").configure {
        enabled = false
    }
    named<Tar>("distTar").configure {
        enabled = false
    }
    named<CreateStartScripts>("startScripts").configure {
        enabled = false
    }
    named<CreateStartScripts>(SHADOW_SCRIPTS_TASK_NAME).configure {
        enabled = false
    }
    named(SHADOW_INSTALL_TASK_NAME).configure {
        enabled = false
    }
    named("shadowDistTar").configure {
        enabled = false
    }
    named("shadowDistZip").configure {
        enabled = false
    }
}

fun TaskContainerScope.configureOverwatcheat() {
    register("overwatcheat") {
        dependsOn(shadowJar)
        doLast {
            val name = "overwatcheat"

            val buildDir = file("build/")

            val dir = buildDir.resolve(name)
            if (dir.exists()) dir.deleteRecursivelyOrThrow()
            dir.mkdirs()

            val jarName = "${name}.jar"
            val jar = dir.resolve(jarName)
            val allJar = buildDir.resolve("libs/overwatcheat.jar")
            allJar.copyTo(jar, true)

            dir.writeStartBat(name, jarName)

            fun File.copyFromRoot(path: String) = file(path).copyTo(resolve(path), true)

            dir.copyFromRoot("overwatcheat.cfg")
            dir.copyFromRoot("LICENSE.txt")
            dir.copyFromRoot("README.md")
        }
    }
}

fun File.writeStartBat(name: String, jarName: String) =
    resolve("run.bat")
        .writeText(
            """@echo off
cd /d "%~dp0"
title $name
java ${application.applicationDefaultJvmArgs.joinToString(" ")} -jar "$jarName"
pause"""
        )