plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("com.composables:ui:0.2.0")
    implementation("com.composables:composeunstyled-slider:2.7.0")
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
        // OpenJDK AWT XIM on Linux does not deliver preedit into Compose or
        // place the Fcitx candidate window on the caret (JBR-8273). JBR does.
        javaHome = "${System.getProperty("user.home")}/.local/share/jbr/jbr-21.0.6-linux-x64-b895.91"
        jvmArgs(
            "-Djava.awt.im.style=on-the-spot",
        )
    }
}
