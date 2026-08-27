pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "generatedFeatureApi"
                    url = uri(providers.gradleProperty("featureApiRepository").get())
                }
            }
            filter {
                includeGroup("dev.evestaticmapplanner")
            }
        }
        mavenCentral {
            content {
                excludeGroup("dev.evestaticmapplanner")
            }
        }
    }
}

rootProject.name = "feature-api-coordinate-consumer"
