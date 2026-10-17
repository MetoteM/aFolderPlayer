package dev.alex.afptorrent

import java.io.File
import org.libtorrent4j.*
import org.libtorrent4j.swig.*

/** Dedicated metadata-only session; never requests file payload. */
internal object TorrentMetadata {
    fun load(bytes:ByteArray):AddTorrentParams {
        require(bytes.size<=TorrentRules.MAX_METADATA)
        val buffer=Vectors.bytes2byte_vector(bytes);val node=bdecode_node();val error=error_code()
        require(bdecode_node.bdecode(buffer,node,error)==0 && error.value()==0) { "Повреждённый .torrent" }
        val params=libtorrent.load_torrent_parsed(node,error,load_torrent_limits())
        require(error.value()==0) { "Некорректные метаданные раздачи" }
        buffer.clear()
        return AddTorrentParams(params)
    }
    fun fetch(session:SessionManager,uri:String,directory:File,seconds:Int):ByteArray {
        val params=AddTorrentParams.parseMagnetUri(uri)
        directory.mkdirs();params.setSavePath(directory.absolutePath)
        params.flags=params.flags.and_(TorrentFlags.AUTO_MANAGED.inv()).or_(TorrentFlags.UPLOAD_MODE).or_(TorrentFlags.STOP_WHEN_READY)
        val error=ErrorCode(error_code());val handle=SessionHandle(session.swig()).addTorrent(params,error)
        require(!error.isError && handle.isValid) { "Не удалось открыть magnet" }
        try {
            handle.resume();val deadline=System.nanoTime()+seconds*1_000_000_000L
            while(System.nanoTime()<deadline){
                if(Thread.currentThread().isInterrupted)throw InterruptedException()
                val status=handle.status(true)
                check(status.totalPayloadUpload()==0L) { "Отдача содержимого запрещена" }
                if(status.hasMetadata()){
                    // Keep original trackers/webseeds and the exact validated info
                    // dictionary. BEP52 piece layers may arrive only during payload
                    // transfer; missing layers are legal for a magnet descriptor.
                    params.torrentInfo=handle.torrentFile()
                    val bytes=Entry(libtorrent.write_torrent_file(params.swig(),libtorrent.getAllow_missing_piece_layer())).bencode()
                    require(bytes.size<=TorrentRules.MAX_METADATA)
                    return bytes
                }
                check(status.errorCode().value==0) { "Не удалось получить метаданные" }
                Thread.sleep(100)
            }
            throw IllegalStateException("Метаданные не получены за отведённое время")
        } finally { if(handle.isValid){handle.pause();session.remove(handle)} }
    }
}
