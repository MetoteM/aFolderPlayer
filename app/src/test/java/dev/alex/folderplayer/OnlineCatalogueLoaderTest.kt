package dev.alex.folderplayer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class OnlineCatalogueLoaderTest {
    @Test fun completePlaylistIncludesAllPagesAndKeepsDuplicateEntries() {
        val offsets=mutableListOf<Int>()
        val api=object:OnlineCatalogue {
            override fun installed()=true
            override fun request(operation:String,argument:String):JSONObject {
                assertEquals("playlistPage",operation);val a=JSONObject(argument);assertEquals("99:7",a.getString("id"))
                val offset=a.getInt("offset");offsets+=offset;val end=minOf(offset+50,125)
                return JSONObject().put("tracks",JSONArray((offset until end).map { JSONObject().put("id",if(it==50)"1" else (it+1).toString()) }))
                    .put("total",125).put("next",if(end<125)end else JSONObject.NULL)
            }
        }
        val full=OnlineCatalogueLoader(api).complete("playlistPage","99:7",null,{true}) { _,_-> }
        assertEquals(listOf(0,50,100),offsets);assertEquals(125,full.getJSONArray("tracks").length())
        assertEquals("1",full.getJSONArray("tracks").getJSONObject(50).getString("id"))
    }
    @Test fun cancelledOrLoopingPaginationDoesNotCommitPartialQueue() {
        val api=object:OnlineCatalogue {
            override fun installed()=true
            override fun request(operation:String,argument:String)=JSONObject().put("tracks",JSONArray()).put("next",0)
        }
        for(active in listOf(true,false)) {
            try { OnlineCatalogueLoader(api).complete("playlistPage","42:1",null,{active}) { _,_-> };fail() } catch(_:IllegalStateException) { }
        }
    }
}
