plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(compose.desktop.currentOs)
}

kotlin {
    jvmToolchain(25)
}

compose.desktop {
    application {
        mainClass = "dev.evestaticmapplanner.MainKt"
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
