plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
}

group = "dev.evestaticmapplanner"
version = "0.1.0-SNAPSHOT"

tasks.named("build") {
    dependsOn(
        ":app:build",
        ":core:build",
        ":data:build",
        ":sde:build",
    )
}
