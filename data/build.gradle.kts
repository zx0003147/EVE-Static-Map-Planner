plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commons.csv)

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("phase4Acceptance") {
    group = "verification"
    description = "Runs Phase 4 acceptance against an explicit static.db and isolated user.db"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.evestaticmapplanner.data.qa.Phase4AcceptanceCliKt")
    val acceptanceArguments = providers.gradleProperty("phase4AcceptanceArgs")
    doFirst {
        args = acceptanceArguments.orNull
            ?.split('|')
            ?.filter(String::isNotBlank)
            ?: error("Provide -Pphase4AcceptanceArgs=<static.db>|<user.db>|<synthetic.csv>|<synthetic.json>")
    }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
