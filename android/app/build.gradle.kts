// SPDX-License-Identifier: AGPL-3.0-only
plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "org.ethertaco.simlocation"
    compileSdk = 36
    defaultConfig { applicationId = "org.ethertaco.simlocation"; minSdk = 35; targetSdk = 35; versionCode = 5; versionName = "0.5.0"; testInstrumentationRunner = "org.ethertaco.simlocation.BackendTestRunner" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("../artifacts/simlocation-android/debug.keystore")
            storePassword = "android"; keyAlias = "simlocation-debug"; keyPassword = "android"
        }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/assets").get().asFile)
}
dependencies {
    compileOnly("io.github.libxposed:api:${providers.gradleProperty("xposedApi").getOrElse("101.0.1")}")
    implementation("io.github.libxposed:service:101.0.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.8.2")
    implementation("top.yukonga.miuix.kmp:miuix:0.5.1")
}
val prepareAssets by tasks.registering(Copy::class) {
    from("../../LICENSE")
    from("../THIRD_PARTY_NOTICES.md")
    into(layout.buildDirectory.dir("generated/assets"))
}
tasks.named("preBuild") { dependsOn(prepareAssets) }
