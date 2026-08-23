import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("dev.evestaticmapplanner.mcp.MainKt")
}

dependencies {
    implementation(project(":control-transport"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.kotlin.server) {
        // The bridge is stdio-only. The official server artifact also publishes
        // optional Ktor HTTP/SSE/WebSocket dependencies, which are deliberately
        // kept off this module's production classpath.
        exclude(group = "io.ktor")
    }
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    testImplementation(project(":control"))
    testImplementation(project(":core"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mcp.kotlin.client)
    testImplementation(libs.mcp.kotlin.testing)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

val launcherName = "EVE Map MCP Bridge"
val launcherRuntimeModules = listOf(
    "java.base",
    "java.instrument",
    "java.logging",
    "java.net.http",
    "jdk.httpserver",
    "jdk.unsupported",
)
val launcherOutputDirectory = layout.buildDirectory.dir("launcher")
val launcherImageDirectory = launcherOutputDirectory.map { it.dir(launcherName) }
val installedLibraries = layout.buildDirectory.dir("install/${project.name}/lib")
val mainJarName = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
val launcherJdk = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

val createLauncher by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Creates the self-contained Windows stdio MCP launcher app-image."
    dependsOn(tasks.named<Sync>("installDist"))
    inputs.dir(installedLibraries)
    inputs.property("launcherName", launcherName)
    inputs.property("applicationVersion", project.version.toString())
    inputs.property("mainClass", application.mainClass)
    inputs.property("runtimeModules", launcherRuntimeModules)
    outputs.dir(launcherImageDirectory)

    doFirst {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "The MCP launcher can only be created on Windows."
        }
        val outputRoot = launcherOutputDirectory.get().asFile.canonicalFile
        val buildRoot = layout.buildDirectory.get().asFile.canonicalFile
        check(outputRoot != buildRoot && outputRoot.toPath().startsWith(buildRoot.toPath())) {
            "Refusing to replace launcher output outside the mcp build directory: $outputRoot"
        }
        project.delete(outputRoot)
        check(outputRoot.mkdirs()) { "Could not create MCP launcher output directory: $outputRoot" }

        val jpackage = launcherJdk.get().metadata.installationPath.file("bin/jpackage.exe").asFile
        check(jpackage.isFile) { "jpackage.exe is unavailable in the JDK 25 toolchain: $jpackage" }
        commandLine(
            jpackage.absolutePath,
            "--type", "app-image",
            "--input", installedLibraries.get().asFile.absolutePath,
            "--dest", outputRoot.absolutePath,
            "--name", launcherName,
            "--main-jar", mainJarName.get(),
            "--main-class", application.mainClass.get(),
            "--app-version", project.version.toString(),
            "--description", "Secure stdio bridge for EVE Static Map Planner AI Map Control.",
            "--vendor", "Static Map Planner Project",
            "--add-modules", launcherRuntimeModules.joinToString(","),
            "--win-console",
        )
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(createLauncher)
    systemProperty(
        "eve.mcp.launcher.path",
        launcherImageDirectory.map { it.file("$launcherName.exe").asFile.absolutePath }.get(),
    )
}
