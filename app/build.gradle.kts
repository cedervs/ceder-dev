import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Physical-device debug builds need a LAN-reachable backend URL (10.0.2.2 only resolves
// from the emulator). Each developer sets their own in the untracked local.properties.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val devApiBaseUrl = localProperties.getProperty("dev.api.base.url") ?: "http://10.0.2.2:8000/"

android {
    namespace = "com.cedervs.worlddiscovery"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cedervs.worlddiscovery"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Public OAuth web client ID (not a secret) — used as the audience for Google ID
        // tokens; the backend verifies against this same value.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"348836152616-3pc7b17vm4osslku4r70475t4ames7l4.apps.googleusercontent.com\"",
        )
    }

    buildTypes {
        debug {
            // Defaults to the emulator's host-loopback alias; override per-developer via
            // dev.api.base.url in local.properties (untracked) to test on a physical device.
            buildConfigField("String", "API_BASE_URL", "\"$devApiBaseUrl\"")
        }
        release {
            // No production backend exists yet for Auth 2A; set the real URL when one does.
            buildConfigField("String", "API_BASE_URL", "\"https://api.worlddiscovery.invalid/\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The generic `com.uber:h3` artifact (pulled in transitively via :core-discovery-engine, used
// there so its own JVM unit tests keep working with desktop natives) bundles no Android native
// library and must never reach the packaged app — see AndroidH3CellConverter. Excluding it here
// avoids a duplicate-class clash with com.uber:h3-android, which the app uses instead.
configurations.all {
    exclude(group = "com.uber", module = "h3")
}

dependencies {
    implementation(project(":feature-map"))
    implementation(project(":feature-journey"))
    implementation(project(":feature-progress"))
    implementation(project(":feature-profile"))
    implementation(project(":core-network"))
    implementation(project(":core-auth"))
    implementation(project(":core-discovery-engine"))
    implementation(project(":core-database"))
    implementation(project(":core-location"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.h3.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
