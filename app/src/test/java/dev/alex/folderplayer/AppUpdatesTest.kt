package dev.alex.folderplayer

import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AppUpdatesTest {
 private fun release(v:String="0.6.0")=JSONObject().put("tag_name","v$v").put("published_at","2026-10-09T12:00:00Z").put("assets",JSONArray().put(JSONObject().put("name","aFolderPlayer_${v}.apk").put("browser_download_url","https://github.com/MetoteM/aFolderPlayer/releases/download/v$v/aFolderPlayer_${v}.apk").put("state","uploaded").put("size",4000000).put("digest","sha256:"+"a".repeat(64))))
 private fun find(vararg r:JSONObject)=AppUpdates.candidate(JSONArray(r.toList()).toString().toByteArray(),"0.5.2")
 @Test fun selectsNewestPlayerRegardlessOfOrderAndSkipsExtensionRelease() {
  val extensions=release().put("tag_name","extensions-v1")
  assertEquals("0.10.0",find(extensions,release("0.6.0"),release("0.10.0"),release("0.5.2"))!!.version)
  assertNull(find(release("0.5.1"),release("0.5.2")))
 }
 @Test fun draftPreviewAndUnpublishedReleasesAreIgnored() {
  assertNull(find(release().put("draft",true),release().put("prerelease",true),release().put("published_at",JSONObject.NULL)))
 }
 @Test fun rejectsMissingIntegrityWrongRepositoryAndOversizedAssets() {
  for(change in listOf("digest" to "", "digest" to "sha256:garbage", "size" to 60000001L,"size" to -1L,"browser_download_url" to "https://evil.example/update.apk", "name" to "AFP_Translate_0.1.0.apk","state" to "starter")) {
   val r=release();r.getJSONArray("assets").getJSONObject(0).put(change.first,change.second);assertNull(find(r))
  }
 }
 @Test fun versionComparisonIsNumericAndRejectsUnsupportedTags() {
  assertTrue(AppUpdates.compare(AppUpdates.version("0.10.0")!!,AppUpdates.version("0.9.9")!!)>0)
  for(s in listOf("0.6","0.6.0-beta","0.6.0+dev","999999999999.0.0","extensions-v1"))assertNull(AppUpdates.version(s))
 }
 @Suppress("DEPRECATION") private fun info(version:String="0.6.0",code:Int=25,signature:Byte=1)=PackageInfo().apply {
  packageName="dev.alex.folderplayer";versionName=version;versionCode=code;signatures=arrayOf(Signature(byteArrayOf(signature)))
  signingInfo=android.content.pm.SigningInfo().also {
   org.robolectric.Shadows.shadowOf(it).setSignatures(signatures)
  }
 }
 @Test fun acceptsOnlyMatchingIdentityAndStrictlyNewerApk() {
  val own=info("0.5.2",24)
  AppUpdates.validateInfo(info(),own,own.packageName,"0.6.0")
  val bad=listOf(info(signature=2),info(code=24),info(code=23),info(version="0.7.0"),info().apply { packageName="dev.alex.afptranslate" },info().apply { signingInfo=null;signatures=emptyArray() })
  for(apk in bad)assertThrows(IllegalArgumentException::class.java) { AppUpdates.validateInfo(apk,own,own.packageName,"0.6.0") }
 }
 @Test fun installedRcCanUpgradeToMatchingStableButNeverSelectsOlderOrPrerelease() {
  val bytes=JSONArray().put(release("0.8.3")).put(release("0.8.4")).put(release("0.8.5").put("prerelease",true)).toString().toByteArray()
  assertEquals("0.8.4",AppUpdates.candidate(bytes,"0.8.4-rc.1")!!.version)
  assertNull(AppUpdates.candidate(JSONArray().put(release("0.8.3")).toString().toByteArray(),"0.8.4-rc.1"))
  assertNull(AppUpdates.candidate(bytes,"0.8.4"))
 }

 @Test fun downloadedApkMustStillMatchOriginalHashAndSizeAfterPermissionReturn() {
  val file=java.io.File.createTempFile("afp-update", ".apk")
  try {
   file.writeBytes(byteArrayOf(1,2,3))
   val hash=java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
   AppUpdates.verifyDownload(file,3,hash)
   file.writeBytes(byteArrayOf(1,2,4))
   assertThrows(IllegalArgumentException::class.java) { AppUpdates.verifyDownload(file,3,hash) }
   assertThrows(IllegalArgumentException::class.java) { AppUpdates.verifyDownload(file,2,hash) }
   assertThrows(IllegalArgumentException::class.java) { AppUpdates.verifyDownload(file,-1,hash) }
  } finally { file.delete() }
 }
}
