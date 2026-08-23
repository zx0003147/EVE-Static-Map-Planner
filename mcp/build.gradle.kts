plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":control-transport"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.kotlin.server) {
        // The bridge is stdio-only. The official server artifact also publishes
        // optional Ktor HTTP/SSE/WebSocket dependencies, which are deliberately
        // kept off this module's production classpath.
        exclude(group = "io.ktor")
    }
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    testImplementation(project(":control"))
    testImplementation(project(":core"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mcp.kotlin.client)
    testImplementation(libs.mcp.kotlin.testing)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
