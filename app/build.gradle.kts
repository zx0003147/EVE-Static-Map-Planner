import dev.evestaticmapplanner.packaging.JpackageComponentGuidNamespace
import dev.evestaticmapplanner.packaging.MsiComponentTableReader
import dev.evestaticmapplanner.packaging.MsiLegacyPackageCleanupReader
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.artifacts.VersionCatalogsExtension
import java.io.File
import java.nio.charset.Charset
import java.util.UUID

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
val windowsComponentNamespace = UUID.fromString(windowsUpgradeUuid)
val windowsInstallerResources = layout.projectDirectory.dir("src/main/jpackage/windows")

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
    from(rootProject.file("NOTICE.md")) {
        into("legal")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":control"))
    implementation(project(":data"))
    implementation(project(":sde"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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

tasks.matching { it.name == "createDistributable" }.configureEach {
    dependsOn(prepareUnusedComposeWixDirectory)
}

// Compose 1.10.0 always appends its private --resource-dir after freeArgs. Keep
// its public packageMsi task and app-image prerequisites, but replace the final
// invocation with a two-pass jpackage pipeline. Pass 1 is a non-release probe;
// pass 2 adopts a generated bundle.wxf whose effective Component GUIDs are
// deterministically namespaced by the fixed UpgradeCode.
tasks.matching { it.name == "packageMsi" }.configureEach {
    setActions(emptyList())
    dependsOn("createDistributable", verifyWindowsInstallerResources)
    inputs.dir(windowsInstallerResources)
    inputs.property("windowsComponentNamespace", windowsComponentNamespace.toString())
    inputs.property("jpackageComponentGuidContract", JpackageComponentGuidNamespace.NAME_PREFIX)
    val outputBase = nativeOutputDir
        ?.let(::file)
        ?.resolve("compose")
        ?: layout.buildDirectory.dir("compose/binaries").get().asFile
    val applicationImage = outputBase.resolve("main/app/EVE Static Map Planner")
    val generatedMsi = outputBase.resolve("main/msi/EVE Static Map Planner-$appVersion.msi")
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
        resetBuildOutput(generatedWindowsResources)
        windowsInstallerResources.asFile.copyRecursively(generatedWindowsResources, overwrite = true)
        val transformResult = JpackageComponentGuidNamespace.transform(
            sourceBundle = probeBundle.toPath(),
            probeComponents = probeComponents,
            outputBundle = generatedBundle.toPath(),
            namespace = windowsComponentNamespace,
        )
        componentMappingCsv.writeText(buildString {
            appendLine("componentId,effectiveOriginalGuid,namespacedGuid")
            for (mapping in transformResult.mappings.values.sortedBy { it.componentId }) {
                appendLine("${mapping.componentId},${mapping.effectiveOriginalGuid},${mapping.namespacedGuid}")
            }
        })
        check(transformResult.componentCount == probeComponents.size) {
            "Transformed Component count does not match probe MSI"
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
        JpackageComponentGuidNamespace.verifyFinalComponents(finalComponents, transformResult)
        val legacyCleanup = MsiLegacyPackageCleanupReader.verify(
            generatedMsi.toPath(),
            transformResult.legacyPackageCleanupComponentId,
        )
        JpackageComponentGuidNamespace.assertOnlyExpectedChanges(
            probeBundle.toPath(),
            generatedBundle.toPath(),
        )
        logger.lifecycle(
            "Namespaced MSI verified: components=${transformResult.componentCount}, " +
                "explicit=${transformResult.explicitGuidCount}, added=${transformResult.addedGuidCount}, " +
                "legacyPackageCleanup=${legacyCleanup.componentId}@${legacyCleanup.directoryId}, " +
                "namespace=$windowsUpgradeUuid",
        )
    }
}
