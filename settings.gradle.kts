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

rootProject.name = "FamilyGuard"

include(
    ":app",
    ":core:common",
    ":core:ui",
    ":core:domain",
    ":core:network",
    ":core:auth",
    ":core:database",
    ":core:data",
    ":core:permissions",
    ":feature:onboarding",
    ":feature:family",
    ":feature:pairing",
    ":feature:rules",
    ":feature:reports",
    ":feature:location",
    ":feature:requests",
    ":feature:settings",
    ":feature:childhub",
    ":feature:enforcement",
    ":service:enforcement",
    ":service:location",
)
