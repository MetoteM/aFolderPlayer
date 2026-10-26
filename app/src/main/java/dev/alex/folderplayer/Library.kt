package dev.alex.folderplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

internal const val MIN_DURATION_MS = 30_000L

internal fun eligibleDuration(duration: Long) = duration >= MIN_DURATION_MS

internal data class Track(
    val uri: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val folder: String,
    val filename: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val rootUri: String = "",
    val sourceUri: String = uri,
    val cueStartMs: Long = 0,
    val cueEndMs: Long? = null,
    val cueSheetUri: String = "",
    val artworkUri: String = "",
) {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun item(): MediaItem =
        MediaItem.Builder()
            .setMediaId(uri)
            .setUri(sourceUri)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(cueStartMs)
                .setEndPositionMs(cueEndMs ?: androidx.media3.common.C.TIME_END_OF_SOURCE).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album.takeIf { it.isNotBlank() })
                    .setAlbumArtist(albumArtist.takeIf { it.isNotBlank() })
                    .setReleaseYear(year)
                    .setTrackNumber(trackNumber)
                    .setDiscNumber(discNumber)
                    .setArtworkUri(artworkUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                    .build()
            )
            .build()

    fun json() =
        JSONObject()
            .put("uri", uri)
            .put("title", title)
            .put("artist", artist)
            .put("duration", duration)
            .put("folder", folder)
            .put("filename", filename)
            .put("album", album)
            .put("albumArtist", albumArtist)
            .put("year", year)
            .put("trackNumber", trackNumber)
            .put("discNumber", discNumber)
            .put("rootUri", rootUri)
            .put("sourceUri", sourceUri)
            .put("cueStartMs", cueStartMs)
            .put("cueEndMs", cueEndMs)
            .put("cueSheetUri", cueSheetUri)
            .put("artworkUri", artworkUri)
}

internal class Library(sourceContext: Context) {
    private val context = sourceContext.applicationContext
    private val prefs = sourceContext.getSharedPreferences("library", Context.MODE_PRIVATE)

    private val trackStore = LibraryTrackStore(prefs)

    fun hiddenTracks(): Set<String> = prefs.getStringSet("hiddenTracks", emptySet())!!.toSet()

    fun setTracksHidden(ids: Set<String>, hidden: Boolean) {
        synchronized(storeLock) {
            val updated = hiddenTracks().toMutableSet()
            if (hidden) updated.addAll(ids) else updated.removeAll(ids)
            prefs.edit().putStringSet("hiddenTracks", updated).apply()
        }
    }

    fun excludeTracks(ids: Set<String>) {
        synchronized(storeLock) {
            val excluded = prefs.getStringSet("excludedTracks", emptySet())!!.toSet() + ids
            prefs.edit().putStringSet("excludedTracks", excluded).apply()
        }
    }

    fun playableTracks(): List<Track> {
        val hidden = hiddenTracks()
        return tracks().filter { it.uri !in hidden }
    }

    fun roots(): List<String> = strings(prefs.getString("roots", "[]")!!)

    fun setRoots(roots: List<String>) {
        synchronized(storeLock) {
            prefs.edit().putString("roots", JSONArray(roots).toString()).apply()
        }
    }

    private fun storedTracks(): List<Track> = trackStore.read()

    fun tracks(): List<Track> {
        val excluded=prefs.getStringSet("excludedTracks", emptySet())!!.toSet()
        return AlbumYears(context).apply(storedTracks().filter { it.uri !in excluded })
    }

    data class ScanResult(val tracks:List<Track>, val errors:Int, val retainedRoots:Int)

    fun scan(): ScanResult {
        fun checkActive() {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Сканирование отменено")
        }
        checkActive()
        val (scanRoots, previous, scanId) = synchronized(storeLock) {
            Triple(roots(), storedTracks(), ++scanGeneration)
        }
        val failedRoots = linkedSetOf<String>()
        val found = linkedMapOf<String, Track>()
        val visited = hashSetOf<String>()
        var errors = 0
        data class CueDocument(val file:LibraryDocument,val folder:String,val root:String)
        val cues=mutableListOf<CueDocument>()
        val images=mutableListOf<Pair<String,AlbumImage>>()
        val extensions = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav")
        fun walk(file: LibraryDocument, folder: String, root: String) {
            checkActive()
            if (!visited.add(file.uri.toString())) return
            if (file.isDirectory) {
                val children =
                    runCatching { LibraryDocuments.children(context, file.uri) }
                        .getOrElse {
                            errors++
                            failedRoots.add(root)
                            emptyList()
                        }
                children.sortedBy { it.name }.forEach { walk(it, "$folder/${file.name.orEmpty()}", root) }
                return
            }
            val name = file.name ?: return
            if(AlbumArtwork.image(name)) { if(file.size in 1..12*1024*1024L)images.add(root to AlbumImage(folder.trimStart('/')+"/"+name,file.uri.toString()));return }
            if(name.endsWith(".cue",true)) { cues.add(CueDocument(file,folder.trimStart('/'),root));return }
            if (name.substringAfterLast('.', "").lowercase() !in extensions) return
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, file.uri)
                val duration =
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                if (duration == null || duration <= 0) {
                    errors++
                    return
                }
                val title =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf {
                        it.isNotBlank()
                    } ?: name.substringBeforeLast('.')
                val artist =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                found[file.uri.toString()] =
                    Track(
                        file.uri.toString(),
                        title,
                        artist,
                        duration,
                        folder.trimStart('/'),
                        name,
                        retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                            .orEmpty()
                            .trim(),
                        retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                            .orEmpty()
                            .trim(),
                        readAlbumYear(retriever, name, file.uri),
                        tagNumber(
                            retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER
                            )
                        ),
                        tagNumber(
                            retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER
                            )
                        ),
                        root,
                    )
            } catch (_: Exception) {
                errors++
            } finally {
                runCatching { retriever.release() }
            }
        }
        for (root in scanRoots) {
            checkActive()
            val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(root)) }.getOrNull()
            if (tree == null || !tree.canRead() || !tree.isDirectory) {
                errors++
                failedRoots.add(root)
                continue
            }
            walk(LibraryDocument(tree.uri,tree.name,true,0), "", root)
        }
        val original=found.values.toList()
        val split=hashSetOf<String>()
        for(cue in cues) {
            runCatching {
                val bytes=context.contentResolver.openInputStream(cue.file.uri)!!.use { input ->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) { val n=input.read(buffer);if(n<0)break;require(out.size()+n<=CueSheet.MAX_BYTES);out.write(buffer,0,n) };out.toByteArray()
                }
                val sheet=CueSheet.parse(CueSheet.decode(bytes))
                val tracks=CueTracks.expand(sheet,cue.file.uri.toString(),cue.folder,cue.root,original)
                val sources=tracks.map { it.sourceUri }.toSet()
                require(sources.none { it in split }) { "Несколько CUE для одного файла" }
                split.addAll(sources);sources.forEach { found.remove(it) };tracks.forEach { found[it.uri]=it }
            }.onFailure { errors++ } // A broken sheet keeps the original audio playable.
        }
        val artwork=images.groupBy({it.first},{it.second}).mapValues { AlbumArtwork(it.value) }
        val sorted = found.values.map { track->track.copy(artworkUri=artwork[track.rootUri]?.candidates(track.folder+"/"+track.filename)?.firstOrNull()?.uri.orEmpty()) }.filter { it.cueSheetUri.isNotBlank() || eligibleDuration(it.duration) }.sortedBy { it.title.lowercase() }
        synchronized(storeLock) {
            checkActive()
            if (scanId != scanGeneration || roots() != scanRoots)
                throw java.util.concurrent.CancellationException("Результат сканирования устарел")
            val published = sorted.filter { it.rootUri !in failedRoots } +
                previous.filter { it.rootUri in failedRoots }
            val complete = published.distinctBy { it.uri }.sortedBy { it.title.lowercase() }
            migrateCueReferences(complete)
            trackStore.write(complete)
            syncFolderPlaylists(complete)
            return ScanResult(tracks(), errors, failedRoots.count { root -> previous.any { it.rootUri == root } })
        }
    }

    private fun migrateCueReferences(tracks:List<Track>) {
        synchronized(storeLock) {
            val replacements=tracks.filter { it.cueSheetUri.isNotBlank() }.groupBy { it.sourceUri }
            if(replacements.isEmpty())return
            fun expand(ids:Collection<String>)=ids.flatMap { id->replacements[id]?.sortedBy { it.cueStartMs }?.map { it.uri } ?: listOf(id) }.distinct()
            val lists=lists();val edit=prefs.edit()
            for(name in lists.keys().asSequence().toList())lists.put(name,JSONArray(expand(strings(lists.getJSONArray(name).toString()))))
            edit.putString("playlists",lists.toString())
            for(key in listOf("hiddenTracks","excludedTracks"))edit.putStringSet(key,expand(prefs.getStringSet(key,emptySet())!!).toSet())
            edit.apply()
        }
    }

    fun isRandom(source: String) = prefs.contains("randomOrder:$source")

    fun clearRandom(source: String) {
        synchronized(storeLock) {
            prefs.edit().remove("randomOrder:$source").apply()
        }
    }

    fun ordered(all: List<Track>, source: String, librarySort: Int): List<Track> {
        val ordered =
            playbackTracks(
                all,
                source.takeIf { it.isNotEmpty() }?.let { playlist(it) },
                librarySort,
                playlistSort(source),
            )
        val saved = prefs.getString("randomOrder:$source", null) ?: return ordered
        val ranks = strings(saved).withIndex().associate { it.value to it.index }
        return ordered.sortedBy { ranks[it.uri] ?: Int.MAX_VALUE }
    }

    fun randomize(all: List<Track>, source: String, librarySort: Int) {
        synchronized(storeLock) {
            val shuffled = ordered(all, source, librarySort).shuffled()
            prefs
                .edit()
                .putString("randomOrder:$source", JSONArray(shuffled.map { it.uri }).toString())
                .apply()
        }
    }

    fun playlistSort(name: String): Int =
        prefs.getInt("playlistSort:$name", SORT_MANUAL).coerceIn(0, 3)

    fun setPlaylistSort(name: String, mode: Int) {
        synchronized(storeLock) {
            require(mode in 0..3)
            prefs.edit().putInt("playlistSort:$name", mode).apply()
        }
    }

    fun addFolder(
        name: String,
        folder: MusicFolder,
        recursive: Boolean,
        follow: Boolean = false,
    ): Int {
        synchronized(storeLock) {
            val existing = playlist(name)
            val added =
                playlistOrder(tracks().filter { folder.contains(it, recursive) }, SORT_ALBUM)
                    .map { it.uri }
                    .filter { it !in existing }
            savePlaylist(name, existing + added)
            val data = folderLinks()
            val links = data.optJSONArray(name) ?: JSONArray()
            val updated = JSONArray()
            for (i in 0 until links.length()) {
                val link = links.getJSONObject(i)
                if (link.optString("root") != folder.root || link.optString("path") != folder.path)
                    updated.put(link)
            }
            if (follow)
                updated.put(
                    JSONObject()
                        .put("root", folder.root)
                        .put("path", folder.path)
                        .put("recursive", recursive)
                        .put("excluded", JSONArray())
                )
            data.put(name, updated)
            prefs.edit().putString("playlistFolders", data.toString()).apply()
            return added.size
        }
    }

    data class FolderLink(val folder: MusicFolder, val recursive: Boolean)

    fun linkedFolders(name: String): List<FolderLink> {
        val rows = folderLinks().optJSONArray(name) ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            runCatching {
                    val r = rows.getJSONObject(i)
                    FolderLink(
                        MusicFolder(r.getString("root"), r.getString("path")),
                        r.optBoolean("recursive"),
                    )
                }
                .getOrNull()
        }
    }

    fun configureFolder(name: String, folder: MusicFolder, recursive: Boolean?) {
        synchronized(storeLock) {
            if (name !in playlistNames()) return
            val data = folderLinks()
            val rows = data.optJSONArray(name) ?: return
            val next = JSONArray()
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                if (r.optString("root") == folder.root && r.optString("path") == folder.path) {
                    if (recursive != null) next.put(r.put("recursive", recursive))
                } else next.put(r)
            }
            data.put(name, next)
            prefs.edit().putString("playlistFolders", data.toString()).apply()
        }
    }

    data class RemovedTrack(val playlist: String, val uri: String, val index: Int)

    fun removeTrack(name: String, uri: String): RemovedTrack? {
        synchronized(storeLock) {
            val old = playlist(name).toList()
            val index = old.indexOf(uri)
            if (index < 0) return null
            savePlaylist(name, old.filter { it != uri })
            return RemovedTrack(name, uri, index)
        }
    }

    fun restoreTrack(item: RemovedTrack): Boolean {
        synchronized(storeLock) {
            if (item.playlist !in playlistNames() && item.playlist != "Избранное") return false
            val current = playlist(item.playlist).toMutableList()
            if (item.uri !in current) current.add(item.index.coerceIn(0, current.size), item.uri)
            savePlaylist(item.playlist, current)
            return true
        }
    }

    private fun folderLinks() =
        runCatching { JSONObject(prefs.getString("playlistFolders", "{}") ?: "{}") }
            .getOrDefault(JSONObject())

    /**
     * Append new folder members; manual removals remain excluded and existing order stays intact.
     */
    internal fun syncFolderPlaylists(all: List<Track>): Int {
        synchronized(storeLock) {
            val data = folderLinks()
            var total = 0
            for (name in data.keys().asSequence().toList()) {
                if (name !in playlistNames()) continue
                val current = playlist(name)
                val candidates = linkedSetOf<String>()
                val links = data.optJSONArray(name) ?: continue
                for (i in 0 until links.length()) {
                    val link = links.getJSONObject(i)
                    val folder = MusicFolder(link.getString("root"), link.getString("path"))
                    val excluded = strings(link.optJSONArray("excluded")?.toString() ?: "[]").toSet()
                    candidates.addAll(
                        playlistOrder(
                                all.filter {
                                    folder.contains(it, link.optBoolean("recursive")) &&
                                        it.uri !in excluded
                                },
                                SORT_ALBUM,
                            )
                            .map { it.uri }
                    )
                }
                val added = candidates.filter { it !in current }
                if (added.isNotEmpty()) {
                    savePlaylist(name, current + added)
                    total += added.size
                }
            }
            return total
        }
    }

    internal fun readAlbumYear(retriever: MediaMetadataRetriever, name: String, uri: Uri): Int? {
        val nativeYear =
            tagYear(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR))
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension != "flac" && extension != "mp3") return nativeYear
        // Original-release tags must be inspected even when Android supplies a reissue year.
        return runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    if (extension == "flac") FlacYearReader.readYear(input) ?: nativeYear
                    else Id3YearReader.read(input, nativeYear)
                }
            }
            .getOrNull() ?: nativeYear
    }

    private fun lists() =
        runCatching { JSONObject(prefs.getString("playlists", "{}") ?: "{}") }
            .getOrDefault(JSONObject())

    fun playlistNames(): List<String> = lists().keys().asSequence().toList().sorted()

    fun playlist(name: String): Set<String> =
        strings(lists().optJSONArray(name)?.toString() ?: "[]").toSet()

    fun savePlaylist(name: String, uris: Collection<String>) {
        synchronized(storeLock) {
            val removed = playlist(name) - uris.toSet()
            val links = folderLinks()
            val rows = links.optJSONArray(name)
            if (rows != null) {
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val excluded = strings(row.optJSONArray("excluded")?.toString() ?: "[]").toSet()
                    row.put("excluded", JSONArray((excluded + removed) - uris.toSet()))
                }
                prefs.edit().putString("playlistFolders", links.toString()).apply()
            }
            val data = lists().put(name, JSONArray(uris))
            prefs.edit().putString("playlists", data.toString()).apply()
        }
    }

    fun renamePlaylist(old: String, name: String): Boolean {
        synchronized(storeLock) {
            if (name.isBlank() || name == "Избранное" || old == "Избранное") return false
            val data = lists()
            if (data.has(name) || !data.has(old)) return false
            data.put(name, data.getJSONArray(old))
            data.remove(old)
            val links = folderLinks()
            if (links.has(old)) {
                links.put(name, links.getJSONArray(old))
                links.remove(old)
                prefs.edit().putString("playlistFolders", links.toString()).apply()
            }
            val edit =
                prefs
                    .edit()
                    .putString("playlists", data.toString())
                    .putInt("playlistSort:$name", playlistSort(old))
                    .remove("playlistSort:$old")
            prefs.getString("randomOrder:$old", null)?.let { edit.putString("randomOrder:$name", it) }
            edit.remove("randomOrder:$old").apply()
            return true
        }
    }

    fun removePlaylist(name: String) {
        synchronized(storeLock) {
            val data = lists()
            data.remove(name)
            val links = folderLinks()
            links.remove(name)
            prefs
                .edit()
                .putString("playlistFolders", links.toString())
                .putString("playlists", data.toString())
                .remove("playlistSort:$name")
                .remove("randomOrder:$name")
                .apply()
        }
    }

    fun toggleFavorite(track: Track) {
        synchronized(storeLock) {
            val set = playlist("Избранное").toMutableSet()
            if (!set.add(track.uri)) set.remove(track.uri)
            savePlaylist("Избранное", set)
        }
    }

    companion object {
        private val storeLock = Any()
        private var scanGeneration = 0L

        fun strings(json: String): List<String> =
            runCatching {
                    val a = JSONArray(json)
                    (0 until a.length()).map { a.getString(it) }
                }
                .getOrDefault(emptyList())
    }
}
