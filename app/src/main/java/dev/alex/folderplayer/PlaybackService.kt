package dev.alex.folderplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val onlinePreferences by lazy { getSharedPreferences("online_actions",0) }
    private val hiddenListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key=="hidden")handler.post {
            if(::player.isInitialized) {
                val hidden=OnlineLibraryActions(this).ids("hidden")
                for(i in player.mediaItemCount-1 downTo 0)if(OnlinePlayback.trackId(android.net.Uri.parse(player.getMediaItemAt(i).mediaId)) in hidden)player.removeMediaItem(i)
                saveState()
            }
        }
    }
    private val online by lazy { OnlinePlayback(this) }
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val restoreExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val equalizer = SoftwareEqProcessor()
    private val prefs by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private val books by lazy { Audiobooks(this) }
    private val positionPrefs by lazy { getSharedPreferences("playback-position", MODE_PRIVATE) }
    private var savedPositionState: Triple<Int, String, Long>? = null
    private val applyEq = Runnable { updateEq() }
    private val changeListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "sleepDeadline" && ::player.isInitialized) scheduleSleep()
            if (key == "eqEnabled" || key == "eqPreamp" || key?.startsWith("eqBand") == true) {
                handler.removeCallbacks(applyEq)
                handler.post(applyEq)
            }
        }
    private var savedQueue: List<String>? = null
    private var savedState: PlaybackSnapshot? = null
    private val checkpoint =
        object : Runnable {
            override fun run() {
                savePosition()
                if (player.isPlaying) handler.postDelayed(this, 2000)
            }
        }
    private var scheduledSleepDeadline = 0L
    private val sleep = Runnable {
        val deadline = prefs.getLong("sleepDeadline", 0)
        if (deadline > 0 && deadline == scheduledSleepDeadline) {
            player.pause()
            prefs.edit().remove("sleepDeadline").apply()
        } else scheduleSleep()
    }

    private fun scheduleSleep() {
        handler.removeCallbacks(sleep)
        val deadline = prefs.getLong("sleepDeadline", 0)
        scheduledSleepDeadline = deadline
        if (deadline > 0)
            handler.postDelayed(sleep, (deadline - System.currentTimeMillis()).coerceAtLeast(0))
    }

    private fun scheduleCheckpoint() {
        handler.removeCallbacks(checkpoint)
        if (player.isPlaying) handler.postDelayed(checkpoint, 2000)
    }

    override fun onCreate() {
        super.onCreate()
        CrashNotes.install(this)
        if (prefs.getInt("eqFormatVersion", 0) < 2) {
            val edit =
                prefs
                    .edit()
                    .putInt("eqFormatVersion", 2)
                    .putBoolean("eqEnabled", false)
                    .putInt("eqPreamp", 0)
            for (i in 0 until 5) edit.putInt("eqBand$i", 0)
            edit.apply()
        }
        if (prefs.getInt("eqFormatVersion", 0) < 3) {
            val oldHz = doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
            val old = DoubleArray(5) { prefs.getInt("eqBand$it", 0).toDouble() }
            val edit = prefs.edit().putInt("eqFormatVersion", 3)
            DspEqualizer.FREQUENCIES.forEachIndexed { i, hz ->
                val right = oldHz.indexOfFirst { it >= hz }
                val value =
                    when {
                        right == 0 -> old[0]
                        right < 0 -> old.last()
                        else -> {
                            val left = right - 1
                            val fraction =
                                kotlin.math.ln(hz / oldHz[left]) /
                                    kotlin.math.ln(oldHz[right] / oldHz[left])
                            old[left] + fraction * (old[right] - old[left])
                        }
                    }
                edit.putInt("eqBand$i", kotlin.math.round(value).toInt())
            }
            edit.apply()
        }
        updateEq()
        prefs.registerOnSharedPreferenceChangeListener(changeListener)
        val renderers =
            object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(equalizer))
                        .build()
                }
            }
        player =
            ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    androidx.media3.datasource.ResolvingDataSource.Factory(
                        OnlineDataSource.Factory(this),
                        object : androidx.media3.datasource.ResolvingDataSource.Resolver {
                            override fun resolveDataSpec(dataSpec: androidx.media3.datasource.DataSpec): androidx.media3.datasource.DataSpec {
                                if (dataSpec.uri.scheme != "afp-yandex") return dataSpec
                                return dataSpec.withUri(online.resolve(dataSpec.uri))
                            }
                        }
                    )
                ))
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000)
                .build()
        val activity =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        onlinePreferences.registerOnSharedPreferenceChangeListener(hiddenListener)
        session =
            MediaSession.Builder(this, player)
                .setSessionActivity(activity)
                .setBitmapLoader(OnlineBitmapLoader(this))
                .setCallback(
                    object : MediaSession.Callback {
                        override fun onCustomCommand(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            command: SessionCommand,
                            args: Bundle,
                        ): ListenableFuture<SessionResult> {
                            if (controller.packageName != packageName)
                                return Futures.immediateFuture(
                                    SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                                )
                            val result = applyPlaybackCommand(command.customAction, args)
                            return Futures.immediateFuture(SessionResult(result))
                        }

                        override fun onConnect(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                        ): MediaSession.ConnectionResult {
                            if (controller.packageName == packageName)
                                return MediaSession.ConnectionResult.accept(
                                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                                        .buildUpon()
                                        .add(
                                            SessionCommand(
                                                PlaybackCommands.START_ONLINE_QUEUE,
                                                Bundle.EMPTY,
                                            )
                                        )
                                        .add(SessionCommand(PlaybackCommands.START_BOOK, Bundle.EMPTY))
                                        .add(SessionCommand(PlaybackCommands.BOOK_SPEED, Bundle.EMPTY))
                                        .add(
                                            SessionCommand(
                                                PlaybackCommands.START_QUEUE,
                                                Bundle.EMPTY,
                                            )
                                        )
                                        .add(
                                            SessionCommand(
                                                PlaybackCommands.SET_REPEAT,
                                                Bundle.EMPTY,
                                            )
                                        )
                                        .add(
                                            SessionCommand(
                                                PlaybackCommands.REORDER_QUEUE,
                                                Bundle.EMPTY,
                                            )
                                        )
                                        .build(),
                                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
                                )
                            if (controller.isTrusted) return super.onConnect(session, controller)
                            // Legacy companion apps may see metadata without being marked trusted.
                            // Grant transport controls, but no queue/library modification.
                            val controls =
                                Player.Commands.Builder()
                                    .addAll(
                                        Player.COMMAND_PLAY_PAUSE,
                                        Player.COMMAND_PREPARE,
                                        Player.COMMAND_STOP,
                                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                                        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                                        Player.COMMAND_SEEK_TO_PREVIOUS,
                                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                                        Player.COMMAND_SEEK_TO_NEXT,
                                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                                        Player.COMMAND_SEEK_BACK,
                                        Player.COMMAND_SEEK_FORWARD,
                                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                                        Player.COMMAND_GET_TIMELINE,
                                        Player.COMMAND_GET_METADATA,
                                        Player.COMMAND_GET_VOLUME,
                                        Player.COMMAND_SET_VOLUME,
                                    )
                                    .build()
                            return MediaSession.ConnectionResult.accept(
                                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                                controls,
                            )
                        }
                    }
                )
                .build()
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                    this@PlaybackService.player.setWakeMode(if (item?.mediaId?.startsWith("afp-yandex://") == true) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
                }
                override fun onEvents(player: Player, events: Player.Events) {
                    saveState(events.contains(Player.EVENT_TIMELINE_CHANGED))
                    scheduleCheckpoint()
                }
            }
        )
        // Heavy restore (full library scan, queue JSON, online catalogue, books) runs
        // off the main thread; only the resulting player mutations are posted back.
        // This keeps service startup constant regardless of library size.
        restoreExecutor.execute {
            val tracks = Library(this).playableTracks().associateBy { it.uri }
            var savedIndex = prefs.getInt("index", 0)
            var savedPosition = prefs.getLong("position", 0)
            val savedRepeat =
                prefs
                    .getInt("requestedRepeat", prefs.getInt("repeat", Player.REPEAT_MODE_OFF))
                    .coerceIn(0, 2)
            val savedShuffle = prefs.getBoolean("shuffle", false)
            val store = OnlineLibraryStore(this)
            val onlineSaved = runCatching { OnlinePlayback.items(store.read("playing")?.optJSONArray("tracks")?.toString() ?: store.queue(prefs.getString("onlineQueueId", "").orEmpty()) ?: prefs.getString("onlineQueue", "[]")!!) }.getOrDefault(emptyList()).filter { OnlinePlayback.trackId(android.net.Uri.parse(it.mediaId)) !in OnlineLibraryActions(this).ids("hidden") }.associateBy { it.mediaId }
            val restoredBook = books.book(prefs.getString("activeBook", "").orEmpty())
            val savedBookItems = restoredBook?.chapters?.associate { it.uri to it.item(restoredBook) }.orEmpty()
            val saved =
                Library.strings(prefs.getString("queue", "[]")!!).mapNotNull { savedBookItems[it] ?: tracks[it]?.item() ?: onlineSaved[it] }
            val positionIndex = positionPrefs.getInt("index", -1)
            if (
                positionIndex in saved.indices &&
                    saved[positionIndex].mediaId == positionPrefs.getString("uri", null)
            ) {
                savedIndex = positionIndex
                savedPosition = positionPrefs.getLong("position", savedPosition).coerceAtLeast(0)
            }
            if (restoredBook != null) {
                val mark = books.position(restoredBook)
                val found = saved.indexOfFirst { it.mediaId == mark.uri }
                if (found >= 0) { savedIndex = found; savedPosition = mark.position }
            } else prefs.edit().remove("activeBook").apply()
            val startSpeed = restoredBook?.let { books.speed(it.id) }
            handler.post {
                if (::player.isInitialized) {
                    if (restoredBook != null) player.setPlaybackSpeed(startSpeed!!)
                    player.repeatMode = if (restoredBook != null) Player.REPEAT_MODE_OFF else savedRepeat
                    player.shuffleModeEnabled = restoredBook == null && savedShuffle
                    player.setPauseAtEndOfMediaItems(false)
                    if (saved.isNotEmpty()) {
                        player.setMediaItems(
                            saved,
                            savedIndex.coerceIn(0, saved.lastIndex),
                            savedPosition.coerceAtLeast(0),
                        )
                        player.prepare()
                    }
                    // Drop only stale sleep deadlines left from a previous process;
                    // a timer scheduled by the activity meanwhile must survive.
                    val deadline = prefs.getLong("sleepDeadline", 0)
                    if (deadline in 1 until System.currentTimeMillis())
                        prefs.edit().remove("sleepDeadline").apply()
                    saveState()
                    scheduleCheckpoint()
                    scheduleSleep()
                }
            }
        }
    }

    internal fun applyPlaybackCommand(action: String, args: Bundle): Int {
        return when (action) {
            PlaybackCommands.START_BOOK -> {
                val book=books.book(args.getString("bookId").orEmpty())
                if(book==null)SessionError.ERROR_BAD_VALUE else {
                    val mark=books.position(book)
                    val requested=args.getString("uri")
                    val restart=requested==null && mark.completed
                    val uri=requested ?: if(restart)book.chapters.first().uri else mark.uri
                    val index=book.chapters.indexOfFirst { it.uri==uri }
                    if(index<0)SessionError.ERROR_BAD_VALUE else {
                        savePosition()
                        val chapter=book.chapters[index]
                        val pos=(if(requested!=null)args.getLong("position",0) else if(restart)0 else mark.position).coerceAtLeast(0)
                            .let { if(chapter.duration>0)it.coerceAtMost(chapter.duration) else it }
                        books.savePosition(book.id,uri,pos)
                        prefs.edit().putString("activeBook",book.id).putString("queueSource","Аудиокнига · ${book.title}").apply()
                        player.setMediaItems(book.chapters.map { it.item(book) },index,pos)
                        player.repeatMode=Player.REPEAT_MODE_OFF;player.shuffleModeEnabled=false
                        player.setPlaybackSpeed(books.speed(book.id));player.setPauseAtEndOfMediaItems(false)
                        player.prepare();player.play();SessionResult.RESULT_SUCCESS
                    }
                }
            }
            PlaybackCommands.BOOK_SPEED -> {
                val id=player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")
                val speed=args.getFloat("speed",1f)
                if(id==null || !speed.isFinite() || speed !in .5f..2.5f)SessionError.ERROR_BAD_VALUE
                else { books.setSpeed(id,speed);player.setPlaybackSpeed(speed);SessionResult.RESULT_SUCCESS }
            }
            PlaybackCommands.START_ONLINE_QUEUE -> {
                val store=OnlineLibraryStore(this)
                val queueId=args.getString("queueId").orEmpty()
                val parsed=runCatching { OnlinePlayback.items(store.queue(queueId) ?: "[]") }.getOrNull()
                val mode=args.getString("mode","play").orEmpty()
                val hidden=OnlineLibraryActions(this).ids("hidden")
                val items=parsed?.filter { OnlinePlayback.trackId(android.net.Uri.parse(it.mediaId)) !in hidden }.orEmpty()
                val index=args.getInt("index",0)
                if(parsed==null || mode !in listOf("play","next","append","remove") || (mode!="remove" && (items.isEmpty() || (mode=="play" && index !in items.indices)))) SessionError.ERROR_BAD_VALUE
                else {
                    val old=(0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                    val combined=if(mode=="play")items else old+items
                    if(mode!="remove" && (combined.size>20000 || OnlineQueueMetadata.rows(combined).toString().length>8_000_000)) SessionError.ERROR_BAD_VALUE
                    else {
                        when(mode) {
                            "play" -> {
                                leaveBook()
                                prefs.edit().putString("queueSource","Яндекс Музыка"+store.queueSource(queueId).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()).remove("onlineQueueId").remove("onlineQueue").apply()
                                player.setMediaItems(items,index,0);player.setPauseAtEndOfMediaItems(false);player.prepare();player.play()
                            }
                            "next", "append" -> {
                                if(player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")!=null)return SessionError.ERROR_BAD_VALUE
                                if(old.any { !it.mediaId.startsWith("afp-yandex://") })prefs.edit().putString("queueSource","Смешанная очередь").apply()
                                else prefs.edit().putString("queueSource","Яндекс Музыка").apply()
                                val at=if(mode=="next" && old.isNotEmpty())(player.currentMediaItemIndex+1).coerceAtMost(old.size) else old.size
                                player.addMediaItems(at,items)
                                if(old.isEmpty()) { player.prepare();player.play() }
                            }
                            "remove" -> {
                                val ids=parsed.map { it.mediaId }.toSet()
                                for(i in player.mediaItemCount-1 downTo 0)if(player.getMediaItemAt(i).mediaId in ids)player.removeMediaItem(i)
                            }
                        }
                        saveState();store.dropQueue(queueId)
                        SessionResult.RESULT_SUCCESS
                    }
                }
            }
            PlaybackCommands.START_QUEUE -> {
                val library = Library(this)
                val source = args.getString("source").orEmpty()
                val queue =
                    library.ordered(library.playableTracks(), source, prefs.getInt("librarySort", 0))
                val index = queue.indexOfFirst { it.uri == args.getString("uri") }
                if (index < 0) SessionError.ERROR_BAD_VALUE
                else {
                    leaveBook()
                    prefs.edit().putString("queueSource", source).apply()
                    // Queue, starting item and repeat mode are applied together on the service
                    // looper.
                    player.setMediaItems(queue.map { it.item() }, index, 0)
                    player.repeatMode =
                        prefs.getInt("requestedRepeat", player.repeatMode).coerceIn(0, 2)
                    player.setPauseAtEndOfMediaItems(false)
                    player.prepare()
                    player.play()
                    SessionResult.RESULT_SUCCESS
                }
            }
            PlaybackCommands.REORDER_QUEUE -> {
                if(player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")!=null)return SessionResult.RESULT_SUCCESS
                val source = args.getString("source").orEmpty()
                if (prefs.getString("queueSource", "") != source || player.mediaItemCount == 0)
                    SessionResult.RESULT_SUCCESS
                else {
                    val library = Library(this)
                    val old = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                    val byId = old.groupBy { it.mediaId }
                    val refresh = args.getBoolean("refreshSource", false)
                    val playable = library.playableTracks()
                    val playableIds = playable.map { it.uri }.toSet()
                    val known =
                        playable.filter { refresh || it.uri in byId }.associateBy { it.uri }
                    val sourceIds =
                        library
                            .ordered(known.values.toList(), source, prefs.getInt("librarySort", 0))
                            .map { it.uri }
                    val extraIds = old.map { it.mediaId }.filter { it !in sourceIds && it in known }
                    val ordered =
                        (sourceIds + extraIds).distinct().flatMap {
                            byId[it]?.map { item->if(refresh)known.getValue(it).item() else item } ?: listOf(known.getValue(it).item())
                        } + old.filter { it.mediaId !in known && QueueEligibility.playable(it.mediaId,playableIds,OnlineLibraryActions(this).ids("hidden")) }
                    val current = player.currentMediaItem?.mediaId
                    val occurrence =
                        old.take(player.currentMediaItemIndex.coerceAtLeast(0)).count {
                            it.mediaId == current
                        }
                    val index =
                        ordered.indices
                            .filter { ordered[it].mediaId == current }
                            .getOrNull(occurrence) ?: -1
                    if (index >= 0 && player.playbackState != Player.STATE_ENDED) {
                        val position = player.currentPosition
                        val state = player.playbackState
                        player.setMediaItems(ordered, index, position)
                        if (state != Player.STATE_IDLE) player.prepare()
                    }
                    SessionResult.RESULT_SUCCESS
                }
            }
            PlaybackCommands.SET_REPEAT -> {
                if(player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")!=null)return SessionError.ERROR_BAD_VALUE
                val mode = args.getInt("mode", -1)
                if (mode !in 0..2) SessionError.ERROR_BAD_VALUE
                else {
                    prefs.edit().putInt("requestedRepeat", mode).apply()
                    player.repeatMode = mode
                    SessionResult.RESULT_SUCCESS
                }
            }
            else -> SessionError.ERROR_NOT_SUPPORTED
        }
    }

    private fun leaveBook() {
        savePosition()
        prefs.edit().remove("activeBook").apply()
        player.setPlaybackSpeed(1f)
        player.repeatMode=prefs.getInt("requestedRepeat",Player.REPEAT_MODE_OFF).coerceIn(0,2)
        player.shuffleModeEnabled=false
    }

    private fun updateEq() {
        val bands = DoubleArray(10) { prefs.getInt("eqBand$it", 0).toDouble() }
        equalizer.setSettings(
            DspEqualizer.Settings(
                prefs.getBoolean("eqEnabled", false),
                prefs.getInt("eqPreamp", 0).toDouble(),
                bands,
            )
        )
    }

    /** Only these tiny preferences are written on the active playback timer. */
    private fun savePosition() {
        if (!::player.isInitialized) return
        val next =
            Triple(
                player.currentMediaItemIndex.coerceAtLeast(0),
                player.currentMediaItem?.mediaId.orEmpty(),
                player.currentPosition.coerceAtLeast(0),
            )
        val bookId=player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")
        if(bookId!=null) {
            val book=books.book(bookId)
            val completed=player.playbackState==Player.STATE_ENDED && player.currentMediaItemIndex==player.mediaItemCount-1
            val previous=book?.let { books.position(it) }
            val wasCompleted=previous?.completed==true && previous.uri==next.second && next.third>=previous.position && !player.playWhenReady
            books.savePosition(bookId,next.second,next.third,completed || wasCompleted)
        }
        if (next != savedPositionState) {
            positionPrefs
                .edit()
                .putInt("index", next.first)
                .putString("uri", next.second)
                .putLong("position", next.third)
                .apply()
            savedPositionState = next
        }
    }

    private fun saveState(queueChanged: Boolean = true) {
        if (!::player.isInitialized) return
        val edit = prefs.edit()
        var changed = false
        if (queueChanged || savedQueue == null) {
            val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            if (queue != savedQueue) {
                val metadata=OnlineQueueMetadata.rows((0 until player.mediaItemCount).map { player.getMediaItemAt(it) })
                OnlineLibraryStore(this).write("playing",org.json.JSONObject().put("tracks",metadata))
                edit.putString("queue", JSONArray(queue).toString())
                savedQueue = queue
                changed = true
            }
        }
        val next =
            PlaybackSnapshot(
                player.currentMediaItemIndex.coerceAtLeast(0),
                player.currentPosition.coerceAtLeast(0),
                player.repeatMode,
                player.shuffleModeEnabled,
            )
        if (next != savedState) {
            edit
                .putInt("index", next.index)
                .putLong("position", next.position)
                .putInt("repeat", next.repeat)
                .putBoolean("shuffle", next.shuffle)
            savedState = next
            changed = true
        }
        if (changed) edit.apply()
        savePosition()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        onlinePreferences.unregisterOnSharedPreferenceChangeListener(hiddenListener)
        prefs.unregisterOnSharedPreferenceChangeListener(changeListener)
        restoreExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        saveState()
        session?.release()
        player.release()
        super.onDestroy()
    }
}
