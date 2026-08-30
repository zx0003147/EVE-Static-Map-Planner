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
    dependsOn(tasks.compileTestKotlin)
    testClassesDirs = files(layout.buildDirectory.dir("classes/kotlin/test"))
    classpath = project.files(
        testClassesDirs,
        sourceSets.main.get().output,
        configurations.testRuntimeClasspath.get(),
    )
}

kotlin {
    jvmToolchain(25)
}
