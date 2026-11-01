pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "bandwagon"

include(":app")
include(":core:model")
include(":core:health")
include(":core:data")
include(":core:sync")
include(":core:ui")
include(":core:notifications")
include(":feature:onboarding")
include(":feature:groups")
include(":feature:challenges")
include(":feature:home")
include(":feature:health")
include(":feature:you")
include(":tools:hc-seeder")
