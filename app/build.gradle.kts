plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlintCore.get())
}

// ---- Release versioning (single source of truth: literals below) ----
// Bump via scripts/release.sh. versionCode MUST increase on every release:
// Play/Firebase silently skip same-code updates. Literals (not computed
// values) so F-Droid's fdroid checkupdates can read them at the release tag.

// ---- Release signing (credentials from ~/.gradle/gradle.properties) ----
val releaseStoreFile = project.findProperty("ASTIKO_KEYSTORE_FILE") as String?
val releaseStorePassword = project.findProperty("ASTIKO_KEYSTORE_PASSWORD") as String?
val releaseKeyAlias = project.findProperty("ASTIKO_KEY_ALIAS") as String?
val releaseKeyPassword = project.findProperty("ASTIKO_KEY_PASSWORD") as String?
val hasReleaseSigning =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
        .all { !it.isNullOrBlank() }

android {
    namespace = "app.astiko"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.astiko"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.2"
    }

    bundle {
        language {
            // The in-app language switch (System/Greek/English) resolves
            // resources at runtime. Play's per-language bundle split
            // would ship only the base resources and break the switch for
            // split-downloaded installs (lint: AppBundleLocaleChanges).
            enableSplit = false
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            ndk {
                // Release ships only the ARM ABIs. MapLibre's x86/x86_64
                // .so files are emulator-only (the Apple-Silicon emulator
                // runs arm64-v8a natively). Debug keeps ALL ABIs
                // so x86_64 hosts (Intel/AMD emulators, most
                // contributors) can install debug builds.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            // R8 code shrinking + optimization.
            optimization {
                enable = true
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "Release signing not configured. APK will be UNSIGNED. " +
                        "Set ASTIKO_KEYSTORE_FILE, ASTIKO_KEYSTORE_PASSWORD, " +
                        "ASTIKO_KEY_ALIAS and ASTIKO_KEY_PASSWORD in ~/.gradle/gradle.properties",
                )
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the HTTP logging interceptor (AppContainer)
        buildConfig = true
    }
    testOptions {
        unitTests {
            // android.util.Log (and other android.* stubs) return defaults
            // instead of throwing "not mocked". The polling ViewModels
            // log failed polls with Log.w, which unit tests exercise.
            // CAUTION: this also silently defaults ANY new android.* call
            // in tests. Prefer pure-Kotlin seams over touching android.*
            // in unit tests.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.maplibre)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
