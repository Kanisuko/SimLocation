// SPDX-License-Identifier: AGPL-3.0-only
plugins { id("com.android.application") }
android {
    namespace = "org.ethertaco.simlocation.systemprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.ethertaco.simlocation.systemprobe"
        minSdk = 35; targetSdk = 35; versionCode = 2; versionName = "0.1.1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("../artifacts/simlocation-android/debug.keystore")
            storePassword = "android"; keyAlias = "simlocation-debug"; keyPassword = "android"
        }
    }
}
// One source set, both modern API compile checks. Framework supplies the API at runtime.
dependencies { compileOnly("io.github.libxposed:api:${providers.gradleProperty("xposedApi").getOrElse("101.0.1")}") }
