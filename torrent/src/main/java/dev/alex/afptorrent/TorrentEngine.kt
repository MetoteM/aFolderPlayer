package dev.alex.afptorrent

import java.io.File
import org.libtorrent4j.*
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t

/** No Android dependency: exercised with real peers in the Linux JNI integration test. */
internal class TorrentEngine {
    val session=SessionManager(false)
    fun start(overrides:SettingsPack?=null) {
        if(session.isRunning)return
        val settings=(overrides ?: SettingsPack()).connectionsLimit(60).activeDownloads(2).activeSeeds(0)
        settings.setMaxMetadataSize(TorrentRules.MAX_METADATA)
        settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(),false)
        settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(),false)
        noUploadSettings(settings)
        session.start(SessionParams(settings))
        noUploadPeers(session)
        session.pause()
    }
    fun describe(info:TorrentInfo):List<TorrentFile> {
        require(info.isValid && info.numFiles() in 1..TorrentRules.MAX_FILES) { "Раздача повреждена или содержит слишком много файлов" }
        val fs=info.files();val seen=HashSet<String>()
        return (0 until fs.numFiles()).map { i ->
            val path=fs.filePath(i)
            require(TorrentRules.safePath(path) && seen.add(path.lowercase(java.util.Locale.ROOT))) { "Небезопасные или повторяющиеся пути" }
            require(fs.fileFlags(i).and_(FileStorage.FLAG_SYMLINK).to_int()==0) { "Символические ссылки не поддерживаются" }
            TorrentFile(i,path,fs.fileSize(i),!fs.padFileAt(i) && TorrentRules.music(path))
        }
    }
    fun add(info:TorrentInfo,root:File,selected:Set<Int>,metadata:ByteArray?=null,magnet:String?=null) {
        root.mkdirs()
        val params=if(metadata!=null)TorrentMetadata.load(metadata) else AddTorrentParams().apply { torrentInfo=info }
        params.setSavePath(root.absolutePath)
        params.flags=params.flags.and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.UPLOAD_MODE.inv()).and_(TorrentFlags.STOP_WHEN_READY.inv())
        val priorities=org.libtorrent4j.swig.byte_vector()
        for(i in 0 until info.numFiles())priorities.add((if(i in selected)Priority.DEFAULT else Priority.IGNORE).swig())
        params.swig().set_file_priorities(priorities)
        if(magnet!=null){val original=AddTorrentParams.parseMagnetUri(magnet);params.peers(original.peers());params.trackers=(params.trackers+original.trackers).distinct();params.urlSeeds=(params.urlSeeds+original.urlSeeds).distinct()}
        session.swig().async_add_torrent(params.swig())
    }
    fun handle(info:TorrentInfo):TorrentHandle?=session.find(info.infoHash())?.takeIf { it.isValid }
    fun close()=session.stop()
    companion object {
        fun noUploadSettings(settings:SettingsPack) {
            settings.setInteger(settings_pack.int_types.choking_algorithm.swigValue(),settings_pack.choking_algorithm_t.fixed_slots_choker.swigValue())
            settings.setInteger(settings_pack.int_types.unchoke_slots_limit.swigValue(),0)
            settings.setInteger(settings_pack.int_types.allowed_fast_set_size.swigValue(),0)
        }
        fun noUploadPeers(session:SessionManager) {
            // Libtorrent exempts LAN peers from choking by default. Map every peer
            // to the global class only, including IPv6 and socket type classes.
            val filter=org.libtorrent4j.swig.ip_filter()
            filter.add_rule(Address.parseIp("0.0.0.0").swig(),Address.parseIp("255.255.255.255").swig(),1)
            filter.add_rule(Address.parseIp("::").swig(),Address.parseIp("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff").swig(),1)
            session.swig().set_peer_class_filter(filter)
            session.swig().set_peer_class_type_filter(org.libtorrent4j.swig.peer_class_type_filter())
        }
    }
}
