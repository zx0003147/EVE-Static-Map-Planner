plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
}

val appVersion = providers.gradleProperty("appVersion").get()
val nodeExecutableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "node.exe" else "node"
val systemNodeCommand = System.getenv("PATH")
    ?.split(File.pathSeparator)
    ?.asSequence()
    ?.map { File(it, nodeExecutableName) }
    ?.firstOrNull(File::isFile)
    ?.absolutePath
    ?: nodeExecutableName

plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin>().configureEach {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec> {
        download.set(false)
        command.set(systemNodeCommand)
    }
}

plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin>().configureEach {
    @Suppress("DEPRECATION_ERROR")
    extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension> {
        download = false
        command = systemNodeCommand
    }
}

allprojects {
    group = "dev.evestaticmapplanner"
    version = appVersion
}

tasks.named("build") {
    dependsOn(
        ":app:build",
        ":control:build",
        ":control-transport:build",
        ":core:build",
        ":data:build",
        ":feature-api:build",
        ":marker-application:build",
        ":mcp:build",
        ":sde:build",
        ":web-pack:build",
        ":web-client:build",
    )
}

tasks.register<Exec>("webLoaderTest") {
    group = "verification"
    description = "Runs the dependency-free browser Web Pack loader contract tests with Node.js."
    workingDir(layout.projectDirectory.dir("web-loader"))
    commandLine("node", "--test", "web-pack-loader.test.mjs")
}
