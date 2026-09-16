plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(project(":control"))
    implementation(project(":shared-client"))
    implementation(libs.koog.agents.core)
    implementation(libs.koog.http.client.java)
    implementation(libs.koog.prompt.executor.openai.client)
    implementation(libs.koog.prompt.executor.openrouter.client)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.koog.agents.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
