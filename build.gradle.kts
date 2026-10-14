// Top-level build file. Individual module build logic lives in app/build.gradle.kts.
plugins {
    // 8.6.1, not 8.5.2: a transitive dependency (androidx.core:core 1.16.0, pulled in indirectly
    // by other androidx libs) requires AGP 8.6.0+. AGP 8.6.x still supports compileSdk up to 35
    // (this project's setting) and requires Gradle 8.7, which this project's wrapper is already
    // pinned to (see gradle/wrapper/gradle-wrapper.properties) -- so bumping just this one number
    // is the whole fix, no compileSdk/Gradle-version change needed alongside it.
    id("com.android.application") version "8.6.1" apply false
    // Added for the in-app EPUB reader: Readium's kotlin-toolkit is a Kotlin-only library
    // (Fragment factories, coroutines StateFlow for currentLocator, etc.), so this project is
    // no longer pure Java -- app/src/main/java now also contains a handful of .kt files
    // (EpubReaderActivity and friends). Java and Kotlin sources coexist fine in the same
    // source set with AGP; PDF/CBZ readers and everything else stayed plain Java.
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
