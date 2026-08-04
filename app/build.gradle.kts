plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
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
}
