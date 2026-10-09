package dev.alex.folderplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
 private lateinit var player: ExoPlayer
 private var session: MediaSession? = null
 private val handler=Handler(Looper.getMainLooper())
 private val equalizer=SoftwareEqProcessor()
 private val prefs by lazy { getSharedPreferences("playback",MODE_PRIVATE) }
 private val applyEq=Runnable { updateEq() }
 private val changeListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
  if(key=="eqEnabled" || key=="eqPreamp" || key?.startsWith("eqBand")==true) { handler.removeCallbacks(applyEq);handler.post(applyEq) }
 }
 private val checkpoint=object: Runnable {
  override fun run() {
   saveState()
   val deadline=prefs.getLong("sleepDeadline",0)
   if(deadline>0 && System.currentTimeMillis()>=deadline) { player.pause(); prefs.edit().remove("sleepDeadline").apply() }
   handler.postDelayed(this,2000)
  }
 }
 override fun onCreate() {
  super.onCreate()
  if(prefs.getInt("eqFormatVersion",0)<2) {
   val edit=prefs.edit().putInt("eqFormatVersion",2).putBoolean("eqEnabled",false).putInt("eqPreamp",0)
   for(i in 0 until 5) edit.putInt("eqBand$i",0)
   edit.apply()
  }
  if(prefs.getInt("eqFormatVersion",0)<3) {
   val oldHz=doubleArrayOf(60.0,230.0,910.0,3600.0,14000.0)
   val old=DoubleArray(5) { prefs.getInt("eqBand$it",0).toDouble() }
   val edit=prefs.edit().putInt("eqFormatVersion",3)
   DspEqualizer.FREQUENCIES.forEachIndexed { i,hz ->
    val right=oldHz.indexOfFirst { it>=hz }
    val value=when { right==0 -> old[0]; right<0 -> old.last(); else -> {
     val left=right-1; val fraction=kotlin.math.ln(hz/oldHz[left])/kotlin.math.ln(oldHz[right]/oldHz[left])
     old[left]+fraction*(old[right]-old[left])
    } }
    edit.putInt("eqBand$i",kotlin.math.round(value).toInt())
   }; edit.apply()
  }
  updateEq()
  prefs.registerOnSharedPreferenceChangeListener(changeListener)
  val renderers=object: DefaultRenderersFactory(this) {
   override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
    return DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
     .setAudioProcessors(arrayOf(equalizer)).build()
   }
  }
  player=ExoPlayer.Builder(this,renderers)
   .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
   .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL)
   .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build()
  val activity=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  session=MediaSession.Builder(this,player).setSessionActivity(activity).setCallback(object: MediaSession.Callback {
   override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
    if(controller.packageName!=packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
    val result=applyPlaybackCommand(command.customAction,args)
    return Futures.immediateFuture(SessionResult(result))
   }
   override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
    if(controller.packageName==packageName) return MediaSession.ConnectionResult.accept(
     MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
      .add(SessionCommand(PlaybackCommands.START_QUEUE,Bundle.EMPTY)).add(SessionCommand(PlaybackCommands.SET_REPEAT,Bundle.EMPTY)).add(SessionCommand(PlaybackCommands.REORDER_QUEUE,Bundle.EMPTY)).build(),
     MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
    if(controller.isTrusted) return super.onConnect(session,controller)
    // Legacy companion apps may see metadata without being marked trusted.
    // Grant transport controls, but no queue/library modification.
    val controls=Player.Commands.Builder().addAll(
     Player.COMMAND_PLAY_PAUSE,Player.COMMAND_PREPARE,Player.COMMAND_STOP,
     Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
     Player.COMMAND_SEEK_TO_PREVIOUS,Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
     Player.COMMAND_SEEK_TO_NEXT,Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
     Player.COMMAND_SEEK_BACK,Player.COMMAND_SEEK_FORWARD,
     Player.COMMAND_GET_CURRENT_MEDIA_ITEM,Player.COMMAND_GET_TIMELINE,Player.COMMAND_GET_METADATA,
     Player.COMMAND_GET_VOLUME,Player.COMMAND_SET_VOLUME).build()
    return MediaSession.ConnectionResult.accept(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,controls)
   }
  }).build()
  player.addListener(object: Player.Listener {
   override fun onEvents(player: Player, events: Player.Events) { saveState() }
  })
  val tracks=Library(this).tracks().associateBy { it.uri }
  val savedIndex=prefs.getInt("index",0)
  val savedPosition=prefs.getLong("position",0)
  val savedRepeat=prefs.getInt("requestedRepeat",prefs.getInt("repeat",Player.REPEAT_MODE_OFF)).coerceIn(0,2)
  val savedShuffle=prefs.getBoolean("shuffle",false)
  val saved=Library.strings(prefs.getString("queue","[]")!!).mapNotNull { tracks[it]?.item() }
  player.repeatMode=savedRepeat
  player.shuffleModeEnabled=savedShuffle
  player.setPauseAtEndOfMediaItems(false)
  if(saved.isNotEmpty()) {
   player.setMediaItems(saved,savedIndex.coerceIn(0,saved.lastIndex),savedPosition.coerceAtLeast(0))
   player.prepare()
  }
  prefs.edit().remove("sleepDeadline").apply()
  handler.post(checkpoint)
 }
 internal fun applyPlaybackCommand(action:String,args:Bundle):Int {
  return when(action) {
     PlaybackCommands.START_QUEUE -> {
      val library=Library(this)
      val source=args.getString("source").orEmpty()
      val queue=library.ordered(library.tracks(),source,prefs.getInt("librarySort",0))
      val index=queue.indexOfFirst { it.uri==args.getString("uri") }
      if(index<0) SessionError.ERROR_BAD_VALUE else {
       prefs.edit().putString("queueSource",source).apply()
       // Queue, starting item and repeat mode are applied together on the service looper.
       player.setMediaItems(queue.map { it.item() },index,0)
       player.repeatMode=prefs.getInt("requestedRepeat",player.repeatMode).coerceIn(0,2)
       player.setPauseAtEndOfMediaItems(false)
       player.prepare(); player.play(); SessionResult.RESULT_SUCCESS
      }
     }
     PlaybackCommands.REORDER_QUEUE -> {
      val source=args.getString("source").orEmpty()
      if(prefs.getString("queueSource","")!=source || player.mediaItemCount==0) SessionResult.RESULT_SUCCESS else {
       val library=Library(this);val old=(0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
       val byId=old.groupBy { it.mediaId };val known=library.tracks().filter { it.uri in byId }.associateBy { it.uri }
       val sourceIds=library.ordered(known.values.toList(),source,prefs.getInt("librarySort",0)).map { it.uri };val extraIds=old.map { it.mediaId }.filter { it !in sourceIds && it in known }
       val ordered=(sourceIds+extraIds).distinct().flatMap { byId[it].orEmpty() }+old.filter { it.mediaId !in known }
       val current=player.currentMediaItem?.mediaId;val occurrence=old.take(player.currentMediaItemIndex.coerceAtLeast(0)).count { it.mediaId==current }
       val index=ordered.indices.filter { ordered[it].mediaId==current }.getOrNull(occurrence) ?: -1
       if(index>=0 && player.playbackState!=Player.STATE_ENDED) {
        val position=player.currentPosition;val state=player.playbackState
        player.setMediaItems(ordered,index,position)
        if(state!=Player.STATE_IDLE) player.prepare()
       }
       SessionResult.RESULT_SUCCESS
      }
     }
     PlaybackCommands.SET_REPEAT -> {
      val mode=args.getInt("mode",-1)
      if(mode !in 0..2) SessionError.ERROR_BAD_VALUE else {
       prefs.edit().putInt("requestedRepeat",mode).apply()
       player.repeatMode=mode; SessionResult.RESULT_SUCCESS
      }
     }
     else -> SessionError.ERROR_NOT_SUPPORTED
    }
 }
 private fun updateEq() {
  val bands=DoubleArray(10) { prefs.getInt("eqBand$it",0).toDouble() }
  equalizer.setSettings(DspEqualizer.Settings(prefs.getBoolean("eqEnabled",false),prefs.getInt("eqPreamp",0).toDouble(),bands))
 }
 private fun saveState() {
  val queue=(0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
  prefs.edit().putString("queue",JSONArray(queue).toString()).putInt("index",player.currentMediaItemIndex.coerceAtLeast(0))
   .putLong("position",player.currentPosition.coerceAtLeast(0)).putInt("repeat",player.repeatMode).putBoolean("shuffle",player.shuffleModeEnabled).apply()
 }
 override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
 override fun onDestroy() {
  prefs.unregisterOnSharedPreferenceChangeListener(changeListener); handler.removeCallbacksAndMessages(null); saveState(); session?.release(); player.release(); super.onDestroy()
 }
}
