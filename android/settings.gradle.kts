pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "CharacterAgent"
include(":app", ":core:domain", ":core:network", ":core:data", ":core:designsystem")
include(":feature:characters", ":feature:chat", ":feature:notes", ":feature:settings")
include(":feature:profile")
