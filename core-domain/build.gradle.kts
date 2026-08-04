plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately a plain JVM module: models, repository interfaces and use cases
// have no reason to know about Android.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // @Inject only; the DI container itself stays out of this module.
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
