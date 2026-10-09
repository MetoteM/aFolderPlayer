import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val signingProps = Properties().apply { val file=rootProject.file("signing/signing.properties"); if(file.exists())file.inputStream().use { load(it) } }
android {
 namespace = "dev.alex.folderplayer"
 compileSdk = 36
 buildToolsVersion = "36.0.0"
 defaultConfig { applicationId = "dev.alex.folderplayer"; minSdk = 26; targetSdk = 36; versionCode = 24; versionName = "0.5.2"; ndk { abiFilters += "arm64-v8a" } }
 signingConfigs { create("personalRelease") {
  storeFile = rootProject.file("signing/folder-player.p12")
  storePassword = signingProps.getProperty("storePassword")
  keyAlias = "folder-player"
  keyPassword = signingProps.getProperty("storePassword")
  storeType = "PKCS12"
 } }
 buildTypes { getByName("release") { if(rootProject.file("signing/folder-player.p12").exists())signingConfig = signingConfigs.getByName("personalRelease"); isMinifyEnabled = false } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { aidl = true }
 kotlinOptions { jvmTarget = "17" }
 testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
}
dependencies {
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.14.1")
 implementation("androidx.media3:media3-exoplayer:1.6.1")
 implementation("androidx.media3:media3-session:1.6.1")
 implementation("androidx.documentfile:documentfile:1.0.1")
}
