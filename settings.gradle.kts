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
        // Needed for sardine-android (WebDAV client), published via JitPack only -- see
        // CLOUD_SETUP.md / Cloud Libraries WebDAV provider. Kept AFTER google()/mavenCentral()
        // on purpose so those two are always resolved first for everything else.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "CalibreSync"
include(":app")
