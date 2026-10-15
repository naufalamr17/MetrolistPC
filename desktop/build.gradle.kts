import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.awt.image.BufferedImage
import java.awt.RenderingHints
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

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

val windowsIcon = layout.buildDirectory.file("windows-icon/metrolist.ico")
val prepareWindowsIcon by tasks.registering {
    val original = file("../fastlane/metadata/android/en-US/images/icon.png")
    inputs.file(original)
    outputs.file(windowsIcon)
    doLast {
        val source = ImageIO.read(original)
        val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
        val frames = sizes.map { size ->
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(source, 0, 0, size, size, null)
            graphics.dispose()
            ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }
        val header = ByteBuffer.allocate(6 + sizes.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(sizes.size.toShort())
        var offset = header.capacity()
        sizes.forEachIndexed { index, size ->
            header.put((size % 256).toByte()).put((size % 256).toByte()).put(0).put(0)
                .putShort(1).putShort(32).putInt(frames[index].size).putInt(offset)
            offset += frames[index].size
        }
        windowsIcon.get().asFile.apply { parentFile.mkdirs() }.outputStream().use { output ->
            output.write(header.array()); frames.forEach(output::write)
        }
    }
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
                iconFile.set(windowsIcon)
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
    dependsOn(prepareWindowsIcon)
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
