plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.character.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.character.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.6.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // D27: device checks have their own sandbox; reruns never clear the user's development app.
    buildTypes {
        create("deviceCheck") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".devicecheck"
            matchingFallbacks += "debug"
        }
    }
    sourceSets.getByName("deviceCheck") {
        manifest.srcFile("src/debug/AndroidManifest.xml")
        res.srcDir("src/debug/res")
    }
    // Keep the existing debug unit-test tasks available during normal builds.
    testBuildType = if (providers.gradleProperty("deviceChecks").orNull == "true") "deviceCheck" else "debug"
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:characters"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:notes"))
    implementation(project(":feature:settings"))
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.8.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
