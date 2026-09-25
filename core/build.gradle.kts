plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain {
            kotlin.srcDir("src/main/kotlin")
            kotlin.exclude(
                "dev/evestaticmapplanner/core/ansiblex/**",
                "dev/evestaticmapplanner/core/alliance/**",
                "dev/evestaticmapplanner/core/marker/**",
                "dev/evestaticmapplanner/core/repository/AnsiblexRepository.kt",
                "dev/evestaticmapplanner/core/repository/CachingStaticMapRepository.kt",
                "dev/evestaticmapplanner/core/repository/SavedMarkerRepository.kt",
                "dev/evestaticmapplanner/core/jump/JumpRangeOverlayCollection.kt",
                "dev/evestaticmapplanner/core/map/MapSceneCache.kt",
                "dev/evestaticmapplanner/core/route/DesktopRouteGraph.kt",
            )
        }
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
            kotlin.include(
                "dev/evestaticmapplanner/core/ansiblex/**",
                "dev/evestaticmapplanner/core/alliance/**",
                "dev/evestaticmapplanner/core/marker/**",
                "dev/evestaticmapplanner/core/repository/AnsiblexRepository.kt",
                "dev/evestaticmapplanner/core/repository/CachingStaticMapRepository.kt",
                "dev/evestaticmapplanner/core/repository/SavedMarkerRepository.kt",
                "dev/evestaticmapplanner/core/jump/JumpRangeOverlayCollection.kt",
                "dev/evestaticmapplanner/core/map/MapSceneCache.kt",
                "dev/evestaticmapplanner/core/route/DesktopRouteGraph.kt",
            )
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}

tasks.named<Test>("jvmTest") { useJUnitPlatform() }
