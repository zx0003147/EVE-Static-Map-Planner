import java.security.MessageDigest
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
}

val appVersion = providers.gradleProperty("appVersion").get()
val selfHostedWebName = "eve-map-web-$appVersion"
val selfHostedWebStage = layout.buildDirectory.dir("self-hosted-web/site")
val selfHostedWebDistributions = layout.buildDirectory.dir("distributions")
val webProductionDirectory = project(":web-client").layout.buildDirectory.dir("dist/js/productionExecutable")
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

val prepareSelfHostedWeb by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Validates and stages the production Web client plus a Desktop-exported Web Pack."
    dependsOn(":web-client:webProduction")
    from(webProductionDirectory)
    into(selfHostedWebStage)
    exclude("**/*.map")

    doFirst {
        if (!providers.gradleProperty("webPackDir").isPresent) {
            throw GradleException(
                "assembleSelfHostedWeb requires -PwebPackDir=<Desktop EVE-Web-Pack directory>",
            )
        }
    }

    doLast {
        val site = selfHostedWebStage.get().asFile
        listOf(
            "index.html",
            "web-client.js",
            "web-client.css",
            "web-pack-loader.mjs",
            "pwa-runtime.mjs",
            "manifest.webmanifest",
            "service-worker.js",
            "icons/app-icon-192.png",
            "icons/app-icon-512.png",
            "data/manifest.json",
        ).forEach { relativePath ->
            val file = site.resolve(relativePath)
            if (!file.isFile || file.length() == 0L) {
                throw GradleException("Self-hosted Web artifact is missing required file: $relativePath")
            }
        }

        val manifestFile = site.resolve("data/manifest.json")
        val manifest = manifestFile.readText(Charsets.UTF_8)
        fun stringField(name: String): String = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(manifest)
            ?.groupValues
            ?.get(1)
            ?: throw GradleException("Web Pack manifest is missing $name")
        fun longField(name: String): Long = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*([0-9]+)")
            .find(manifest)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: throw GradleException("Web Pack manifest is missing or has invalid $name")
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        val schemaVersion = longField("schemaVersion")
        val packVersion = stringField("packVersion")
        val packFileName = stringField("fileName")
        val desktopAppVersion = stringField("desktopAppVersion")
        val expectedSize = longField("sizeBytes")
        val expectedSha256 = stringField("sha256")
        val sdeBuild = longField("sdeBuild")
        if (schemaVersion != 1L) throw GradleException("Unsupported Web Pack schemaVersion: $schemaVersion")
        if (desktopAppVersion != appVersion) {
            throw GradleException(
                "Web Pack Desktop version $desktopAppVersion does not match Web release version $appVersion",
            )
        }
        if (!packVersion.matches(Regex("[A-Za-z0-9._-]+"))) {
            throw GradleException("Web Pack packVersion is unsafe")
        }
        if (packFileName != "web-pack-$packVersion.json.gz") {
            throw GradleException("Web Pack manifest filename does not match packVersion")
        }
        val packFile = site.resolve("data").resolve(packFileName).canonicalFile
        val dataDirectory = site.resolve("data").canonicalFile
        if (packFile.parentFile != dataDirectory || !packFile.isFile) {
            throw GradleException("Web Pack manifest references a missing or unsafe file")
        }
        if (packFile.length() != expectedSize) {
            throw GradleException("Web Pack size does not match data/manifest.json")
        }
        if (sha256(packFile) != expectedSha256) {
            throw GradleException("Web Pack SHA-256 does not match data/manifest.json")
        }

        site.resolve("self-hosted-web.json").writeText(
            """{
              "formatVersion": 1,
              "artifactType": "eve-map-self-hosted-web",
              "webVersion": "$appVersion",
              "webPackSchemaVersion": $schemaVersion,
              "webPackVersion": "$packVersion",
              "sdeBuild": $sdeBuild
            }
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }
}

tasks.register<Zip>("assembleSelfHostedWeb") {
    group = "distribution"
    description = "Builds eve-map-web-<version>.zip with SHA-256 and release metadata."
    dependsOn(prepareSelfHostedWeb)
    from(selfHostedWebStage)
    destinationDirectory.set(selfHostedWebDistributions)
    archiveFileName.set("$selfHostedWebName.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    val checksumFile = selfHostedWebDistributions.map { it.file("$selfHostedWebName.zip.sha256") }
    val metadataFile = selfHostedWebDistributions.map { it.file("$selfHostedWebName.metadata.json") }
    outputs.files(checksumFile, metadataFile)

    doLast {
        val artifact = archiveFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val artifactSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        val internalMetadata = selfHostedWebStage.get().file("self-hosted-web.json").asFile.readText(Charsets.UTF_8)
        fun metadataField(name: String): String = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(internalMetadata)
            ?.groupValues
            ?.get(1)
            ?: throw GradleException("Internal self-hosted metadata is missing $name")
        fun metadataNumber(name: String): Long = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*([0-9]+)")
            .find(internalMetadata)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: throw GradleException("Internal self-hosted metadata is missing $name")

        checksumFile.get().asFile.writeText("$artifactSha256  ${artifact.name}\n", Charsets.UTF_8)
        metadataFile.get().asFile.writeText(
            """{
              "formatVersion": 1,
              "artifactFileName": "${artifact.name}",
              "webVersion": "$appVersion",
              "webPackSchemaVersion": ${metadataNumber("webPackSchemaVersion")},
              "webPackVersion": "${metadataField("webPackVersion")}",
              "sdeBuild": ${metadataNumber("sdeBuild")},
              "sizeBytes": ${artifact.length()},
              "sha256": "$artifactSha256"
            }
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }
}
