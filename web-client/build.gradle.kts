plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val generatedLoaderResources = layout.buildDirectory.dir("generated/phase1-loader")
val generatedWebDataResources = layout.buildDirectory.dir("generated/web-data")
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
    dependsOn(syncPhase1WebLoader, stageWebPack)
}

tasks.register("webDev") {
    group = "application"
    description = "Starts the Kotlin/JS development server. Stage /data separately before launch."
    dependsOn("jsBrowserDevelopmentRun")
}

tasks.register("webProduction") {
    group = "build"
    description = "Builds the production Web client into build/dist/js/productionExecutable."
    dependsOn("jsBrowserDistribution")
}
