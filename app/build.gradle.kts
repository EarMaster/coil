plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.coilforphoniebox"
    compileSdk = 36

    defaultConfig {
        // Immutable after publication — see docs/implementation-plan.md §10.1.
        applicationId = "app.coilforphoniebox"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    androidResources {
        // The launch locales (§12.1). Pseudolocales are not listed: they are generated for
        // the debug build by isPseudoLocalesEnabled below, after filtering.
        localeFilters += listOf("en", "de", "fr", "es", "nl")
    }

    // Supplied by .github/workflows/release.yml. A local release build has no keystore and
    // produces an unsigned APK rather than failing — the signing config is only created
    // when there is something to put in it.
    val releaseKeystore: String? = System.getenv("KEYSTORE_PATH")

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Generates en_XA / ar_XB at build time for the pseudolocale pass (§12.3).
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseKeystore?.let { signingConfigs.getByName("release") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Only for VERSION_NAME, which the about section shows.
        buildConfig = true
    }

    lint {
        // §12.2: hardcoded user-facing text must fail the build, not warn.
        error += listOf("HardcodedText")
        // Partial translations are explicitly allowed to merge (§12.5), so drift
        // stays visible without blocking a release.
        warning += listOf("MissingTranslation", "MissingQuantity")
        checkDependencies = true
        abortOnError = true
        warningsAsErrors = false
        sarifReport = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests {
            // Robolectric renders the real UI, so the unit tests need the real resources —
            // strings, colours and all five locales included.
            isIncludeAndroidResources = true
            all {
                // Rendering a full screen at 420dpi needs more heap than the 512 MB default.
                it.maxHeapSize = "2g"
            }
        }
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-transport"))
    implementation(project(":feature-media"))
    implementation(project(":feature-shortcuts"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Screenshot tests. They run as ordinary unit tests and stay inert during `./gradlew test`
    // — Roborazzi only writes or compares files when its Gradle tasks set the system property,
    // so the plain test task neither records nor fails on a golden.
    //
    // They live in the `testDebug` source set, not `test`: the activity they compose into is
    // `HiltTestActivity` from the debug source set, which the release unit test variant cannot
    // see. Scoping the dependencies the same way keeps `testReleaseUnitTest` empty rather than
    // broken.
    testDebugImplementation(platform(libs.compose.bom))
    testDebugImplementation(libs.compose.ui.test.junit4)
    testDebugImplementation(libs.robolectric)
    testDebugImplementation(libs.roborazzi)
    testDebugImplementation(libs.roborazzi.compose)
    testDebugImplementation(libs.coil.test)
    // Whole-app goldens compose CoilApp, whose screens resolve their view models through
    // `hiltViewModel()` — so the graph has to exist, with fakes in place of the repositories.
    testDebugImplementation(libs.hilt.android.testing)
    kspTestDebug(libs.hilt.compiler)
}
