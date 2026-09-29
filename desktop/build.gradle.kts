plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.composeMultiplatform)
}

val appBuildGeneratedDir = layout.buildDirectory.dir("generated/appbuild/kotlin")

val generateAppBuild = tasks.register("generateAppBuild") {
    val versionName = providers.gradleProperty("irio.versionName").get()
    val versionCode = providers.gradleProperty("irio.versionCode").get().toLong()
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    outputs.dir(appBuildGeneratedDir)
    doLast {
        val dir = appBuildGeneratedDir.get().asFile.resolve("com/irio")
        dir.mkdirs()
        val escapedName = versionName.replace("\\", "\\\\").replace("\"", "\\\"")
        dir.resolve("DesktopAppBuild.kt").writeText(
            """
            package com.irio

            internal object DesktopAppBuild {
                const val versionName = "$escapedName"
                const val versionCode = ${versionCode}L
                const val buildType = "debug"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") {
        kotlin.srcDir(appBuildGeneratedDir)
    }
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
    dependsOn(generateAppBuild)
}

dependencies {
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("com.composables:ui:0.2.0")
    implementation("com.composables:composeunstyled-slider:2.7.0")
    implementation("dev.chrisbanes.haze:haze:2.0.0-rc02")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.0-rc02")
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.45.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.45.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:0.45.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.0")
    implementation("com.squareup.okhttp3:okhttp:${libs.versions.okhttp.get()}")

    // 图片预览：双击/双指缩放（PhotoViewer）
    implementation("me.saket.telephoto:zoomable:0.19.0")

    // LaTeX
    implementation("org.scilab.forge:jlatexmath:1.0.7")
    implementation("org.json:json:20260814")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

compose.desktop {
    application {
        mainClass = "com.irio.MainKt"
    }
}
