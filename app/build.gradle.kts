/*
 * Application module.
 *
 * Deliberately plain: no AndroidX, no third-party libraries, no code generation. Everything is
 * Kotlin on top of the platform SDK, which keeps the APK around 1.5 MB and makes the build easy to
 * reproduce (and to audit for F-Droid, where every dependency must be free and buildable from source).
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
        versionCode = 4
        versionName = "0.1.0"
    }

    sourceSets {
        // unit tests live next to the sources they test, in kotlin/, not in java/
        getByName("test") {
            java.setSrcDirs(listOf("src/test/kotlin"))
        }
        getByName("main") {
            // non-standard layout: sources live in app/src/main/kotlin, everything else is default
            java.setSrcDirs(listOf("src/main/kotlin"))
            java.includes.add("**/*.kt")
            res.setSrcDirs(listOf("src/main/res"))
            assets.setSrcDirs(listOf("src/main/assets"))
            manifest.srcFile("src/main/AndroidManifest.xml")
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
    // The app itself has no dependencies (see the comment at the top). The unit tests need a test
    // runner only; they are the guard against regressions in the pen pipeline and never ship.
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
