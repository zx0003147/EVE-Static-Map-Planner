import org.gradle.api.artifacts.ProjectDependency
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
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

tasks.test {
    useJUnitPlatform()
    dependsOn(fixturePackJar)
    systemProperty("feature.api.fixture.jar", fixturePackJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}

tasks.check {
    dependsOn(verifyFeatureApiDependencies)
    dependsOn(fixturePackJar)
}
