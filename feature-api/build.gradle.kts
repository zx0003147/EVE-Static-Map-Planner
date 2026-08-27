import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Exec
import org.gradle.jvm.tasks.Jar
import org.w3c.dom.Element
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

val featureApiArtifactVersion = providers.gradleProperty("featureApiArtifactVersion").get()
version = featureApiArtifactVersion

java {
    withSourcesJar()
}

dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

val featureApiTestRepository = layout.buildDirectory.dir("test-maven-repository")
val enableGitHubPackagesPublication = providers.gradleProperty("enableFeatureApiGitHubPackagesPublication")
    .map(String::toBooleanStrict)
    .orElse(false)

publishing {
    publications {
        register<MavenPublication>("featureApi") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "feature-api"
            version = featureApiArtifactVersion
        }
    }
    repositories {
        maven {
            name = "featureApiTest"
            url = uri(featureApiTestRepository)
        }
        if (enableGitHubPackagesPublication.get()) {
            maven {
                name = "githubPackages"
                url = uri("https://maven.pkg.github.com/zx0003147/EVE-Static-Map-Planner")
                credentials {
                    username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    password = providers.environmentVariable("GITHUB_TOKEN").orNull
                }
            }
        }
    }
}

val fixturePack = sourceSets.create("fixturePack")

dependencies {
    add(fixturePack.implementationConfigurationName, sourceSets.main.get().output)
    add(fixturePack.implementationConfigurationName, kotlin("stdlib"))
}

val fixturePackJar by tasks.registering(Jar::class) {
    archiveClassifier.set("fixture-pack")
    from(fixturePack.output)
    manifest {
        attributes(
            "EVE-Feature-Pack-Id" to "fixture.pack",
            "EVE-Feature-Pack-Name" to "Minimal Fixture Pack",
            "EVE-Feature-Pack-Version" to "0.0.1-test",
            "EVE-Feature-Pack-Publisher" to "EVE Static Map Planner Tests",
            "EVE-Feature-API-Version" to "1",
        )
    }
}

val fixturePackElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(fixturePackElements.name, fixturePackJar)
}

val verifyFeatureApiDependencies by tasks.registering {
    group = "verification"
    description = "Verifies that the production Feature API has no project or forbidden library dependencies."
    doLast {
        val productionConfigurations = listOf("api", "implementation", "compileOnly", "runtimeOnly")
        val projectDependencies = productionConfigurations.flatMap { configurationName ->
            configurations.getByName(configurationName).dependencies.withType(ProjectDependency::class.java)
        }
        check(projectDependencies.isEmpty()) {
            "feature-api must not depend on another project: ${projectDependencies.map { it.path }}"
        }

        val forbiddenDependencyFragments = listOf(
            "compose",
            "coroutines",
            "sqlite",
            "modelcontextprotocol",
        )
        val forbiddenDependencies = productionConfigurations.flatMap { configurationName ->
            configurations.getByName(configurationName).dependencies
        }.filter { dependency ->
            val coordinate = "${dependency.group.orEmpty()}:${dependency.name}".lowercase()
            forbiddenDependencyFragments.any(coordinate::contains)
        }
        check(forbiddenDependencies.isEmpty()) {
            "feature-api has forbidden production dependencies: " +
                forbiddenDependencies.joinToString { "${it.group}:${it.name}" }
        }
    }
}

val publicationDirectory = featureApiTestRepository.map {
    it.dir("dev/evestaticmapplanner/feature-api/$featureApiArtifactVersion")
}
val publishedBinaryJar = publicationDirectory.map { it.file("feature-api-$featureApiArtifactVersion.jar") }
val publishedSourcesJar = publicationDirectory.map { it.file("feature-api-$featureApiArtifactVersion-sources.jar") }
val publishedPom = publicationDirectory.map { it.file("feature-api-$featureApiArtifactVersion.pom") }
val publishFeatureApiToTestRepository = tasks.named(
    "publishFeatureApiPublicationToFeatureApiTestRepository",
)

val verifyFeatureApiPublication by tasks.registering {
    group = "verification"
    description = "Verifies the coordinate-based Feature API publication in the generated test Maven repository."
    dependsOn(publishFeatureApiToTestRepository)
    inputs.files(publishedBinaryJar, publishedSourcesJar, publishedPom)

    doLast {
        val binaryJar = publishedBinaryJar.get().asFile
        val sourcesJar = publishedSourcesJar.get().asFile
        val pom = publishedPom.get().asFile
        check(binaryJar.isFile) { "Published Feature API binary JAR is missing: $binaryJar" }
        check(sourcesJar.isFile) { "Published Feature API sources JAR is missing: $sourcesJar" }
        check(pom.isFile) { "Published Feature API POM is missing: $pom" }

        JarFile(binaryJar).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toList()
            val expectedClasses = setOf(
                "dev/evestaticmapplanner/feature/api/FeatureApiVersions.class",
                "dev/evestaticmapplanner/feature/api/FeaturePackEntrypoint.class",
                "dev/evestaticmapplanner/feature/api/OverlayProvider.class",
                "dev/evestaticmapplanner/feature/api/SystemInfoProvider.class",
            )
            check(entries.containsAll(expectedClasses)) {
                "Published Feature API JAR is missing public contract classes: ${expectedClasses - entries.toSet()}"
            }
            val foreignClasses = entries.filter { entry ->
                entry.endsWith(".class") && !entry.startsWith("dev/evestaticmapplanner/feature/api/")
            }
            check(foreignClasses.isEmpty()) {
                "Published Feature API JAR contains non-API implementation classes: $foreignClasses"
            }
            check(entries.none { it.startsWith("dev/evestaticmapplanner/feature/fixture/") }) {
                "Published Feature API JAR contains the generic fixture Pack"
            }
        }

        JarFile(sourcesJar).use { jar ->
            val sourceEntries = jar.entries().asSequence().map { it.name }.filter { it.endsWith(".kt") }.toList()
            check(sourceEntries.isNotEmpty()) { "Published Feature API sources JAR contains no Kotlin sources" }
            check(sourceEntries.all { it.startsWith("dev/evestaticmapplanner/feature/api/") }) {
                "Published sources JAR contains non-API sources: " +
                    sourceEntries.filterNot { it.startsWith("dev/evestaticmapplanner/feature/api/") }
            }
        }

        val documentFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = documentFactory.newDocumentBuilder().parse(pom)
        val projectElement = document.documentElement
        fun Element.directChildText(name: String): String? = (0 until childNodes.length).asSequence()
            .map(childNodes::item)
            .filterIsInstance<Element>()
            .firstOrNull { it.tagName == name }
            ?.textContent
            ?.trim()

        check(projectElement.directChildText("groupId") == "dev.evestaticmapplanner") {
            "Published POM has the wrong groupId"
        }
        check(projectElement.directChildText("artifactId") == "feature-api") {
            "Published POM has the wrong artifactId"
        }
        check(projectElement.directChildText("version") == featureApiArtifactVersion) {
            "Published POM has the wrong artifact version"
        }

        val forbiddenDependencyFragments = listOf(
            "dev.evestaticmapplanner:app",
            "dev.evestaticmapplanner:core",
            "dev.evestaticmapplanner:data",
            "dev.evestaticmapplanner:sde",
            "dev.evestaticmapplanner:mcp",
            "dev.evestaticmapplanner:sovereignty-pack",
            "compose",
            "sqlite",
        )
        val dependencyNodes = document.getElementsByTagName("dependency")
        val dependencies = (0 until dependencyNodes.length).asSequence()
            .map(dependencyNodes::item)
            .filterIsInstance<Element>()
            .map { dependency ->
                "${dependency.directChildText("groupId")}:${dependency.directChildText("artifactId")}".lowercase()
            }
            .toList()
        val forbiddenDependencies = dependencies.filter { coordinate ->
            forbiddenDependencyFragments.any(coordinate::contains)
        }
        check(forbiddenDependencies.isEmpty()) {
            "Published POM contains forbidden Core or Pack dependencies: $forbiddenDependencies"
        }
    }
}

val coordinateConsumerDirectory = layout.projectDirectory.dir("src/test/fixtures/coordinate-consumer")
val coordinateConsumerPackJar = coordinateConsumerDirectory.file("build/libs/pack.jar")
val verifyFeatureApiCoordinateConsumer by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds an independent thin Pack fixture against the generated Feature API Maven coordinates."
    dependsOn(verifyFeatureApiPublication)
    inputs.files(
        coordinateConsumerDirectory.file("settings.gradle.kts"),
        coordinateConsumerDirectory.file("build.gradle.kts"),
        coordinateConsumerDirectory.file("gradle.properties"),
    )
    inputs.dir(coordinateConsumerDirectory.dir("src"))
    inputs.dir(featureApiTestRepository)
    outputs.file(coordinateConsumerPackJar)
    outputs.upToDateWhen { false }

    val wrapper = rootProject.file(if (System.getProperty("os.name").startsWith("Windows", true)) "gradlew.bat" else "gradlew")
    commandLine(
        wrapper.absolutePath,
        "--no-daemon",
        "--console=plain",
        "-p",
        coordinateConsumerDirectory.asFile.absolutePath,
        "clean",
        "test",
        "verifyThinPack",
        "-PfeatureApiRepository=${featureApiTestRepository.get().asFile.absolutePath}",
        "-PfeatureApiVersion=$featureApiArtifactVersion",
    )
}

tasks.withType<PublishToMavenRepository>().configureEach {
    if (repository.name == "githubPackages") {
        doFirst {
            check(!System.getenv("GITHUB_ACTOR").isNullOrBlank()) {
                "GITHUB_ACTOR is required for explicit Feature API GitHub Packages publication"
            }
            check(!System.getenv("GITHUB_TOKEN").isNullOrBlank()) {
                "GITHUB_TOKEN is required for explicit Feature API GitHub Packages publication"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(fixturePackJar)
    systemProperty("feature.api.fixture.jar", fixturePackJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}

tasks.check {
    dependsOn(verifyFeatureApiDependencies)
    dependsOn(verifyFeatureApiPublication)
    dependsOn(verifyFeatureApiCoordinateConsumer)
    dependsOn(fixturePackJar)
}
