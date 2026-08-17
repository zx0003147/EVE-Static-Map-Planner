plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
}

kotlin {
    jvmToolchain(25)
}
