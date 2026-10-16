import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val signingProps = Properties().apply { val file=rootProject.file("signing/signing.properties"); if(file.exists())file.inputStream().use { load(it) } }
android {
 namespace = "dev.alex.afponline"
 compileSdk = 36
 buildToolsVersion = "36.0.0"
 defaultConfig { applicationId = "dev.alex.afponline"; minSdk = 26; targetSdk = 36; versionCode = 7; versionName = "0.2.0-yandex.4"; ndk { abiFilters += "arm64-v8a" } }
 signingConfigs { create("personalRelease") {
  storeFile = rootProject.file("signing/folder-player.p12")
  storePassword = signingProps.getProperty("storePassword")
  keyAlias = "folder-player"
  keyPassword = signingProps.getProperty("storePassword")
  storeType = "PKCS12"
 } }
 buildTypes { getByName("debug") { applicationIdSuffix = ".yandexlab" }; getByName("release") { if(rootProject.file("signing/folder-player.p12").exists())signingConfig = signingConfigs.getByName("personalRelease"); isMinifyEnabled = false } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { aidl = true }
 kotlinOptions { jvmTarget = "17" }
 testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
}
dependencies {
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 testImplementation("org.robolectric:robolectric:4.14.1")
}

