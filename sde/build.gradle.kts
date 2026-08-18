plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "dev.evestaticmapplanner.sde.cli.StaticDataCliKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("phase7Acceptance") {
    group = "verification"
    description = "Runs isolated real-CCP SDE updater acceptance against an explicit managed root"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.evestaticmapplanner.sde.qa.Phase7AcceptanceCliKt")
    val acceptanceRoot = providers.gradleProperty("phase7AcceptanceRoot")
    doFirst {
        args = listOf(acceptanceRoot.orNull ?: error("Provide -Pphase7AcceptanceRoot=<isolated-managed-root>"))
    }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
