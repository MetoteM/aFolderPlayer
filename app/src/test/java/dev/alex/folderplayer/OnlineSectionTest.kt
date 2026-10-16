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
            all(root).filterIsInstance<Button>().single { it.text=="Плейлисты" }.performClick();idle()
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
            all(root).filterIsInstance<Button>().single { it.text=="⋮" }.performClick();choose(1)
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
}
