plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cedervs.worlddiscovery.feature.map"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-location"))
    implementation(project(":core-discovery-engine"))
    implementation(libs.kotlinx.coroutines.android)
    // Parses the rendering-only OSM-derived mainland France polygon (MainlandFranceRenderingPolygon.kt)
    // -- same pinned version already used by core-discovery-engine's own GeographicAreaReference.kt for
    // the (separate, classification-only) geoBoundaries resource.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // MapLibre Native, the decided rendering engine (docs/ai-context/ARCHITECTURE_DECISIONS.md).
    // The OpenGL ES variant, not the bare `android-sdk` artifact: the latter requires Vulkan
    // 1.0 (`<uses-feature android:required="true">`), which would exclude devices without it —
    // verified by inspecting both AARs' manifests. Not a tile/style provider choice; see
    // DiscoveryMapView's doc comment for that separate, still-open decision.
    implementation(libs.maplibre.android.sdk.opengl)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
