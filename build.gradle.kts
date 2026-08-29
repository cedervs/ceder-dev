plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "9.7.1"
    distributionType = Wrapper.DistributionType.BIN
}
