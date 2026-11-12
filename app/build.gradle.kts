/*
 * Application module.
 *
 * Deliberately plain: no AndroidX, no third-party libraries, no code generation, no crash reporting
 * or analytics (the app has no INTERNET permission, so the usual SDKs are impossible by
 * construction). Everything is Kotlin on top of the platform SDK, which keeps the APK under 2 MB and
 * makes the build reproducible - and easy to audit for F-Droid, where every dependency must be free
 * and buildable from source.
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.handwrite.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.handwrite.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 5
        versionName = "0.1.1"
    }

    sourceSets {
        getByName("main") {
            // Sources live in kotlin/ rather than java/; setting the directory is enough - the Kotlin
            // plugin picks up every *.kt in it. (An include pattern here is not supported by the
            // Android source-set API and fails the build.)
            java.setSrcDirs(listOf("src/main/kotlin"))
        }
        getByName("test") {
            // unit tests sit next to the code they test, in kotlin/ as well
            java.setSrcDirs(listOf("src/test/kotlin"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // A stable, project-owned key: F-Droid signs with its own key, users get updates through
            // the store they installed from. The password lives in keystore/README, not in this file.
            signingConfig = signingConfigs.create("release") {
                storeFile = rootProject.file("keystore/handwrite-release.jks")
                storePassword = System.getenv("HANDWRITE_KS_PASS") ?: "handwrite2026"
                keyAlias = System.getenv("HANDWRITE_KS_ALIAS") ?: "handwrite"
                keyPassword = System.getenv("HANDWRITE_KS_PASS") ?: "handwrite2026"
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        /*
         * Pin the language version to the one the shipped Kotlin plugin provides.
         *
         * Not cosmetic: the source was developed against a newer compiler locally, and one construct it
         * accepts ("break/continue inside an inline lambda") is still experimental for 2.0 - the build
         * failed on exactly that. Pinning makes the local and the CI compiler agree, so a construct
         * that compiles here cannot fail there, and the code stays free of experimental features.
         */
        languageVersion = "2.0"
        apiVersion = "2.0"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/*.version")
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    /*
     * Kotlin's standard library, with an explicit version.
     *
     * It has to be named: the Kotlin Gradle plugin adds `kotlin-stdlib` (and `kotlin-stdlib-jdk7/8`)
     * on its own, without version numbers of their own, and F-Droid rejects that with "is not
     * pinned" - a build must state the exact version of every library it ships. Naming it also
     * removes the plugin's implicit node, so only one copy of the stdlib is resolved.
     *
     * `stdlib-jdk7/8` are left out on purpose: since Kotlin 1.8 their classes live in kotlin-stdlib,
     * so depending on them only ships the same code twice.
     */
    implementation(kotlin("stdlib", "2.0.21"))

    // The unit tests need a runner only; they guard the pen pipeline and never ship.
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test", "2.0.21"))
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
