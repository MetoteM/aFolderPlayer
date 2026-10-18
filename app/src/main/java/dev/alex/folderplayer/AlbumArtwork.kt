package dev.alex.folderplayer

import java.util.Locale

internal data class AlbumImage(val path:String,val uri:String)

/** Resolve only inside the album (or its artwork folder), never a neighbouring album. */
internal class AlbumArtwork(images:List<AlbumImage>) {
    private val folders=images.groupBy { it.path.substringBeforeLast('/',"").lowercase(Locale.ROOT) }
    private val artFolders=listOf("scans","scan","covers","cover","artwork","art","images","обложки","сканы")
    fun candidates(audioPath:String):List<AlbumImage> {
        val folder=audioPath.substringBeforeLast('/',"")
        val name=audioPath.substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)
        val album=folder.substringAfterLast('/').lowercase(Locale.ROOT)
        fun rank(image:AlbumImage):Int? {
            val stem=image.path.substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)
            val standard=listOf("cover","folder","front","album").indexOf(stem)
            if(standard>=0)return standard
            if(stem==name || stem==album)return 4
            if(Regex("(?:^|[ _().\\-])(?:front|cover|folder|обложка)(?:$|[ _().\\-])").containsMatchIn(stem))return 5
            return null
        }
        fun imagesAt(path:String)=folders[path.lowercase(Locale.ROOT)].orEmpty()
        fun at(path:String):List<AlbumImage> {
            val direct=imagesAt(path)
            val nested=artFolders.flatMap { imagesAt(if(path.isEmpty())it else "$path/$it") }
            val known=direct.mapNotNull { image->rank(image)?.let { Triple(0,it,image) } }+
                nested.mapNotNull { image->rank(image)?.let { Triple(1,it,image) } }
            val ordered=known.sortedWith(compareBy<Triple<Int,Int,AlbumImage>>({it.first},{it.second},{it.third.path.lowercase(Locale.ROOT)})).map { it.third }
            // A lone arbitrary album.jpg is useful; back/disc/booklet scans are not front covers.
            val fallback=(direct+nested).filter { image ->
                !Regex("(?i)(?:^|[ _().\\-])(?:back|rear|cd|disc|disk|booklet|tray|задник)(?:$|[ _().\\-0-9])").containsMatchIn(image.path.substringAfterLast('/').substringBeforeLast('.'))
            }.singleOrNull()
            return (ordered+listOfNotNull(fallback)).distinctBy { it.uri }
        }
        val here=at(folder)
        val parent=if(Regex("(?i)^(?:cd|disc|disk|диск)[ _-]*[0-9]+$").matches(folder.substringAfterLast('/')))at(folder.substringBeforeLast('/',"")) else emptyList()
        return (here+parent).distinctBy { it.uri }
    }
    companion object {
        fun image(name:String)=name.substringAfterLast('.',"").lowercase(Locale.ROOT) in setOf("jpg","jpeg","png","webp")
    }
}
