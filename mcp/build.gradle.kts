import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import java.nio.charset.StandardCharsets

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
val generatedBuildInfoDirectory = layout.buildDirectory.dir("generated/resources/buildInfo")
val generateMcpBuildInfo by tasks.registering {
    inputs.property("applicationVersion", project.version.toString())
    outputs.dir(generatedBuildInfoDirectory)
    doLast {
        val output = generatedBuildInfoDirectory.get().file("mcp-build.properties").asFile
        output.parentFile.mkdirs()
        output.writeText("version=${project.version}\n", Charsets.UTF_8)
    }
}

sourceSets.named("main") {
    resources.srcDir(generatedBuildInfoDirectory)
}

tasks.processResources {
    dependsOn(generateMcpBuildInfo)
    from(rootProject.files("NOTICE.md", "THIRD-PARTY-NOTICES.md")) {
        into("legal")
    }
    from(rootProject.layout.projectDirectory.dir("legal")) {
        into("legal")
    }
}

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
val nativeOutputDir = providers.gradleProperty("nativeOutputDir").orNull
val integratedApplicationImage = nativeOutputDir
    ?.let(rootProject::file)
    ?.resolve("compose/main/integrated-app/EVE Static Map Planner")
    ?: project(":app").layout.buildDirectory.dir(
        "compose/binaries/main/integrated-app/EVE Static Map Planner",
    ).get().asFile

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
    systemProperty("eve.mcp.expectedVersion", project.version.toString())
    systemProperty(
        "eve.mcp.launcher.path",
        launcherImageDirectory.map { it.file("$launcherName.exe").asFile.absolutePath }.get(),
    )
}

val analyzeProductionRuntimeModules by tasks.registering {
    group = "verification"
    description = "Runs jdeps against the production-only MCP bridge classpath."
    dependsOn(tasks.named<Sync>("installDist"))
    inputs.dir(installedLibraries)
    val report = layout.buildDirectory.file("reports/step3c/mcp-jdeps-modules.txt")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        val libraries = installedLibraries.get().asFile.listFiles()
            ?.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        val mainJar = libraries.singleOrNull { it.name == "mcp-${project.version}.jar" }
            ?: error("Expected exactly one versioned MCP main jar")
        val dependencyClasspath = libraries.filterNot { it == mainJar }.joinToString(File.pathSeparator)
        val jdeps = launcherJdk.get().metadata.installationPath.file("bin/jdeps.exe").asFile
        check(jdeps.isFile) { "jdeps.exe is unavailable in the JDK 25 toolchain: $jdeps" }
        val process = ProcessBuilder(
            jdeps.absolutePath,
            "--ignore-missing-deps",
            "--multi-release", "25",
            "--print-module-deps",
            "--class-path", dependencyClasspath,
            mainJar.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
        check(process.waitFor() == 0) { "jdeps failed for the MCP production classpath: $output" }
        val modules = output.split(',').map(String::trim).filter(String::isNotBlank).toSortedSet()
        val required = setOf("java.base", "java.instrument", "java.logging", "java.net.http", "jdk.unsupported")
        check(modules.containsAll(required)) { "MCP jdeps result is missing expected modules: ${required - modules}" }
        val outputFile = report.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(modules.joinToString(",") + System.lineSeparator(), Charsets.UTF_8)
        logger.lifecycle("MCP production jdeps modules: ${modules.joinToString(",")}")
    }
}

val installedImageTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs MCP process tests against the final shared-runtime Windows app-image launcher."
    dependsOn(":app:createIntegratedDistributable")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("dev.evestaticmapplanner.mcp.McpProcessTest")
    systemProperty(
        "eve.mcp.launcher.path",
        integratedApplicationImage.resolve("EVE Map MCP Bridge.exe").absolutePath,
    )
    systemProperty(
        "eve.mcp.stable.launcher.path",
        integratedApplicationImage.resolve("eve-map-mcp.exe").absolutePath,
    )
    val pathEnvironmentKey = System.getenv().keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "Path"
    environment(
        pathEnvironmentKey,
        integratedApplicationImage.absolutePath + ";" + System.getenv(pathEnvironmentKey).orEmpty(),
    )
    inputs.file(integratedApplicationImage.resolve("EVE Map MCP Bridge.exe"))
    inputs.file(integratedApplicationImage.resolve("eve-map-mcp.exe"))
    outputs.upToDateWhen { false }
}

val portableInstalledImageTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs MCP process tests against the launcher extracted from the final Portable ZIP."
    dependsOn(":app:verifyPortableZip")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("dev.evestaticmapplanner.mcp.McpProcessTest")
    val portableImage = rootProject.layout.buildDirectory.dir(
        "portable-acceptance/Portable QA With Spaces/EVE Static Map Planner",
    )
    doFirst {
        systemProperty(
            "eve.mcp.launcher.path",
            portableImage.get().file("EVE Map MCP Bridge.exe").asFile.absolutePath,
        )
        systemProperty(
            "eve.mcp.stable.launcher.path",
            portableImage.get().file("eve-map-mcp.exe").asFile.absolutePath,
        )
        val pathEnvironmentKey = System.getenv().keys.firstOrNull {
            it.equals("PATH", ignoreCase = true)
        } ?: "Path"
        environment(
            pathEnvironmentKey,
            portableImage.get().asFile.absolutePath + ";" + System.getenv(pathEnvironmentKey).orEmpty(),
        )
    }
    inputs.dir(portableImage)
    outputs.upToDateWhen { false }
}

val portableLocatorExternalConsumerTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Launches the Portable MCP strictly through the locator generated by the GUI."
    dependsOn(":app:verifyPortableZip")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("dev.evestaticmapplanner.mcp.McpLocatorExternalConsumerTest")
    val portableImage = rootProject.layout.buildDirectory.dir(
        "portable-acceptance/Portable QA With Spaces/EVE Static Map Planner",
    )
    doFirst {
        systemProperty("eve.mcp.locator.portable.image", portableImage.get().asFile.absolutePath)
    }
    inputs.dir(portableImage)
    outputs.upToDateWhen { false }
}
