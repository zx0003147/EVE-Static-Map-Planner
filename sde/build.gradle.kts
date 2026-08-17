plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
}

kotlin {
    jvmToolchain(25)
}
