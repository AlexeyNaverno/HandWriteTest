/*
 * Root build file.
 *
 * The Android Gradle plugin version is pinned here (and only here) so that a release build is
 * reproducible: the same tag always produces the same APK metadata.
 */
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
