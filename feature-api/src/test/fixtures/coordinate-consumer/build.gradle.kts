import org.gradle.jvm.tasks.Jar
import java.util.jar.JarFile

plugins {
    kotlin("jvm") version "2.3.0"
}

val featureApiVersion = providers.gradleProperty("featureApiVersion").get()

dependencies {
    compileOnly("dev.evestaticmapplanner:feature-api:$featureApiVersion")
    testImplementation("dev.evestaticmapplanner:feature-api:$featureApiVersion")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.jar {
    archiveFileName.set("pack.jar")
    manifest {
        attributes(
            "EVE-Feature-Pack-Id" to "coordinate.consumer.fixture",
            "EVE-Feature-Pack-Name" to "Coordinate Consumer Fixture",
            "EVE-Feature-Pack-Version" to "0.0.1-test",
            "EVE-Feature-Pack-Publisher" to "EVE Static Map Planner Tests",
            "EVE-Feature-API-Version" to "1",
        )
    }
}

tasks.test {
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

val verifyThinPack by tasks.registering {
    group = "verification"
    description = "Verifies that coordinate-based compileOnly consumption keeps Feature API classes out of pack.jar."
    dependsOn(tasks.jar)
    val packJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(packJar)

    doLast {
        JarFile(packJar.get().asFile).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toList()
            check(entries.contains("dev/evestaticmapplanner/fixture/CoordinateConsumerPack.class")) {
                "Coordinate consumer implementation is missing from pack.jar"
            }
            check(entries.none { it.startsWith("dev/evestaticmapplanner/feature/api/") }) {
                "Coordinate consumer pack.jar bundles Host-owned Feature API classes"
            }
            check(entries.none { it.startsWith("kotlin/") }) {
                "Coordinate consumer pack.jar bundles Kotlin stdlib"
            }
        }
    }
}
