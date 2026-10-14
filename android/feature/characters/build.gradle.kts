plugins { id("com.android.library"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.character.app.characters"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    testImplementation("junit:junit:4.13.2")
}
