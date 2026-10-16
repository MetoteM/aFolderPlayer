import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val signingProps = Properties().apply { val file=rootProject.file("signing/signing.properties"); if(file.exists())file.inputStream().use { load(it) } }
val yandexSdkClient=providers.gradleProperty("yandexSdkClientId").orElse("").get().trim()
require(yandexSdkClient.isEmpty() || yandexSdkClient.matches(Regex("[0-9a-fA-F]{32}"))) { "Invalid yandexSdkClientId" }
android {
 namespace = "dev.alex.afponline"
 compileSdk = 36
 buildToolsVersion = "36.0.0"
 defaultConfig { applicationId = "dev.alex.afponline"; minSdk = 26; targetSdk = 36; versionCode = 9; versionName = "0.2.0-yandex.6"; manifestPlaceholders["YANDEX_CLIENT_ID"] = yandexSdkClient.ifEmpty { "unconfigured" }; buildConfigField("boolean","YANDEX_SDK_CONFIGURED",yandexSdkClient.isNotEmpty().toString()); ndk { abiFilters += "arm64-v8a" } }
 signingConfigs { create("personalRelease") {
  storeFile = rootProject.file("signing/folder-player.p12")
  storePassword = signingProps.getProperty("storePassword")
  keyAlias = "folder-player"
  keyPassword = signingProps.getProperty("storePassword")
  storeType = "PKCS12"
 } }
 buildTypes { getByName("debug") { applicationIdSuffix = ".yandexlab" }; getByName("release") { if(rootProject.file("signing/folder-player.p12").exists())signingConfig = signingConfigs.getByName("personalRelease"); isMinifyEnabled = false } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { aidl = true; buildConfig = true }
 kotlinOptions { jvmTarget = "17" }
 testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
}
dependencies {
 implementation("com.yandex.android:authsdk:3.1.3")
 implementation("androidx.activity:activity-ktx:1.9.3")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 testImplementation("org.robolectric:robolectric:4.14.1")
}

