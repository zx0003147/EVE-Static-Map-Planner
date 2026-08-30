plugins {
    kotlin("jvm") version "2.3.0"
}

repositories {
    mavenCentral()
}

providers.gradleProperty("nativeOutputDir").orNull?.let {
    layout.buildDirectory.set(file(it).resolve("build-logic"))
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

kotlin {
    jvmToolchain(25)
}
