plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace="org.retrotv.pad"
    compileSdk=35
    defaultConfig {
        applicationId="org.retrotv.pad"
        minSdk=28
        targetSdk=35
        versionCode=12
        versionName="0.3.6-dev"
        testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    lint { abortOnError=true }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
