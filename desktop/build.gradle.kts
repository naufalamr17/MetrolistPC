import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
}

group = "com.metrolist"
version = "13.7.0"

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("../innertube/src/main/kotlin")
        kotlin.srcDir("../app/src/main/kotlin/com/metrolist/lrclib")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("com.github.MetrolistGroup.innertubex:innertubex-desktop:v0.8.4")
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-client-encoding:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test-junit"))
    testImplementation("io.ktor:ktor-client-mock:3.6.0")
}

val prepareAudioResources by tasks.registering(Sync::class) {
    val ffmpegRoot = file("C:/ProgramData/chocolatey/lib/ffmpeg/tools/ffmpeg")
    from(ffmpegRoot.resolve("bin/ffmpeg.exe"))
    from(ffmpegRoot.resolve("LICENSE")) { rename { "FFmpeg-LICENSE.txt" } }
    from(ffmpegRoot.resolve("README.txt")) { rename { "FFmpeg-README.txt" } }
    into(layout.buildDirectory.dir("native-resources/windows"))
    doFirst { check(ffmpegRoot.resolve("bin/ffmpeg.exe").isFile) { "The installed FFmpeg decoder was not found." } }
}

tasks.processResources {
    from("../app/src/main/res/values/metrolist_strings.xml")
    from("../fastlane/metadata/android/en-US/images/icon.png") { rename { "metrolist-icon.png" } }
    from("../LICENSE")
}

compose.desktop {
    application {
        mainClass = "com.metrolist.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "Metrolist Desktop"
            packageVersion = "13.7.0"
            description = "Educational Windows port using the Metrolist Kotlin engine"
            vendor = "Local educational port; upstream Metrolist contributors"
            licenseFile.set(project.file("../LICENSE"))
            appResourcesRootDir.set(layout.buildDirectory.dir("native-resources"))
            modules("java.desktop", "java.net.http", "java.logging", "java.xml", "jdk.crypto.ec", "jdk.unsupported", "jdk.httpserver")
            windows {
                menuGroup = "Metrolist"
                shortcut = true
                perUserInstall = true
                upgradeUuid = "25a790cf-e591-4f64-822d-392dce0d6f81"
            }
        }
    }
}

tasks.matching { it.name in setOf("createDistributable", "packageExe", "prepareAppResources") }.configureEach {
    dependsOn(prepareAudioResources)
}

tasks.register<JavaExec>("verifyLive") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.metrolist.desktop.VerifyKt")
    dependsOn(tasks.classes)
    systemProperty("metrolist.live.output", providers.gradleProperty("uiOutput").getOrElse(layout.buildDirectory.dir("ui-proof").get().asFile.absolutePath))
}

tasks.test {
    systemProperty("metrolist.test.output", providers.gradleProperty("uiOutput").getOrElse(layout.buildDirectory.dir("ui-proof").get().asFile.absolutePath))
    testLogging { events("passed", "failed", "skipped") }
}
