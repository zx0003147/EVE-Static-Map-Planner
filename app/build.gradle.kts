import dev.evestaticmapplanner.packaging.PortableDistributionAudit
import dev.evestaticmapplanner.packaging.WindowsAppImageIntegration
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.nio.charset.Charset
import java.util.jar.JarFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

fun readGitCommit(repositoryRoot: File): String = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "HEAD")
        .directory(repositoryRoot)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    if (process.waitFor() == 0 && output.matches(Regex("[0-9a-fA-F]{40}"))) output.lowercase() else "unknown"
}.getOrDefault("unknown")

fun propertyValue(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\r", "")
    .replace("\n", "\\n")

val appVersion = providers.gradleProperty("appVersion").get()
val nativeOutputDir = providers.gradleProperty("nativeOutputDir").orNull
val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val distributionNoticeFiles = files(
    rootProject.file("NOTICE.md"),
    rootProject.file("THIRD-PARTY-NOTICES.md"),
)
val distributionLegalDirectory = rootProject.layout.projectDirectory.dir("legal")
val nativeComposeOutputBase = nativeOutputDir
    ?.let(::file)
    ?.resolve("compose")
    ?: layout.buildDirectory.dir("compose/binaries").get().asFile
val composeApplicationImage = nativeComposeOutputBase.resolve("main/app/EVE Static Map Planner")
val integratedImageBuildRoot = nativeComposeOutputBase.resolve("main/integrated-app")
val integratedApplicationImage = integratedImageBuildRoot.resolve("EVE Static Map Planner")
val mcpInstalledLibraries = project(":mcp").layout.buildDirectory.dir("install/mcp/lib")
val featurePackFixture by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val sovereigntyPackJar = providers.gradleProperty("sovereigntyPackJar")
val esiPackJar = providers.gradleProperty("esiPackJar")

nativeOutputDir?.let { layout.buildDirectory.set(file(it).resolve("app-build")) }

val generatedBuildInfoDirectory = layout.buildDirectory.dir("generated/resources/buildInfo")
val gitCommit = providers.provider { readGitCommit(rootProject.projectDir) }

val generateBuildInfo by tasks.registering {
    val metadata = linkedMapOf(
        "appVersion" to appVersion,
        "gitCommit" to gitCommit.get(),
        "jdkVersion" to System.getProperty("java.version", "unknown"),
        "jdkVendor" to System.getProperty("java.vendor", "unknown"),
        "kotlinVersion" to versionCatalog.findVersion("kotlin").get().requiredVersion,
        "composeVersion" to versionCatalog.findVersion("compose").get().requiredVersion,
        "gradleVersion" to gradle.gradleVersion,
        "targetOs" to if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "Windows" else System.getProperty("os.name"),
        "targetArch" to when (System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> System.getProperty("os.arch")
        },
    )
    inputs.properties(metadata)
    outputs.dir(generatedBuildInfoDirectory)
    doLast {
        val output = generatedBuildInfoDirectory.get().file("build-info.properties").asFile
        output.parentFile.mkdirs()
        output.writeText(
            metadata.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) ->
                "$key=${propertyValue(value)}"
            },
            Charsets.UTF_8,
        )
    }
}

sourceSets.named("main") {
    resources.srcDir(generatedBuildInfoDirectory)
}

tasks.processResources {
    dependsOn(generateBuildInfo)
    from(distributionNoticeFiles) {
        into("legal")
    }
    from(distributionLegalDirectory) {
        into("legal")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":control"))
    implementation(project(":control-transport"))
    implementation(project(":data"))
    implementation(project(":feature-api"))
    implementation(project(":marker-application"))
    implementation(project(":sde"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    add(
        featurePackFixture.name,
        project(mapOf("path" to ":feature-api", "configuration" to "fixturePackElements")),
    )
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    dependsOn(featurePackFixture)
    val externalSovereigntyPackJar = sovereigntyPackJar.orNull?.takeIf(String::isNotBlank)
    val externalEsiPackJar = esiPackJar.orNull?.takeIf(String::isNotBlank)
    if (externalSovereigntyPackJar == null) {
        exclude("**/SovereigntyPackIntegrationTest.class")
    } else {
        inputs.property("sovereigntyPackJar", externalSovereigntyPackJar)
    }
    if (externalEsiPackJar == null) {
        exclude("**/EsiPackIntegrationTest.class")
    } else {
        inputs.property("esiPackJar", externalEsiPackJar)
    }
    if (externalSovereigntyPackJar == null || externalEsiPackJar == null) {
        exclude("**/ExternalFeaturePacksIntegrationTest.class")
    }
    doFirst {
        systemProperty("feature.pack.fixture.jar", featurePackFixture.singleFile.absolutePath)
        externalSovereigntyPackJar?.let { configuredPath ->
            systemProperty("sovereignty.pack.jar", file(configuredPath).absolutePath)
        }
        externalEsiPackJar?.let { configuredPath ->
            systemProperty("esi.pack.jar", file(configuredPath).absolutePath)
        }
    }
}

kotlin {
    jvmToolchain(25)
}

compose.desktop {
    application {
        mainClass = "dev.evestaticmapplanner.MainKt"
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            nativeOutputDir?.let { outputBaseDir.set(file(it).resolve("compose")) }
            packageName = "EVE Static Map Planner"
            packageVersion = appVersion
            description = "Unofficial static map and route planning tool for EVE Online."
            vendor = "Static Map Planner Project"
            modules(
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.net.http",
                "java.sql",
                "jdk.httpserver",
                "jdk.unsupported",
            )
            windows {
                iconFile.set(project.file("src/main/resources/icons/app-icon.ico"))
                console = false
            }
        }
    }
}

// Compose 1.10.0 validates its private WiX 3 directory even for an app-image.
// Portable packaging never invokes WiX; keep the unused downloader tasks disabled
// and satisfy only the plugin's directory input.
val prepareUnusedComposeWixDirectory by rootProject.tasks.registering {
    doLast {
        rootProject.layout.buildDirectory.dir("wix311").get().asFile.mkdirs()
    }
}

rootProject.tasks.matching { it.name == "downloadWix" || it.name == "unzipWix" }.configureEach {
    enabled = false
}

// Compose 1.10 creates jlink's output directory before invoking JDK 25 jlink on Windows, but jlink
// requires the output itself to be absent. Keep the official jlink image contract while replacing
// only that broken task action; downstream Compose/jpackage tasks continue to consume the same path.
tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    setActions(emptyList())
    val jlinkOutput = layout.buildDirectory.dir("compose/tmp/main/runtime")
    outputs.dir(jlinkOutput)
    doLast {
        val output = jlinkOutput.get().asFile
        val outputParent = checkNotNull(output.parentFile)
        check(outputParent.isDirectory || outputParent.mkdirs()) { "Could not create jlink work directory: $outputParent" }
        val jlink = File(System.getProperty("java.home"), "bin/jlink.exe")
        check(jlink.isFile) { "jlink.exe is unavailable in the Gradle JDK: $jlink" }
        project.delete(output)
        check(!output.exists()) { "Could not reset Compose jlink output: $output" }
        val arguments = listOf(
            jlink.absolutePath,
            "--add-modules",
            listOf(
                "java.base",
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.net.http",
                "java.sql",
                "jdk.crypto.ec",
                "jdk.httpserver",
                "jdk.unsupported",
            ).joinToString(","),
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--strip-native-commands",
            "--output",
            output.absolutePath,
        )
        val logDirectory = layout.buildDirectory.dir("compose/logs/createRuntimeImage").get().asFile
        check(logDirectory.isDirectory || logDirectory.mkdirs()) { "Could not create jlink log directory: $logDirectory" }
        val stdout = logDirectory.resolve("jlink-out.txt")
        val stderr = logDirectory.resolve("jlink-err.txt")
        val process = ProcessBuilder(arguments)
            .directory(project.projectDir)
            .redirectOutput(stdout)
            .redirectError(stderr)
            .start()
        val exitCode = process.waitFor()
        stdout.readLines().filter(String::isNotBlank).forEach(logger::lifecycle)
        stderr.readLines().filter(String::isNotBlank).forEach(logger::error)
        check(exitCode == 0) { "jlink runtime-image creation failed with exit code $exitCode" }
        check(output.resolve("lib/modules").isFile) { "jlink runtime image is incomplete: $output" }
    }
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    dependsOn(prepareUnusedComposeWixDirectory)
}

val createIntegratedDistributable by tasks.registering {
    group = "distribution"
    description = "Creates the Windows app-image with GUI and stdio MCP launchers sharing one runtime."
    dependsOn("createDistributable", ":mcp:installDist")
    inputs.dir(composeApplicationImage)
    inputs.dir(mcpInstalledLibraries)
    inputs.files(distributionNoticeFiles)
    inputs.dir(distributionLegalDirectory)
    inputs.property("applicationVersion", appVersion)
    inputs.property("mcpMainClass", WindowsAppImageIntegration.MCP_MAIN_CLASS)
    outputs.dir(integratedApplicationImage)
    outputs.upToDateWhen { false }

    doLast {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "The integrated application image can only be created on Windows."
        }
        check(System.getProperty("java.runtime.version").startsWith("25.0.4+7")) {
            "The integrated launcher contract was audited for JDK 25.0.4+7 only; " +
                "found ${System.getProperty("java.runtime.version")}."
        }
        check(composeApplicationImage.isDirectory) { "Missing Compose application image: $composeApplicationImage" }
        val composeAppDirectory = composeApplicationImage.resolve("app")
        val composeMainConfig = composeAppDirectory.resolve("EVE Static Map Planner.cfg")
        check(composeMainConfig.isFile) { "Missing Compose main launcher config: $composeMainConfig" }
        val originalMainConfig = composeMainConfig.readText(Charsets.UTF_8)
        check(WindowsAppImageIntegration.launcherMainClass(originalMainConfig) == WindowsAppImageIntegration.MAIN_CLASS) {
            "Compose main launcher class changed unexpectedly"
        }
        val mainJar = WindowsAppImageIntegration.mainJarFromComposeConfig(originalMainConfig)

        val mcpLibraryDirectory = mcpInstalledLibraries.get().asFile
        check(mcpLibraryDirectory.isDirectory) { "Missing MCP production libraries: $mcpLibraryDirectory" }
        val mcpJars = mcpLibraryDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        check(mcpJars.isNotEmpty()) { "MCP production library directory contains no jars" }
        check(mcpJars.none { it.name.contains("ktor", ignoreCase = true) }) {
            "Ktor runtime must not enter the stdio MCP production image: ${mcpJars.map(File::getName)}"
        }
        check(mcpJars.singleOrNull { it.name == "mcp-$appVersion.jar" } != null) {
            "Expected exactly one MCP main jar named mcp-$appVersion.jar"
        }

        val buildRoot = layout.buildDirectory.get().asFile.canonicalFile
        val integrationWorkRoot = layout.buildDirectory.dir("jpackage/integrated-app").get().asFile
        fun resetBuildOutput(directory: File, allowedRoot: File) {
            val target = directory.canonicalFile
            val root = allowedRoot.canonicalFile
            check(target != root && target.toPath().startsWith(root.toPath())) {
                "Refusing to reset integrated packaging output outside its build root: $target"
            }
            project.delete(target)
            check(target.mkdirs()) { "Could not create integrated packaging output: $target" }
        }

        resetBuildOutput(integrationWorkRoot, buildRoot)
        val inputDirectory = integrationWorkRoot.resolve("input")
        val configDirectory = integrationWorkRoot.resolve("config")
        check(inputDirectory.mkdirs() && configDirectory.mkdirs()) {
            "Could not create integrated jpackage staging directories"
        }
        composeAppDirectory.listFiles().orEmpty()
            .filterNot { it.name == ".jpackage.xml" || it.name == "EVE Static Map Planner.cfg" }
            .forEach { source ->
                val target = inputDirectory.resolve(source.name)
                if (source.isDirectory) source.copyRecursively(target, overwrite = true) else source.copyTo(target)
            }
        val stagedLegalDirectory = inputDirectory.resolve("legal")
        check(stagedLegalDirectory.mkdirs()) { "Could not create the distribution legal directory" }
        distributionNoticeFiles.files.forEach { notice ->
            notice.copyTo(stagedLegalDirectory.resolve(notice.name), overwrite = true)
        }
        distributionLegalDirectory.asFile.copyRecursively(stagedLegalDirectory, overwrite = true)
        check(inputDirectory.resolve(mainJar).isFile) { "Compose main jar was not staged: $mainJar" }
        val stagedMcpDirectory = inputDirectory.resolve("mcp")
        check(stagedMcpDirectory.mkdirs()) { "Could not create the MCP production classpath directory" }
        mcpJars.forEach { it.copyTo(stagedMcpDirectory.resolve(it.name)) }

        val additionalLauncherProperties = configDirectory.resolve("mcp-launcher.properties")
        additionalLauncherProperties.writeText(
            listOf(
                "main-jar=mcp/mcp-$appVersion.jar",
                "main-class=${WindowsAppImageIntegration.MCP_MAIN_CLASS}",
                "description=Secure stdio bridge for EVE Static Map Planner AI Map Control.",
                "app-version=$appVersion",
                "win-console=true",
                "win-shortcut=false",
                "win-menu=false",
            ).joinToString(System.lineSeparator(), postfix = System.lineSeparator()),
            Charsets.ISO_8859_1,
        )

        val integratedDestination = integrationWorkRoot.resolve("out")
        check(integratedDestination.mkdirs()) { "Could not create integrated image destination" }
        val jpackage = File(System.getProperty("java.home"), "bin/jpackage.exe")
        check(jpackage.isFile) { "jpackage.exe is unavailable in the Gradle JDK: $jpackage" }
        val arguments = listOf(
            jpackage.absolutePath,
            "--type", "app-image",
            "--input", inputDirectory.absolutePath,
            "--dest", integratedDestination.absolutePath,
            "--name", WindowsAppImageIntegration.MAIN_LAUNCHER,
            "--main-jar", mainJar,
            "--main-class", WindowsAppImageIntegration.MAIN_CLASS,
            "--app-version", appVersion,
            "--description", "Unofficial static map and route planning tool for EVE Online.",
            "--vendor", "Static Map Planner Project",
            "--runtime-image", composeApplicationImage.resolve("runtime").absolutePath,
            "--icon", project.file("src/main/resources/icons/app-icon.ico").absolutePath,
            "--java-options", "--enable-native-access=ALL-UNNAMED",
            "--add-launcher", "${WindowsAppImageIntegration.MCP_LAUNCHER}=${additionalLauncherProperties.absolutePath}",
            "--add-launcher", "${WindowsAppImageIntegration.STABLE_MCP_LAUNCHER}=${additionalLauncherProperties.absolutePath}",
        )
        val stdoutFile = integrationWorkRoot.resolve("jpackage-out.log")
        val stderrFile = integrationWorkRoot.resolve("jpackage-err.log")
        val process = ProcessBuilder(arguments)
            .directory(project.projectDir)
            .redirectOutput(stdoutFile)
            .redirectError(stderrFile)
            .start()
        val exitCode = process.waitFor()
        val stdout = stdoutFile.readText(Charset.forName(System.getProperty("native.encoding", "UTF-8")))
        val stderr = stderrFile.readText(Charset.forName(System.getProperty("native.encoding", "UTF-8")))
        stdout.lineSequence().filter(String::isNotBlank).forEach(logger::lifecycle)
        stderr.lineSequence().filter(String::isNotBlank).forEach(logger::error)
        check(exitCode == 0) { "Integrated app-image jpackage invocation failed with exit code $exitCode" }

        val generatedImage = integratedDestination.resolve(WindowsAppImageIntegration.MAIN_LAUNCHER)
        check(generatedImage.isDirectory) { "jpackage did not create the integrated app-image: $generatedImage" }
        val generatedAppDirectory = generatedImage.resolve("app")
        composeMainConfig.copyTo(
            generatedAppDirectory.resolve("${WindowsAppImageIntegration.MAIN_LAUNCHER}.cfg"),
            overwrite = true,
        )
        val mcpLauncherConfig = WindowsAppImageIntegration.mcpLauncherConfig(appVersion, mcpJars.map(File::getName))
        setOf(
            WindowsAppImageIntegration.MCP_LAUNCHER,
            WindowsAppImageIntegration.STABLE_MCP_LAUNCHER,
        ).forEach { launcher ->
            generatedAppDirectory.resolve("$launcher.cfg").writeText(mcpLauncherConfig, Charsets.UTF_8)
        }
        val jpackageState = generatedAppDirectory.resolve(".jpackage.xml").readText(Charsets.UTF_8)
        check(jpackageState.contains("<add-launcher name=\"${WindowsAppImageIntegration.MCP_LAUNCHER}\" service=\"false\"")) {
            "Integrated app-image metadata does not identify the MCP add-launcher"
        }
        check(jpackageState.contains(
            "<add-launcher name=\"${WindowsAppImageIntegration.STABLE_MCP_LAUNCHER}\" service=\"false\"",
        )) { "Integrated app-image metadata does not identify the stable MCP add-launcher" }

        resetBuildOutput(integratedImageBuildRoot, nativeComposeOutputBase.resolve("main"))
        generatedImage.copyRecursively(integratedApplicationImage, overwrite = true)
        val jimage = File(System.getProperty("java.home"), "bin/jimage.exe")
        val audit = WindowsAppImageIntegration.audit(
            integratedApplicationImage.toPath(),
            mcpJars.map(File::getName).toSet(),
            jimage.toPath(),
        )
        integrationWorkRoot.resolve("audit.txt").writeText(buildString {
            appendLine("launchers=${audit.launcherNames.sorted().joinToString(",")}")
            appendLine("runtimeDirectories=${audit.runtimeDirectories.size}")
            appendLine("runtimeModuleFiles=${audit.runtimeModuleFiles.size}")
            appendLine("runtimeModules=${audit.runtimeModules.joinToString(",")}")
            appendLine("mcpClasspathEntries=${audit.mcpClasspath.size}")
        })
        logger.lifecycle(
            "Integrated Windows app-image verified: launchers=${audit.launcherNames.size}, " +
                "runtimes=${audit.runtimeDirectories.size}, modules=${audit.runtimeModules.joinToString(",")}, " +
                "mcpClasspathEntries=${audit.mcpClasspath.size}",
        )
    }
}

val verifyFeaturePackPackaging by tasks.registering {
    group = "verification"
    description = "Verifies that Feature Packs remain external to the single-runtime production app-image."
    dependsOn(createIntegratedDistributable, featurePackFixture)
    inputs.dir(integratedApplicationImage)
    inputs.files(featurePackFixture)
    outputs.upToDateWhen { false }
    doLast {
        check(integratedApplicationImage.isDirectory) {
            "Missing integrated application image: $integratedApplicationImage"
        }
        val allPaths = integratedApplicationImage.walkTopDown().toList()
        check(allPaths.none { it.isFile && it.name.equals("pack.jar", ignoreCase = true) }) {
            "An external Feature Pack JAR was bundled into the application image"
        }
        check(allPaths.filter { it.isDirectory && it.name.equals("runtime", ignoreCase = true) } ==
            listOf(integratedApplicationImage.resolve("runtime"))) {
            "The production image must contain exactly one top-level runtime"
        }
        check(allPaths.count {
            it.isFile && it.name == "modules" && it.parentFile?.name == "lib" &&
                it.parentFile?.parentFile?.name.equals("runtime", ignoreCase = true)
        } == 1) {
            "The production image must contain exactly one JRE module image"
        }

        val applicationJars = integratedApplicationImage.resolve("app").walkTopDown()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .toList()
        val apiClass = "dev/evestaticmapplanner/feature/api/FeaturePackEntrypoint.class"
        val fixtureClass = "dev/evestaticmapplanner/feature/fixture/MinimalFixturePack.class"
        check(applicationJars.count { jar -> JarFile(jar).use { it.getEntry(apiClass) != null } } == 1) {
            "The packaged application must expose exactly one Feature API class identity"
        }
        check(applicationJars.none { jar -> JarFile(jar).use { it.getEntry(fixtureClass) != null } }) {
            "The fixture Feature Pack was embedded in the production application"
        }

        val fixtureJar = featurePackFixture.singleFile
        JarFile(fixtureJar).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toList()
            check(entries.none { it.startsWith("kotlin/") }) { "Fixture Pack bundles Kotlin stdlib" }
            check(entries.none { it.startsWith("dev/evestaticmapplanner/feature/api/") }) {
                "Fixture Pack bundles feature-api"
            }
            check(entries.none { it.endsWith("java.exe", ignoreCase = true) }) { "Fixture Pack bundles a JVM" }
        }
    }
}

val featurePackInstalledImageTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs Feature Pack lifecycle validation through the final Windows production launcher."
    dependsOn(createIntegratedDistributable, featurePackFixture)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("dev.evestaticmapplanner.featurepack.FeaturePackInstalledImageTest")
    systemProperty("feature.pack.installed.image", integratedApplicationImage.absolutePath)
    doFirst {
        systemProperty("feature.pack.fixture.jar", featurePackFixture.singleFile.absolutePath)
    }
    inputs.dir(integratedApplicationImage)
    inputs.files(featurePackFixture)
    outputs.upToDateWhen { false }
}

val esiPackInstalledImageTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Loads the real ESI Pack through the final Windows production launcher."
    dependsOn(createIntegratedDistributable)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("dev.evestaticmapplanner.featurepack.EsiPackInstalledImageTest")
    systemProperty("feature.pack.installed.image", integratedApplicationImage.absolutePath)
    doFirst {
        val pack = esiPackJar.orNull?.let(::file)
            ?: error("esiPackInstalledImageTest requires -PesiPackJar=<canonical ESI Pack jar>")
        check(pack.isFile) { "ESI Pack jar does not exist: ${pack.absolutePath}" }
        systemProperty("esi.pack.jar", pack.absolutePath)
    }
    inputs.dir(integratedApplicationImage)
    inputs.file(esiPackJar)
    outputs.upToDateWhen { false }
}

val portableArchiveName = PortableDistributionAudit.archiveFileName(appVersion)
val portableReleaseDirectory = rootProject.layout.buildDirectory.dir("release")
val portableArchiveFile = portableReleaseDirectory.map { it.file(portableArchiveName) }
val portableExtractionRoot = rootProject.layout.buildDirectory.dir(
    "portable-acceptance/Portable QA With Spaces",
)
val portableExtractedImage = portableExtractionRoot.map {
    it.dir(PortableDistributionAudit.ROOT_DIRECTORY)
}

val packagePortableZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the verified self-contained Windows x64 application image as a Portable ZIP."
    dependsOn(
        createIntegratedDistributable,
        verifyFeaturePackPackaging,
        ":mcp:analyzeProductionRuntimeModules",
    )
    archiveFileName.set(portableArchiveName)
    destinationDirectory.set(portableReleaseDirectory)
    from(integratedApplicationImage) {
        into(PortableDistributionAudit.ROOT_DIRECTORY)
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    doFirst {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "The Windows Portable ZIP can only be packaged on Windows."
        }
        check(integratedApplicationImage.isDirectory) {
            "Missing integrated application image: $integratedApplicationImage"
        }
    }
}

val verifyPortableZip by tasks.registering {
    group = "verification"
    description = "Audits and extracts the final Portable ZIP, then verifies the extracted app-image."
    dependsOn(packagePortableZip)
    val auditFile = portableReleaseDirectory.map { it.file("portable-audit-0.6.0.txt") }
    val checksumFile = portableReleaseDirectory.map { it.file("$portableArchiveName.sha256") }
    val manifestFile = portableReleaseDirectory.map { it.file("release-manifest-0.6.0.txt") }
    outputs.files(auditFile, checksumFile, manifestFile)
    outputs.dir(portableExtractionRoot)
    outputs.upToDateWhen { false }

    doLast {
        val zip = portableArchiveFile.get().asFile.toPath()
        val expectedMcpJars = mcpInstalledLibraries.get().asFile.listFiles()
            ?.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            ?.map(File::getName)
            ?.toSet()
            .orEmpty()
        val archiveAudit = PortableDistributionAudit.audit(zip, appVersion, expectedMcpJars)

        val extractionRoot = portableExtractionRoot.get().asFile
        val rootBuildDirectory = rootProject.layout.buildDirectory.get().asFile.canonicalFile
        val extractionTarget = extractionRoot.canonicalFile
        check(extractionTarget != rootBuildDirectory && extractionTarget.toPath().startsWith(rootBuildDirectory.toPath())) {
            "Refusing to reset Portable extraction outside the root build directory: $extractionTarget"
        }
        rootProject.delete(extractionTarget)
        rootProject.copy {
            from(rootProject.zipTree(zip.toFile()))
            into(extractionTarget)
        }

        val extractedImage = portableExtractedImage.get().asFile
        val jimage = File(System.getProperty("java.home"), "bin/jimage.exe")
        val imageAudit = WindowsAppImageIntegration.audit(
            extractedImage.toPath(),
            expectedMcpJars,
            jimage.toPath(),
        )
        val extractedFiles = extractedImage.walkTopDown().count(File::isFile)
        check(extractedFiles == archiveAudit.fileCount) {
            "Extracted file count drift: archive=${archiveAudit.fileCount}, extracted=$extractedFiles"
        }
        check(extractedImage.walkTopDown().none { path ->
            path.isFile && path.name.lowercase() in WindowsAppImageIntegration.forbiddenPackagedFileNames
        }) { "Extracted application image contains mutable user data" }

        val checksumLine = "${archiveAudit.sha256}  $portableArchiveName"
        checksumFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(checksumLine + System.lineSeparator(), Charsets.UTF_8)
        }
        manifestFile.get().asFile.writeText(
            buildString {
                appendLine("EVE Static Map Planner release 0.6.0")
                appendLine("main_artifact=$portableArchiveName")
                appendLine("main_size_bytes=${zip.toFile().length()}")
                appendLine("main_uncompressed_size_bytes=${archiveAudit.uncompressedSize}")
                appendLine("main_file_count=${archiveAudit.fileCount}")
                appendLine("main_sha256=${archiveAudit.sha256}")
                appendLine("esi_pack_version=0.5.0")
                appendLine("sovereignty_pack_version=0.2.0")
                appendLine("feature_api_version=2.0.0")
                appendLine("msi_artifact=REMOVED")
            },
            Charsets.UTF_8,
        )
        auditFile.get().asFile.writeText(
            buildString {
                appendLine("archive=$portableArchiveName")
                appendLine("compressedBytes=${zip.toFile().length()}")
                appendLine("uncompressedBytes=${archiveAudit.uncompressedSize}")
                appendLine("files=${archiveAudit.fileCount}")
                appendLine("sha256=${archiveAudit.sha256}")
                appendLine("launchers=${imageAudit.launcherNames.sorted().joinToString(",")}")
                appendLine("runtimeModulesFile=${imageAudit.runtimeModuleFiles.single()}")
                appendLine("runtimeModules=${imageAudit.runtimeModules.joinToString(",")}")
                appendLine("mcpJars=${archiveAudit.mcpJarNames.size}")
            },
            Charsets.UTF_8,
        )
        logger.lifecycle(
            "Portable ZIP verified: files=${archiveAudit.fileCount}, " +
                "compressed=${zip.toFile().length()}, uncompressed=${archiveAudit.uncompressedSize}, " +
                "sha256=${archiveAudit.sha256}",
        )
    }
}

val portableFeaturePackInstalledImageTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Loads an external fixture Pack through the extracted Portable launcher."
    dependsOn(verifyPortableZip, featurePackFixture)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("dev.evestaticmapplanner.featurepack.FeaturePackInstalledImageTest")
    doFirst {
        systemProperty("feature.pack.installed.image", portableExtractedImage.get().asFile.absolutePath)
        systemProperty("feature.pack.fixture.jar", featurePackFixture.singleFile.absolutePath)
    }
    inputs.dir(portableExtractedImage)
    inputs.files(featurePackFixture)
    outputs.upToDateWhen { false }
}

val portableEsiPackInstalledImageTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Loads the real ESI Pack through the extracted Portable launcher."
    dependsOn(verifyPortableZip)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("dev.evestaticmapplanner.featurepack.EsiPackInstalledImageTest")
    doFirst {
        val pack = esiPackJar.orNull?.let(::file)
            ?: error("portableEsiPackInstalledImageTest requires -PesiPackJar=<canonical ESI Pack jar>")
        check(pack.isFile) { "ESI Pack jar does not exist: ${pack.absolutePath}" }
        systemProperty("feature.pack.installed.image", portableExtractedImage.get().asFile.absolutePath)
        systemProperty("esi.pack.jar", pack.absolutePath)
    }
    inputs.dir(portableExtractedImage)
    inputs.file(esiPackJar)
    outputs.upToDateWhen { false }
}

val portableEsiSsoPackPreflightRuntime = rootProject.layout.buildDirectory.dir(
    "portable-acceptance/esi-sso-runtime",
)

val preparePortableEsiSsoPackPreflightRuntime by tasks.registering(Sync::class) {
    group = "verification"
    description = "Prepares an isolated copy of the Portable runtime with a test-only console launcher."
    dependsOn(verifyPortableZip)
    val javaLauncher = File(System.getProperty("java.home"), "bin/java.exe")
    from(portableExtractedImage.map { it.dir("runtime") })
    from(javaLauncher) { into("bin") }
    into(portableEsiSsoPackPreflightRuntime)
    doFirst {
        check(javaLauncher.isFile) { "Gradle Java launcher does not exist: $javaLauncher" }
        check(portableExtractedImage.get().file("runtime/lib/modules").asFile.isFile) {
            "Extracted Portable runtime module image is missing"
        }
    }
}

val portableEsiSsoPackPreflight by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs ESI Connect through the production Pack host with the extracted Portable runtime."
    dependsOn(preparePortableEsiSsoPackPreflightRuntime, tasks.named("testClasses"))
    val preflightClasspath = sourceSets.test.get().runtimeClasspath
    val report = rootProject.layout.buildDirectory.file("portable-acceptance/esi-sso-pack-preflight.txt")
    doFirst {
        val pack = esiPackJar.orNull?.let(::file)
            ?: error("portableEsiSsoPackPreflight requires -PesiPackJar=<canonical ESI Pack jar>")
        check(pack.isFile) { "ESI Pack jar does not exist: ${pack.absolutePath}" }
        commandLine(
            portableEsiSsoPackPreflightRuntime.get().file("bin/java.exe").asFile.absolutePath,
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            preflightClasspath.asPath,
            "dev.evestaticmapplanner.featurepack.PortableEsiSsoPackPreflightKt",
            pack.absolutePath,
            report.get().asFile.absolutePath,
        )
    }
    inputs.dir(portableEsiSsoPackPreflightRuntime)
    inputs.file(esiPackJar)
    outputs.file(report)
    outputs.upToDateWhen { false }
}

val portableExternalFeaturePacksInstalledImageTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Loads the real ESI and Sovereignty Packs together through the extracted Portable launcher."
    dependsOn(verifyPortableZip)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching(
        "dev.evestaticmapplanner.featurepack.PortableExternalFeaturePacksInstalledImageTest",
    )
    doFirst {
        val esiPack = esiPackJar.orNull?.let(::file)
            ?: error("portableExternalFeaturePacksInstalledImageTest requires -PesiPackJar=<pack.jar>")
        val sovereigntyPack = sovereigntyPackJar.orNull?.let(::file)
            ?: error("portableExternalFeaturePacksInstalledImageTest requires -PsovereigntyPackJar=<pack.jar>")
        check(esiPack.isFile) { "ESI Pack jar does not exist: ${esiPack.absolutePath}" }
        check(sovereigntyPack.isFile) { "Sovereignty Pack jar does not exist: ${sovereigntyPack.absolutePath}" }
        systemProperty("feature.pack.installed.image", portableExtractedImage.get().asFile.absolutePath)
        systemProperty("esi.pack.jar", esiPack.absolutePath)
        systemProperty("sovereignty.pack.jar", sovereigntyPack.absolutePath)
    }
    inputs.dir(portableExtractedImage)
    inputs.file(esiPackJar)
    inputs.file(sovereigntyPackJar)
    outputs.upToDateWhen { false }
}

val portableAcceptance by tasks.registering {
    group = "verification"
    description = "Runs the self-contained Portable ZIP, fixture Pack, and MCP process acceptance gates."
    dependsOn(
        portableFeaturePackInstalledImageTest,
        ":mcp:portableLocatorExternalConsumerTest",
        ":mcp:portableInstalledImageTest",
    )
}
