plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace="dev.alex.afptorrent"; compileSdk=36; buildToolsVersion="36.0.0"
 defaultConfig { applicationId="dev.alex.afptorrent.lab"; minSdk=26; targetSdk=36; versionCode=1; versionName="0.1.0-prototype.1"; ndk { abiFilters += "arm64-v8a" } }
 buildTypes { getByName("release") { isMinifyEnabled=false } }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }; buildFeatures { aidl=true }
 testOptions { unitTests.isReturnDefaultValues=true; unitTests.isIncludeAndroidResources=true }
}
dependencies {
 implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
 implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
 implementation("androidx.documentfile:documentfile:1.0.1")
 testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303")
 testImplementation("org.robolectric:robolectric:4.14.1")
 testRuntimeOnly("org.libtorrent4j:libtorrent4j-linux:2.1.0-39")
}
