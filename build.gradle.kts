plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
}

val appVersion = providers.gradleProperty("appVersion").get()

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
        ":mcp:build",
        ":sde:build",
    )
}
