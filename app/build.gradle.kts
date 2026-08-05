import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

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
        versionCode = 4
        versionName = "1.0.0"
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

// ---------------------------------------------------------------- store assets
//
// `StoreAssetTest` writes the Play listing screenshots straight into the fastlane layout, but
// it cannot finish the job: Roborazzi writes RGBA, Play rejects any image with an alpha
// channel, and `javax.imageio` is not on an Android unit test's classpath. So the last two
// steps happen here, where the full JDK is available.
//
// Paths are resolved inside the configuration block and captured as plain Files. They cannot be
// script-level `val`s: reading one from `doLast` captures the build script object itself, which
// the configuration cache refuses to serialise.
val flattenStoreAssets = tasks.register("flattenStoreAssets") {
    group = "publishing"
    description = "Flattens the Play Store screenshots to 24-bit PNG and copies the phone set " +
        "to the Pages site."

    val storeImages = rootProject.layout.projectDirectory
        .dir("fastlane/metadata/android/en-US/images").asFile
    val pagesScreenshots = rootProject.layout.projectDirectory
        .dir("docs/pages/assets/screenshots").asFile

    doLast {
        if (!storeImages.isDirectory) {
            logger.lifecycle("No store images at $storeImages — nothing to flatten.")
            return@doLast
        }

        // The app's own light background (md_light_background). It only ever shows through
        // antialiased edges, and white would fringe them.
        val background = Color(0xF5FAF6)
        var flattened = 0

        storeImages.walkTopDown().filter { it.isFile && it.extension == "png" }.forEach { file ->
            val source = ImageIO.read(file) ?: return@forEach
            val opaque = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
            opaque.createGraphics().apply {
                color = background
                fillRect(0, 0, source.width, source.height)
                drawImage(source, 0, 0, null)
                dispose()
            }
            ImageIO.write(opaque, "png", file)
            flattened++
        }

        // The website is served from docs/pages only, so it needs its own copy rather than a
        // link into the fastlane tree.
        //
        // All three sets are copied, not just the ones the site currently shows. Which tablet
        // images are worth showing is a content decision — some screens still have a lot of dead
        // space at 1280 dp — and it belongs in docs/pages/_config.yml's `tablet_screenshots`
        // list, not in a build script. Copying everything means changing that list never
        // requires touching Gradle or re-recording.
        var copied = 0
        listOf(
            "phoneScreenshots" to pagesScreenshots,
            "sevenInchScreenshots" to pagesScreenshots.resolve("tablet7"),
            "tenInchScreenshots" to pagesScreenshots.resolve("tablet10"),
        ).forEach { (setName, destination) ->
            val source = storeImages.resolve(setName)
            if (!source.isDirectory) return@forEach
            destination.mkdirs()
            source.listFiles { file -> file.extension == "png" }?.forEach { file ->
                file.copyTo(destination.resolve(file.name), overwrite = true)
                copied++
            }
        }

        logger.lifecycle(
            "Flattened $flattened store screenshot(s); copied $copied to the Pages site."
        )
    }
}

// Recording is the only thing that writes those files, so it is the only thing that needs to
// finish the job. A record run that captured no store assets simply finds nothing to do.
tasks.matching { it.name == "recordRoborazziDebug" }.configureEach { finalizedBy(flattenStoreAssets) }

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
