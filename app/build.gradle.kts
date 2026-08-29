import dev.evestaticmapplanner.packaging.JpackageComponentGuidNamespace
import dev.evestaticmapplanner.packaging.MsiComponentTableReader
import dev.evestaticmapplanner.packaging.MsiDistributionAuditReader
import dev.evestaticmapplanner.packaging.MsiLegacyPackageCleanupReader
import dev.evestaticmapplanner.packaging.WindowsAppImageIntegration
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
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
val windowsUpgradeUuid = "502B9850-A5B0-4922-BB20-AC7FEBA590DC"
val windowsProductUuid = "D26534C4-866C-48BD-A383-CFFE1D096FCD"
val windowsComponentNamespace = UUID.fromString(windowsUpgradeUuid)
val historicalProductCodes = setOf(
    "{A7D6C309-9A9F-3079-A87B-1616BAD49516}", // 0.1.0
    "{1984FBD7-03D9-38DD-8ECD-F58783036C95}", // 0.1.1
    "{8E5104B6-B3CA-36C7-BC65-B3401F15F421}", // 0.1.2
    "{A9BA3E5C-BEAA-336C-830D-5663D6477EEA}", // 0.2.0
    "{47FE49EB-BDC1-3CF5-B949-C743BA3822FD}", // discarded 0.6.0 pre-release MSI
    "{29AF5999-F80D-4608-A69B-D06CF096751E}", // discarded 0.6.0 early-removal QA MSI
    "{050C3D41-224F-4558-90C1-745159869D14}", // discarded 0.6.0 pre-commit QA MSI
)
val windowsInstallerResources = layout.projectDirectory.dir("src/main/jpackage/windows")
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

val verifyWindowsInstallerResources by tasks.registering {
    inputs.file(windowsInstallerResources.file("main.wxs"))
    doLast {
        val mainWxs = windowsInstallerResources.file("main.wxs").asFile.readText()
        check(mainWxs.contains("Property=\"RM_RF48431AECBD69377EA800D62616352F20\" Value=\"\"")) {
            "The jpackage RemoveFolderEx path property is not cleared by the custom main.wxs."
        }
        check(mainWxs.contains("After=\"AppSearch\" Condition=\"UPGRADINGPRODUCTCODE\"")) {
            "The custom main.wxs does not guard recursive cleanup during a major upgrade."
        }
        check(windowsUpgradeUuid == "502B9850-A5B0-4922-BB20-AC7FEBA590DC") {
            "The Windows installer UpgradeCode changed unexpectedly."
        }
        check(mainWxs.contains("ProductCode=\"{$windowsProductUuid}\"")) {
            "The final 0.6.0 ProductCode drifted."
        }
        check(mainWxs.contains("Property=\"JP_UPGRADABLE_FOUND\"") &&
            mainWxs.contains("IncludeMaximum=\"yes\"")) {
            "The installer no longer replaces an older same-version release candidate."
        }
        check(mainWxs.contains("<RemoveExistingProducts After=\"InstallFinalize\" />")) {
            "The installer no longer preserves shared components during a same-version replacement."
        }
        check(mainWxs.contains("Id=\"${WindowsAppImageIntegration.PATH_COMPONENT_ID}\"")) {
            "The stable MCP PATH Component is missing from the custom main.wxs."
        }
        check(mainWxs.contains("Guid=\"${WindowsAppImageIntegration.PATH_COMPONENT_GUID}\"")) {
            "The stable MCP PATH Component GUID drifted."
        }
        check(mainWxs.contains(
            "<Environment Id=\"${WindowsAppImageIntegration.PATH_ENVIRONMENT_ID}\" Name=\"PATH\" " +
                "Value=\"[INSTALLDIR]\" Action=\"set\" Part=\"last\" System=\"no\" Permanent=\"no\" />",
        )) { "The per-user MSI PATH authoring drifted." }
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
            targetFormats(TargetFormat.Msi)
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
                dirChooser = false
                perUserInstall = true
                menu = true
                menuGroup = "EVE Static Map Planner"
                shortcut = true
                upgradeUuid = windowsUpgradeUuid
            }
        }
    }
}

// Compose 1.10.0 validates its private WiX 3 directory even for an app-image,
// while the project uses the system WiX 4 tool only in the custom MSI pipeline.
// Keep the unused Compose tasks disabled and satisfy only the directory input.
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

// Compose 1.10.0 always appends its private --resource-dir after freeArgs. Keep
// its public packageMsi task and app-image prerequisites, but replace the final
// invocation with a two-pass jpackage pipeline. Pass 1 is a non-release probe;
// pass 2 adopts a generated bundle.wxf whose effective Component GUIDs are
// deterministically namespaced by the fixed UpgradeCode.
tasks.matching { it.name == "packageMsi" }.configureEach {
    setActions(emptyList())
    dependsOn(
        createIntegratedDistributable,
        verifyFeaturePackPackaging,
        featurePackInstalledImageTest,
        verifyWindowsInstallerResources,
        ":mcp:analyzeProductionRuntimeModules",
        ":mcp:installedImageTest",
    )
    inputs.dir(windowsInstallerResources)
    inputs.property("windowsComponentNamespace", windowsComponentNamespace.toString())
    inputs.property("windowsProductUuid", windowsProductUuid)
    inputs.property("jpackageComponentGuidContract", JpackageComponentGuidNamespace.NAME_PREFIX)
    val applicationImage = integratedApplicationImage
    val generatedMsi = nativeComposeOutputBase.resolve("main/msi/EVE Static Map Planner-$appVersion.msi")
    val componentGuidBuildRoot = layout.buildDirectory.dir("jpackage/component-guid").get().asFile
    val probeRoot = componentGuidBuildRoot.resolve("probe")
    val probeTemp = probeRoot.resolve("temp")
    val probeDest = probeRoot.resolve("out")
    val finalTemp = componentGuidBuildRoot.resolve("final-temp")
    val packagingLogs = componentGuidBuildRoot.resolve("logs")
    val generatedWindowsResources = layout.buildDirectory.dir("generated/jpackage/windows").get().asFile
    val generatedBundle = generatedWindowsResources.resolve("bundle.wxf")
    val componentMappingCsv = componentGuidBuildRoot.resolve("component-guid-mapping.csv")
    inputs.dir(applicationImage)
    outputs.files(generatedMsi, generatedBundle, componentMappingCsv)
    outputs.upToDateWhen { false }
    doLast {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "The MSI can only be packaged on Windows."
        }
        check(System.getProperty("java.runtime.version").startsWith("25.0.4+7")) {
            "jpackage Component GUID contract was audited for JDK 25.0.4+7 only; " +
                "found ${System.getProperty("java.runtime.version")}. Perform the documented JDK drift audit."
        }
        check(applicationImage.isDirectory) { "Missing Compose application image: $applicationImage" }

        val executable = File(System.getProperty("java.home"), "bin/jpackage.exe")
        check(executable.isFile) { "jpackage.exe is not available in the Gradle JDK: $executable" }

        val buildRoot = layout.buildDirectory.get().asFile.canonicalFile
        fun resetBuildOutput(directory: File) {
            val target = directory.canonicalFile
            check(target != buildRoot && target.toPath().startsWith(buildRoot.toPath())) {
                "Refusing to reset packaging output outside the app build directory: $target"
            }
            project.delete(target)
            check(target.mkdirs()) { "Could not create packaging output directory: $target" }
        }

        fun jpackageArguments(destination: File, temporaryDirectory: File, resourceDirectory: File): List<String> = listOf(
            executable.absolutePath,
            "--type", "msi",
            "--app-image", applicationImage.absolutePath,
            "--dest", destination.absolutePath,
            "--temp", temporaryDirectory.absolutePath,
            "--resource-dir", resourceDirectory.absolutePath,
            "--name", "EVE Static Map Planner",
            "--description", "Unofficial static map and route planning tool for EVE Online.",
            "--app-version", appVersion,
            "--vendor", "Static Map Planner Project",
            "--win-per-user-install",
            "--win-shortcut",
            "--win-menu",
            "--win-menu-group", "EVE Static Map Planner",
            "--win-upgrade-uuid", windowsUpgradeUuid,
            "--verbose",
        )

        val processCharset = Charset.forName(System.getProperty("native.encoding", "UTF-8"))
        fun runJpackage(label: String, arguments: List<String>): String {
            val stdoutFile = packagingLogs.resolve("$label-jpackage-out.log")
            val stderrFile = packagingLogs.resolve("$label-jpackage-err.log")
            packagingLogs.mkdirs()
            packagingLogs.resolve("$label-jpackage-command.txt").writeText(
                arguments.joinToString(System.lineSeparator()) { argument ->
                    if (argument.any(Char::isWhitespace)) "\"$argument\"" else argument
                } + System.lineSeparator(),
            )
            val process = ProcessBuilder(arguments)
                .directory(project.projectDir)
                .redirectOutput(stdoutFile)
                .redirectError(stderrFile)
                .start()
            val exitCode = process.waitFor()
            val stdout = stdoutFile.readText(processCharset)
            val stderr = stderrFile.readText(processCharset)
            stdout.lineSequence().filter(String::isNotBlank).forEach(logger::lifecycle)
            stderr.lineSequence().filter(String::isNotBlank).forEach(logger::error)
            check(exitCode == 0) { "$label jpackage invocation failed with exit code $exitCode" }
            return stdout + System.lineSeparator() + stderr
        }

        resetBuildOutput(componentGuidBuildRoot)
        check(probeTemp.mkdirs() && probeDest.mkdirs() && finalTemp.mkdirs() && packagingLogs.mkdirs()) {
            "Could not create two-pass jpackage directories under $componentGuidBuildRoot"
        }

        val probeLog = runJpackage(
            "probe",
            jpackageArguments(probeDest, probeTemp, windowsInstallerResources.asFile),
        )
        val probeMsi = probeDest.resolve("EVE Static Map Planner-$appVersion.msi")
        val probeBundle = probeTemp.resolve("config/bundle.wxf")
        check(probeMsi.isFile) { "Probe MSI was not created: $probeMsi" }
        check(probeBundle.isFile) { "Probe bundle.wxf was not retained: $probeBundle" }
        check(Regex("main\\.wxs", RegexOption.IGNORE_CASE).findAll(probeLog).count() >= 3) {
            "Probe verbose log does not prove custom main.wxs adoption"
        }

        val probeComponents = MsiComponentTableReader.read(probeMsi.toPath())
        val generatedProbeComponents = probeComponents - WindowsAppImageIntegration.PATH_COMPONENT_ID
        check(probeComponents[WindowsAppImageIntegration.PATH_COMPONENT_ID]
            ?.equals(WindowsAppImageIntegration.PATH_COMPONENT_GUID, ignoreCase = true) == true) {
            "Probe MSI stable MCP PATH Component is missing or has an unexpected GUID"
        }
        resetBuildOutput(generatedWindowsResources)
        windowsInstallerResources.asFile.copyRecursively(generatedWindowsResources, overwrite = true)
        val transformResult = JpackageComponentGuidNamespace.transform(
            sourceBundle = probeBundle.toPath(),
            probeComponents = generatedProbeComponents,
            outputBundle = generatedBundle.toPath(),
            namespace = windowsComponentNamespace,
            excludedShortcutLauncherNames = setOf(
                WindowsAppImageIntegration.MCP_LAUNCHER,
                WindowsAppImageIntegration.STABLE_MCP_LAUNCHER,
            ),
        )
        componentMappingCsv.writeText(buildString {
            appendLine("componentId,effectiveOriginalGuid,namespacedGuid")
            for (mapping in transformResult.mappings.values.sortedBy { it.componentId }) {
                appendLine("${mapping.componentId},${mapping.effectiveOriginalGuid},${mapping.namespacedGuid}")
            }
        })
        check(
            transformResult.componentCount + transformResult.removedShortcutComponentIds.size + 1 ==
                probeComponents.size,
        ) {
            "Transformed Component count plus excluded MCP shortcut Components does not match probe MSI"
        }

        generatedMsi.parentFile.mkdirs()
        check(!generatedMsi.exists() || generatedMsi.delete()) { "Could not replace MSI: $generatedMsi" }
        val finalLog = runJpackage(
            "final",
            jpackageArguments(generatedMsi.parentFile, finalTemp, generatedWindowsResources),
        )
        check(generatedMsi.isFile) { "Namespaced MSI was not created: $generatedMsi" }
        check(Regex("main\\.wxs", RegexOption.IGNORE_CASE).findAll(finalLog).count() >= 3) {
            "Final verbose log does not prove custom main.wxs adoption"
        }
        check(Regex("bundle\\.wxf", RegexOption.IGNORE_CASE).findAll(finalLog).count() >= 3) {
            "Final verbose log does not prove custom bundle.wxf adoption"
        }
        val finalComponents = MsiComponentTableReader.read(generatedMsi.toPath())
        check(finalComponents[WindowsAppImageIntegration.PATH_COMPONENT_ID]
            ?.equals(WindowsAppImageIntegration.PATH_COMPONENT_GUID, ignoreCase = true) == true) {
            "Final MSI stable MCP PATH Component is missing or has an unexpected GUID"
        }
        JpackageComponentGuidNamespace.verifyFinalComponents(
            finalComponents - WindowsAppImageIntegration.PATH_COMPONENT_ID,
            transformResult,
        )
        val legacyCleanup = MsiLegacyPackageCleanupReader.verify(
            generatedMsi.toPath(),
            transformResult.legacyPackageCleanupComponentId,
        )
        val distributionAudit = MsiDistributionAuditReader.read(generatedMsi.toPath())
        val requiredTables = setOf(
            "Property",
            "File",
            "Component",
            "Feature",
            "Shortcut",
            "RemoveFile",
            "Upgrade",
            "InstallExecuteSequence",
            "CustomAction",
            "Wix4RemoveFolderEx",
            "Environment",
        )
        check(distributionAudit.tables.containsAll(requiredTables)) {
            "Final MSI is missing required audit tables: ${requiredTables - distributionAudit.tables}"
        }
        check(distributionAudit.productName == WindowsAppImageIntegration.MAIN_LAUNCHER)
        check(distributionAudit.productVersion == appVersion)
        check(distributionAudit.manufacturer == "Static Map Planner Project")
        check(distributionAudit.upgradeCode.equals("{$windowsUpgradeUuid}", ignoreCase = true))
        check(distributionAudit.productCode.equals("{$windowsProductUuid}", ignoreCase = true)) {
            "The $appVersion ProductCode drifted: ${distributionAudit.productCode}"
        }
        check(distributionAudit.productCode !in historicalProductCodes) {
            "The $appVersion ProductCode reuses a discarded or historical product identity: " +
                distributionAudit.productCode
        }
        check(distributionAudit.summaryTemplate.startsWith("x64;")) {
            "Final MSI is not Windows x64: ${distributionAudit.summaryTemplate}"
        }
        check(distributionAudit.features.size == 1) {
            "Final MSI must contain one product Feature, found ${distributionAudit.features.size}"
        }
        fun longMsiName(name: String): String = name.substringAfter('|', name)
        val filesByLongName = distributionAudit.files.associateBy { longMsiName(it[2]) }
        check(filesByLongName.containsKey("${WindowsAppImageIntegration.MAIN_LAUNCHER}.exe")) {
            "Final MSI does not contain the main launcher"
        }
        val mcpLauncherFile = filesByLongName["${WindowsAppImageIntegration.MCP_LAUNCHER}.exe"]
            ?: error("Final MSI does not contain the MCP launcher")
        check(filesByLongName.containsKey("${WindowsAppImageIntegration.MCP_LAUNCHER}.cfg")) {
            "Final MSI does not contain the MCP launcher config"
        }
        val stableMcpLauncherFile = filesByLongName["${WindowsAppImageIntegration.STABLE_MCP_LAUNCHER}.exe"]
            ?: error("Final MSI does not contain the stable MCP launcher")
        check(filesByLongName.containsKey("${WindowsAppImageIntegration.STABLE_MCP_LAUNCHER}.cfg")) {
            "Final MSI does not contain the stable MCP launcher config"
        }
        val mcpLauncherComponent = distributionAudit.components.single { it[0] == mcpLauncherFile[1] }
        val mcpLauncherDirectory = distributionAudit.directories.single { it[0] == mcpLauncherComponent[2] }
        check(longMsiName(mcpLauncherDirectory[2]) == WindowsAppImageIntegration.MAIN_LAUNCHER) {
            "MCP launcher is not installed at the product install root: $mcpLauncherDirectory"
        }
        val stableMcpLauncherComponent = distributionAudit.components.single { it[0] == stableMcpLauncherFile[1] }
        val stableMcpLauncherDirectory = distributionAudit.directories.single {
            it[0] == stableMcpLauncherComponent[2]
        }
        check(longMsiName(stableMcpLauncherDirectory[2]) == WindowsAppImageIntegration.MAIN_LAUNCHER) {
            "Stable MCP launcher is not installed at the product install root: $stableMcpLauncherDirectory"
        }
        val forbiddenFileRows = distributionAudit.files.filter {
            longMsiName(it[2]).lowercase() in WindowsAppImageIntegration.forbiddenPackagedFileNames
        }
        check(forbiddenFileRows.isEmpty()) { "Final MSI contains runtime/user data: $forbiddenFileRows" }
        check(distributionAudit.files.none { longMsiName(it[2]).equals("pack.jar", ignoreCase = true) }) {
            "Final MSI contains an external Feature Pack JAR"
        }
        check(distributionAudit.files.none { longMsiName(it[2]).contains("ktor", ignoreCase = true) }) {
            "Final MSI contains a Ktor runtime"
        }
        val shortcutNames = distributionAudit.shortcuts.map { longMsiName(it[2]) }
        check(shortcutNames.isNotEmpty() && shortcutNames.all {
            it == WindowsAppImageIntegration.MAIN_LAUNCHER
        }) { "Only the desktop application may receive shortcuts: $shortcutNames" }
        check(distributionAudit.environment == listOf(listOf(
            WindowsAppImageIntegration.PATH_ENVIRONMENT_ID,
            "=-PATH",
            "[~];[INSTALLDIR]",
            WindowsAppImageIntegration.PATH_COMPONENT_ID,
        ))) { "Per-user PATH Environment row drifted: ${distributionAudit.environment}" }
        check(distributionAudit.installExecuteSequence.count { it[0] == "WriteEnvironmentStrings" } == 1) {
            "MSI must schedule WriteEnvironmentStrings exactly once"
        }
        check(distributionAudit.installExecuteSequence.count { it[0] == "RemoveEnvironmentStrings" } == 1) {
            "MSI must schedule RemoveEnvironmentStrings exactly once"
        }
        check(distributionAudit.upgrades.isNotEmpty() && distributionAudit.upgrades.all {
            it[0].equals("{$windowsUpgradeUuid}", ignoreCase = true)
        }) { "Final MSI contains an unexpected UpgradeCode row: ${distributionAudit.upgrades}" }
        val sameVersionUpgrade = distributionAudit.upgrades.single { it[4] == "JP_UPGRADABLE_FOUND" }
        check(sameVersionUpgrade[2] == appVersion && sameVersionUpgrade[3].toInt() and 512 != 0) {
            "Final MSI does not replace an older same-version release candidate: $sameVersionUpgrade"
        }
        check(distributionAudit.customActions.count {
            it[0] == "JpSuppressRemoveFolderExDuringUpgrade" && it[2] == "RM_RF48431AECBD69377EA800D62616352F20"
        } == 1) { "Major-upgrade AppData cleanup suppression CustomAction drifted" }
        check(distributionAudit.installExecuteSequence.count {
            it[0] == "JpSuppressRemoveFolderExDuringUpgrade" && it[1] == "UPGRADINGPRODUCTCODE"
        } == 1) { "Major-upgrade AppData preservation sequence drifted" }
        val installFinalizeSequence = distributionAudit.installExecuteSequence.single {
            it[0] == "InstallFinalize"
        }[2].toInt()
        val removeExistingProductsSequence = distributionAudit.installExecuteSequence.single {
            it[0] == "RemoveExistingProducts"
        }[2].toInt()
        check(removeExistingProductsSequence > installFinalizeSequence) {
            "Major upgrade must remove the related product after InstallFinalize: " +
                "RemoveExistingProducts=$removeExistingProductsSequence, " +
                "InstallFinalize=$installFinalizeSequence"
        }
        check(distributionAudit.removeFolderEx.size == 1 &&
            distributionAudit.removeFolderEx.single()[2] == "RM_RF48431AECBD69377EA800D62616352F20") {
            "Normal uninstall recursive AppData cleanup drifted: ${distributionAudit.removeFolderEx}"
        }
        JpackageComponentGuidNamespace.assertOnlyExpectedChanges(
            probeBundle.toPath(),
            generatedBundle.toPath(),
            excludedShortcutLauncherNames = setOf(
                WindowsAppImageIntegration.MCP_LAUNCHER,
                WindowsAppImageIntegration.STABLE_MCP_LAUNCHER,
            ),
        )
        componentGuidBuildRoot.resolve("msi-audit.txt").writeText(buildString {
            appendLine("productName=${distributionAudit.productName}")
            appendLine("productVersion=${distributionAudit.productVersion}")
            appendLine("productCode=${distributionAudit.productCode}")
            appendLine("upgradeCode=${distributionAudit.upgradeCode}")
            appendLine("manufacturer=${distributionAudit.manufacturer}")
            appendLine("architecture=${distributionAudit.summaryTemplate}")
            appendLine("installScope=perUser")
            appendLine("featureCount=${distributionAudit.features.size}")
            appendLine("componentCount=${finalComponents.size}")
            appendLine("fileCount=${distributionAudit.files.size}")
            appendLine("shortcutCount=${distributionAudit.shortcuts.size}")
            appendLine("mcpLauncherDirectory=${mcpLauncherComponent[2]}")
            appendLine("stableMcpLauncherDirectory=${stableMcpLauncherComponent[2]}")
            appendLine("pathEnvironmentRows=${distributionAudit.environment.size}")
            appendLine("runtimeCount=1")
        })
        logger.lifecycle(
            "Namespaced MSI verified: components=${transformResult.componentCount}, " +
                "explicit=${transformResult.explicitGuidCount}, added=${transformResult.addedGuidCount}, " +
                "excludedMcpShortcutComponents=${transformResult.removedShortcutComponentIds.size}, " +
                "legacyPackageCleanup=${legacyCleanup.componentId}@${legacyCleanup.directoryId}, " +
                "namespace=$windowsUpgradeUuid, productCode=${distributionAudit.productCode}, " +
                "files=${distributionAudit.files.size}, shortcuts=${distributionAudit.shortcuts.size}",
        )
    }
}
