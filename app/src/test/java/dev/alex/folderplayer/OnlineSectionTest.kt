package dev.alex.folderplayer

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class OnlineSectionTest {
    private class Direct:AbstractExecutorService() {
        private var stopped=false
        override fun execute(command:Runnable)=command.run()
        override fun shutdown() { stopped=true }
        override fun shutdownNow():MutableList<Runnable> { stopped=true;return mutableListOf() }
        override fun isShutdown()=stopped
        override fun isTerminated()=stopped
        override fun awaitTermination(timeout:Long,unit:TimeUnit)=true
    }
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun idle() { repeat(8) { shadowOf(Looper.getMainLooper()).idle() } }
    @Test fun realPlaylistOpenButtonLoadsOwnerThenCommitsAll125TracksAtChosenPosition() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        val calls=mutableListOf<String>();val dispatched=mutableListOf<Triple<String,Int,String>>()
        val api=object:OnlineCatalogue {
            override fun installed()=true
            override fun request(operation:String,argument:String):JSONObject {
                calls+=operation
                return when(operation) {
                    "status"->JSONObject().put("connected",true).put("sessionKey","account")
                    "collectionPage"->JSONObject().put("tracks",JSONArray()).put("total",0)
                    "playlists"->JSONObject().put("playlists",JSONArray("""[{"id":"99:7","title":"Foreign playlist","count":125}]"""))
                    "playlistPage"->{
                        val args=JSONObject(argument);assertEquals("99:7",args.getString("id"));val offset=args.getInt("offset");val end=minOf(offset+50,125)
                        JSONObject().put("tracks",JSONArray((offset until end).map { JSONObject().put("id",it+1).put("title","Song ${it+1}").put("duration",120000) }))
                            .put("total",125).put("next",if(end<125)end else JSONObject.NULL)
                    }
                    else->error(operation)
                }
            }
        }
        val ui=OnlineSection(host.get(),{ handle,index,mode,done -> dispatched+=Triple(handle,index,mode);done(null) },api,Direct())
        try {
            ui.show();idle()
            val dialog=ShadowDialog.getLatestDialog();val root=dialog.window!!.decorView
            all(root).filterIsInstance<Button>().single { it.contentDescription=="Плейлисты" }.performClick();idle()
            val list=all(root).filterIsInstance<ListView>().single();assertEquals(1,list.adapter.count)
            val row=list.adapter.getView(0,null,list)
            all(row).filterIsInstance<Button>().single { it.text=="Открыть" }.performClick();idle()
            assertEquals(50,list.adapter.count)
            list.adapter.getView(4,null,list).performClick();idle()
            assertEquals(1,dispatched.size);val (handle,index,mode)=dispatched.single()
            val queue=JSONArray(OnlineLibraryStore(RuntimeEnvironment.getApplication()).queue(handle))
            assertEquals(125,queue.length());assertEquals(4,index);assertEquals("play",mode)
            assertEquals("125",queue.getJSONObject(124).getString("id"));assertEquals(3,calls.count { it=="playlistPage" })
        } finally { ui.close();host.pause().stop().destroy() }
    }
    @Test fun checkboxesDispatchSelectedNextAndFavoritesHideStayLocal() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        val dispatched=mutableListOf<Pair<List<String>,String>>()
        val api=object:OnlineCatalogue {
            override fun installed()=true
            override fun request(operation:String,argument:String):JSONObject = when(operation) {
                "status"->JSONObject().put("connected",true).put("sessionKey","batch-account")
                "collectionPage"->JSONObject().put("tracks",JSONArray((1..3).map { JSONObject().put("id",it).put("title","Song $it") })).put("total",3)
                else->throw AssertionError("Local actions must not call $operation")
            }
        }
        val ui=OnlineSection(host.get(),{ handle,_,mode,done ->
            val rows=JSONArray(OnlineLibraryStore(host.get()).queue(handle))
            dispatched+=(0 until rows.length()).map { rows.getJSONObject(it).getString("id") } to mode
            done(null)
        },api,Direct())
        fun choose(index:Int) {
            val menu=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val list=menu.listView;list.performItemClick(list.adapter.getView(index,null,list),index,index.toLong());idle()
        }
        try {
            ui.show();idle();val root=ShadowDialog.getLatestDialog().window!!.decorView
            val list=all(root).filterIsInstance<ListView>().single()
            val search=all(root).filterIsInstance<EditText>().single()
            search.setText("Song 2");idle();assertEquals(1,list.adapter.count)
            search.setText("");idle();assertEquals(3,list.adapter.count)
            fun select(index:Int) { all(list.adapter.getView(index,null,list)).filterIsInstance<CheckBox>().single().performClick();idle() }
            fun actions() { all(root).filterIsInstance<Button>().single { it.text=="Действия" }.performClick() }
            select(0);select(2);actions();choose(3)
            assertEquals(listOf("1","3") to "next",dispatched.single())
            actions();choose(5)
            assertEquals(setOf("1","3"),OnlineLibraryActions(host.get()).ids("favorites"))
            select(0);select(2);actions();choose(7)
            assertEquals(setOf("1","3"),OnlineLibraryActions(host.get()).ids("hidden"))
            assertEquals(1,list.adapter.count);assertEquals("remove",dispatched.last().second)
            all(root).filterIsInstance<Button>().single { it.contentDescription=="⋮" }.performClick();choose(1)
            assertEquals(2,list.adapter.count)
            select(0);actions();choose(7)
            assertEquals(setOf("3"),OnlineLibraryActions(host.get()).ids("hidden"))
        } finally { ui.close();host.pause().stop().destroy() }
    }
    @Test fun rejectedQueueKeepsTheSourceOpenAndShowsAnError() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        val api=object:OnlineCatalogue {
            override fun installed()=true
            override fun request(operation:String,argument:String)=if(operation=="status")JSONObject().put("connected",true).put("sessionKey","error-account")
                else JSONObject().put("tracks",JSONArray("""[{"id":"1","title":"Song"}]""")).put("total",1)
        }
        val ui=OnlineSection(host.get(),{ _,_,_,done -> done("Плеер отклонил очередь") },api,Direct())
        try {
            ui.show();idle();val source=ShadowDialog.getLatestDialog();val root=source.window!!.decorView
            val list=all(root).filterIsInstance<ListView>().single()
            list.adapter.getView(0,null,list).performClick();idle()
            assertTrue(source.isShowing)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString().contains("Плеер отклонил очередь") })
        } finally { ui.close();host.pause().stop().destroy() }
    }
    @Test fun rapidTapsCommitOnceAndReturnToTheSameOnlineList() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        var dispatches=0;var opened=0;var complete:((String?)->Unit)?=null
        val calls=mutableListOf<String>()
        val api=object:OnlineCatalogue {
            override fun installed()=true
            override fun request(operation:String,argument:String):JSONObject {
                calls+=operation
                return if(operation=="status")JSONObject().put("connected",true).put("sessionKey","rapid-account")
                else JSONObject().put("tracks",JSONArray((1..3).map { JSONObject().put("id",it).put("title","Rapid $it") })).put("total",3)
            }
        }
        val ui=OnlineSection(host.get(),{ _,_,_,done -> dispatches++;complete=done },api,Direct(),{ opened++ })
        try {
            ui.show();idle();val source=ShadowDialog.getLatestDialog();val root=source.window!!.decorView
            val list=all(root).filterIsInstance<ListView>().single()
            repeat(30) { list.adapter.getView(0,null,list).performClick() };idle()
            assertEquals(1,dispatches)
            repeat(30) {
                list.adapter.getView(1,null,list).performClick()
                all(root).filterIsInstance<Button>().single { it.contentDescription=="Плейлисты" }.performClick()
            }
            assertEquals(1,dispatches);assertFalse(calls.contains("playlists"))
            complete!!(null);idle();assertEquals(View.GONE,source.window!!.decorView.visibility)
            val before=calls.size
            ui.show();idle();assertEquals(View.VISIBLE,source.window!!.decorView.visibility);assertSame(source,ShadowDialog.getLatestDialog())
            assertEquals(3,list.adapter.count);assertEquals(before,calls.size)
            all(root).filterIsInstance<NowPlayingStrip>().single().performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND,null);idle()
            assertEquals(1,opened);assertEquals(View.GONE,source.window!!.decorView.visibility)
        } finally { ui.close();host.pause().stop().destroy() }
    }
    @Test fun cancelledRequestsDoNotAccumulateNetworkCallsOrOverwriteLatestSource() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        val tasks=java.util.ArrayDeque<Runnable>()
        val queue=object:AbstractExecutorService() {
            override fun execute(command:Runnable) { tasks.add(command) }
            override fun shutdown() {}
            override fun shutdownNow():MutableList<Runnable> = mutableListOf()
            override fun isShutdown()=false
            override fun isTerminated()=false
            override fun awaitTermination(timeout:Long,unit:TimeUnit)=true
        }
        val calls=mutableListOf<String>()
        val api=object:OnlineCatalogue {
            override fun installed()=true
            override fun request(operation:String,argument:String):JSONObject {
                calls+=operation
                return JSONObject().put("playlists",JSONArray("""[{"id":"42:7","title":"Latest","count":2}]"""))
            }
        }
        val ui=OnlineSection(host.get(),{ _,_,_,_->error("Unexpected playback") },api,queue)
        try {
            ui.show();val root=ShadowDialog.getLatestDialog().window!!.decorView
            repeat(30) {
                all(root).filterIsInstance<Button>().single { it.text=="Отменить загрузку" }.performClick()
                all(root).filterIsInstance<Button>().single { it.contentDescription=="Плейлисты" }.performClick()
            }
            while(tasks.isNotEmpty()) { tasks.removeFirst().run();idle() }
            assertEquals(listOf("playlists"),calls)
            val list=all(root).filterIsInstance<ListView>().single()
            assertEquals(1,list.adapter.count);assertEquals("Latest",(list.adapter.getItem(0) as JSONObject).getString("title"))
        } finally { ui.close();host.pause().stop().destroy() }
    }

    @Test fun synchronousPlaybackFailureUnlocksTheSourceForRetry() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        var attempts=0
        val api=object:OnlineCatalogue {
            override fun installed()=true
            override fun request(operation:String,argument:String)=if(operation=="status")JSONObject().put("connected",true).put("sessionKey","throw-account")
                else JSONObject().put("tracks",JSONArray("""[{"id":"1","title":"Song"}]""")).put("total",1)
        }
        val ui=OnlineSection(host.get(),{ _,_,_,done -> if(++attempts==1)throw IllegalStateException("Disconnected controller") else done(null) },api,Direct())
        try {
            ui.show();idle();val source=ShadowDialog.getLatestDialog();val root=source.window!!.decorView
            val list=all(root).filterIsInstance<ListView>().single()
            list.adapter.getView(0,null,list).performClick();idle()
            assertTrue(source.isShowing)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString().contains("Не удалось передать очередь") })
            list.adapter.getView(0,null,list).performClick();idle()
            assertEquals(2,attempts);assertEquals(View.GONE,source.window!!.decorView.visibility)
        } finally { ui.close();host.pause().stop().destroy() }
    }

    @Test fun openingHiddenListDoesNotDispatchPlaybackAndStaleAdapterPositionsAreSafe() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        val context=host.get();OnlineLibraryStore(context).setSession("hidden-browse")
        OnlineLibraryActions(context).set("hidden",JSONArray("""[{"id":"999","title":"Hidden","artist":"A","duration":45000}]"""),true)
        val api=object:OnlineCatalogue {
            override fun installed()=true
            override fun request(operation:String,argument:String)=when(operation) {
                "status"->JSONObject().put("connected",true).put("sessionKey","hidden-browse")
                "collectionPage"->JSONObject().put("tracks",JSONArray((1..50).map { JSONObject().put("id",it).put("title","Song $it") }))
                else->error("Unexpected network request")
            }
        }
        val service=Robolectric.buildService(PlaybackService::class.java).create()
        val player=PlaybackService::class.java.getDeclaredField("player").apply { isAccessible=true }.get(service.get()) as androidx.media3.common.Player
        player.setMediaItems(OnlinePlayback.items("""[{"id":"1","title":"Playing"},{"id":"2","title":"Next"}]"""));player.seekTo(0,1234);player.playWhenReady=true
        val ui=OnlineSection(context,{ _,_,_,_->throw AssertionError("Browsing hidden must never alter playback") },api,Direct())
        try {
            ui.show();idle();val root=ShadowDialog.getLatestDialog().window!!.decorView
            val list=all(root).filterIsInstance<ListView>().single();assertEquals(50,list.count)
            list.setSelection(49)
            all(root).filterIsInstance<Button>().single { it.contentDescription=="⋮" }.performClick()
            val menu=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().listView
            menu.performItemClick(menu.adapter.getView(1,null,menu),1,1);idle()
            assertEquals(1,list.count);assertFalse(context.isFinishing)
            assertEquals("Hidden",(list.adapter.getItem(0) as JSONObject).getString("title"))
            // An OEM ListView can request a previously visible position during the switch.
            assertNotNull(list.adapter.getView(49,null,list));assertEquals(-1L,list.adapter.getItemId(49));assertNull(list.adapter.getItem(49))
            assertEquals(setOf("999"),OnlineLibraryActions(context).ids("hidden"))
            assertEquals(2,player.mediaItemCount);assertEquals(0,player.currentMediaItemIndex);assertEquals(1234L,player.currentPosition);assertTrue(player.playWhenReady)
        } finally { ui.close();service.destroy();host.pause().stop().destroy() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun rotationRestoresOnlineSourceFilterSelectionAndCachedRowsWithoutNetworkReset() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        val api=object:OnlineCatalogue {
            override fun installed()=true
            override fun request(operation:String,argument:String):JSONObject = when(operation) {
                "status"->JSONObject().put("connected",true).put("sessionKey","rotation-account")
                "collectionPage"->JSONObject().put("tracks",JSONArray((1..8).map { JSONObject().put("id",it).put("title","Song $it") })).put("total",8)
                else->error(operation)
            }
        }
        val first=OnlineSection(host.get(),{ _,_,_,done -> done(null) },api,Direct())
        var second:OnlineSection?=null
        try {
            first.show();idle()
            var root=ShadowDialog.getLatestDialog().window!!.decorView
            all(root).filterIsInstance<EditText>().single().setText("Song 2")
            val list=all(root).filterIsInstance<ListView>().single()
            all(list.adapter.getView(0,null,list)).filterIsInstance<CheckBox>().single().performClick()
            val saved=first.saveNavigation()!!;val retained=first.snapshot()!!
            first.close()
            val offline=object:OnlineCatalogue {
                override fun installed()=true
                override fun request(operation:String,argument:String):JSONObject=throw AssertionError("Rotation must not restart $operation")
            }
            val next=OnlineSection(host.get(),{ _,_,_,done -> done(null) },offline,Direct());second=next
            next.restore(saved,retained);idle()
            root=ShadowDialog.getLatestDialog().window!!.decorView
            assertEquals("Song 2",all(root).filterIsInstance<EditText>().single().text.toString())
            val restored=all(root).filterIsInstance<ListView>().single()
            assertEquals(1,restored.adapter.count)
            assertTrue(all(restored.adapter.getView(0,null,restored)).filterIsInstance<CheckBox>().single().isChecked)
            val pane=all(root).filterIsInstance<BrowserPane>().single()
            repeat(4) {
                pane.measure(View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(280,View.MeasureSpec.EXACTLY))
                pane.layout(0,0,640,280)
            }
            assertTrue("Online results must remain visible",restored.height>=90)
            val image=android.graphics.Bitmap.createBitmap(640,280,android.graphics.Bitmap.Config.ARGB_8888)
            pane.draw(android.graphics.Canvas(image))
            val target=java.io.File("../qa/rotation-landscape/online-640x280.png")
            target.parentFile!!.mkdirs();target.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        } finally { first.close();second?.close();host.pause().stop().destroy() }
    }

    @Test fun rotatingDuringInitialLoadRetriesInsteadOfRestoringAnEmptyCompletedList() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val slow=object:OnlineCatalogue {
            override fun installed()=true
            override fun request(operation:String,argument:String):JSONObject {
                entered.countDown();release.await()
                return JSONObject().put("connected",true).put("sessionKey","pending-rotation")
            }
        }
        val first=OnlineSection(host.get(),{ _,_,_,done -> done(null) },slow)
        var second:OnlineSection?=null
        try {
            first.show();assertTrue(entered.await(2,TimeUnit.SECONDS))
            val saved=first.saveNavigation()!!;val retained=first.snapshot()!!
            assertTrue(saved.getBoolean("loading"))
            first.close()
            val ready=object:OnlineCatalogue {
                override fun installed()=true
                override fun request(operation:String,argument:String):JSONObject {
                    assertEquals("collectionPage",operation)
                    return JSONObject().put("tracks",JSONArray((1..3).map { JSONObject().put("id",it).put("title","Loaded $it") })).put("total",3)
                }
            }
            val next=OnlineSection(host.get(),{ _,_,_,done -> done(null) },ready,Direct());second=next
            next.restore(saved,retained);idle()
            val root=ShadowDialog.getLatestDialog().window!!.decorView
            assertEquals(3,all(root).filterIsInstance<ListView>().single().adapter.count)
        } finally { release.countDown();first.close();second?.close();host.pause().stop().destroy() }
    }

}
