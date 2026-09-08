import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val generatedLoaderResources = layout.buildDirectory.dir("generated/phase1-loader")
val generatedWebDataResources = layout.buildDirectory.dir("generated/web-data")
val generatedPwaIconResources = layout.buildDirectory.dir("generated/pwa-icons")
val syncPhase1WebLoader by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.file("web-loader/web-pack-loader.mjs"))
    into(generatedLoaderResources)
}
val stageWebPack by tasks.registering(Sync::class) {
    group = "application"
    description = "Stages the directory supplied by -PwebPackDir under the Web client's /data path."
    from(
        providers.gradleProperty("webPackDir")
            .map { rootProject.file(it) }
            .orElse(providers.provider { file("no-web-pack-selected") }),
    )
    into(generatedWebDataResources.map { it.dir("data") })
}
val generatePwaIcons by tasks.registering {
    val sourceIcon = rootProject.layout.projectDirectory.file("app/src/main/resources/icons/app-icon.png")
    inputs.file(sourceIcon)
    outputs.dir(generatedPwaIconResources)
    doLast {
        val source = ImageIO.read(sourceIcon.asFile) ?: error("Unable to read the Desktop application icon")
        val outputDirectory = generatedPwaIconResources.get().dir("icons").asFile.apply { mkdirs() }
        listOf(192, 512).forEach { size ->
            val target = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = target.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.drawImage(source, 0, 0, size, size, null)
            } finally {
                graphics.dispose()
            }
            check(ImageIO.write(target, "png", outputDirectory.resolve("app-icon-$size.png"))) {
                "Unable to generate the $size px PWA icon"
            }
        }
    }
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "web-client.js"
            }
        }
        nodejs()
        binaries.executable()
    }

    sourceSets {
        jsMain {
            resources.srcDir(generatedLoaderResources)
            resources.srcDir(generatedWebDataResources)
            resources.srcDir(generatedPwaIconResources)
        }
        jsMain.dependencies {
            implementation(project(":core"))
            implementation(project(":shared-client"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.named("jsProcessResources") {
    dependsOn(syncPhase1WebLoader, stageWebPack, generatePwaIcons)
}

tasks.register<Exec>("pwaRuntimeTest") {
    group = "verification"
    description = "Runs the dependency-free PWA update lifecycle tests with Node.js."
    workingDir(rootProject.layout.projectDirectory.dir("web-loader"))
    commandLine("node", "--test", "pwa-runtime.test.mjs")
}

tasks.register("webDev") {
    group = "application"
    description = "Starts the Kotlin/JS development server. Stage /data separately before launch."
    dependsOn("jsBrowserDevelopmentRun")
}

tasks.register("webProduction") {
    group = "build"
    description = "Builds the production Web client into build/dist/js/productionExecutable."
    dependsOn("jsBrowserDistribution", "pwaRuntimeTest")
}
