package dev.alex.afptorrent
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TorrentRulesTest {
 @Test fun untrustedPathsCannotEscapeOrUseAmbiguousSeparators(){
  listOf("../a.mp3","/a.mp3","a/../b.mp3","a\\b.mp3","C:a.mp3","a//b.mp3","a/./b.mp3","a/\u0000.mp3").forEach { assertFalse(it,TorrentRules.safePath(it)) }
  assertTrue(TorrentRules.safePath("Альбом/CD 1/01. Песня.flac"))
  assertEquals(File("/tmp/afp/album/1.mp3"),TorrentRules.file(File("/tmp/afp"),"album/1.mp3"))
 }
 @Test fun networkPolicyDoesNotLeakIntoMobileOrRoaming(){
  assertTrue(TorrentRules.allowed(true,true,false,false,true,false))
  assertFalse(TorrentRules.allowed(true,false,true,false,true,false))
  assertFalse(TorrentRules.allowed(true,true,true,false,true,false))
  assertFalse(TorrentRules.allowed(true,true,false,true,true,false))
  assertTrue(TorrentRules.allowed(true,false,true,false,false,false))
  assertFalse(TorrentRules.allowed(false,true,false,false,false,true))
 }
 @Test fun selectedFilesCannotOverflowBudgetOrSelectExecutable(){
  val f=listOf(TorrentFile(0,"a.mp3",60,true),TorrentFile(1,"b.exe",5,false),TorrentFile(2,"c.flac",50,true))
  assertEquals(60,TorrentRules.selectedSize(f,setOf(0),100))
  for(s in listOf(emptySet(),setOf(1),setOf(0,2),setOf(3)))assertThrows(IllegalArgumentException::class.java){TorrentRules.selectedSize(f,s,100)}
 }
 @Test fun taskStateRetainsSelectionAndExportReceiptsAcrossRestart(){
  val t=TorrentTask("e8a1b476-2f18-4edf-afdc-d4f3998a830e");t.files=listOf(TorrentFile(0,"a.mp3",90,true));t.selected=setOf(0);t.exported[0]="content://music/a";t.state="complete";t.tree="content://music/tree";t.hash="0123"
  val copy=TorrentTask.read(t.json(true));assertEquals(t.selected,copy.selected);assertEquals(t.exported,copy.exported);assertEquals("complete",copy.state);assertEquals(t.hash,copy.hash)
  assertFalse(t.json(false).has("files"));assertFalse(t.json(false).has("exported"));assertEquals(1,t.json(false).getInt("exportedCount"))
 }
}
