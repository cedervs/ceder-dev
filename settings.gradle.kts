pluginManagement {
    repositories {
        google()
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

rootProject.name = "world-discovery"

include(":app")
include(":feature-map")
include(":feature-journey")
include(":feature-progress")
include(":feature-profile")
include(":core-network")
include(":core-auth")
include(":core-discovery-engine")
include(":core-database")
