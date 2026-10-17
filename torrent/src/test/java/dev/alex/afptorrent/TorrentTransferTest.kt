package dev.alex.afptorrent

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.*
import org.junit.Test
import org.libtorrent4j.*
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t

/** Owned random fixture, loopback peers only: no public tracker or copyrighted media. */
class TorrentTransferTest {
 private fun waitFor(seconds:Int=45,condition:()->Boolean){val end=System.nanoTime()+seconds*1_000_000_000L;while(System.nanoTime()<end){if(condition())return;Thread.sleep(100)};assertTrue("Timed out waiting for BitTorrent state",condition())}
 private fun isolated(port:Int):SettingsPack=SettingsPack().listenInterfaces("127.0.0.1:$port").apply {
  setEnableDht(false);setEnableLsd(false);setBoolean(settings_pack.bool_types.enable_upnp.swigValue(),false);setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(),false)
  setBoolean(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(),true)
 }
 @Test fun realPeersRespectSelectionResumeAndStrictZeroPayloadUpload(){
  val base=Files.createTempDirectory("afp-transfer-").toFile();val seed=SessionManager(false);val consumer=SessionManager(false);val engine=TorrentEngine()
  try {
   val input=File(base,"seed/album").apply { mkdirs() };val data=ByteArray(2*1024*1024).apply { Random(7).nextBytes(this) }
   File(input,"owned-audio.mp3").writeBytes(data);File(input,"unselected.bin").writeBytes(ByteArray(512*1024).apply { Random(8).nextBytes(this) })
   val encoded=TorrentBuilder().path(input).pieceSize(16384).generate().entry().bencode();val info=TorrentInfo(encoded)
   val files=engine.describe(info);val chosen=files.single { it.path.endsWith("owned-audio.mp3") }.index
   seed.start(SessionParams(isolated(49171)));seed.download(info,input.parentFile)
   waitFor { seed.find(info.infoHash())?.status(true)?.isSeeding==true }
   engine.start();engine.session.applySettings(isolated(49172));TorrentEngine.noUploadPeers(engine.session)
   val download=File(base,"download");engine.add(info,download,setOf(chosen));engine.session.resume()
   waitFor { engine.handle(info)!=null }
   var handle=engine.handle(info)!!;handle.unsetFlags(TorrentFlags.AUTO_MANAGED);handle.resume();handle.setDownloadLimit(128*1024)
   handle.swig().connect_peer(TcpEndpoint("127.0.0.1",49171).swig())
   waitFor { handle.status(true).totalWantedDone()>16384 }
   handle.pause();engine.close()
   // Restart using the same verified metadata and partial files; recheck then finish.
   engine.start();engine.session.applySettings(isolated(49172));TorrentEngine.noUploadPeers(engine.session)
   engine.add(info,download,setOf(chosen));engine.session.resume();waitFor { engine.handle(info)!=null };handle=engine.handle(info)!!;handle.unsetFlags(TorrentFlags.AUTO_MANAGED);handle.resume()
   handle.swig().connect_peer(TcpEndpoint("127.0.0.1",49171).swig())
   waitFor { handle.status(true).isFinished };handle.flushCache()
   val finished=TorrentRules.file(download,files[chosen].path)
   waitFor { finished.isFile && finished.length()==data.size.toLong() }
   assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(data),MessageDigest.getInstance("SHA-256").digest(finished.readBytes()))
   assertEquals(Priority.IGNORE,handle.filePriority(files.single { it.path.endsWith("unselected.bin") }.index))
   // A third peer is interested in data we own. It must receive zero payload,
   // including after the 30-second optimistic-unchoke interval and over LAN.
   consumer.start(SessionParams(isolated(49173)));consumer.download(info,File(base,"consumer"));waitFor { consumer.find(info.infoHash())!=null }
   val other=consumer.find(info.infoHash())!!;other.unsetFlags(TorrentFlags.AUTO_MANAGED);other.resume()
   other.swig().connect_peer(TcpEndpoint("127.0.0.1",49172).swig())
   waitFor { handle.status(true).numPeers()>0 }
   val until=System.nanoTime()+35_000_000_000L
   while(System.nanoTime()<until){assertEquals("Payload upload is forbidden",0,handle.status(true).totalPayloadUpload());assertEquals(0,other.status(true).totalPayloadDownload());Thread.sleep(200)}
   handle.pause();assertEquals(0,handle.status(true).totalPayloadUpload())
   // Metadata retrieval is a real magnet exchange against our owned seeder.
   val magnet="magnet:?xt=urn:btih:${info.infoHash()}&x.pe=127.0.0.1:49171"
   val meta=SessionManager(false)
   try { val settings=isolated(49174);TorrentEngine.noUploadSettings(settings);meta.start(SessionParams(settings));TorrentEngine.noUploadPeers(meta)
    val bytes=meta.fetchMagnet(magnet,30,File(base,"metadata").apply { mkdirs() });assertNotNull(bytes);assertEquals(info.infoHash(),TorrentInfo(bytes!!).infoHash())
   }finally { meta.stop() }
  }finally { engine.close();consumer.stop();seed.stop();base.deleteRecursively() }
 }
}
