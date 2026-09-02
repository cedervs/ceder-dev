plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.h3)
    implementation(libs.kotlinx.coroutines.core)
    // Parses the small, checked-in geographic reference artifact (see GeographicAreaReference.kt) —
    // already used elsewhere in this project (see gradle/libs.versions.toml), same pinned version.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
