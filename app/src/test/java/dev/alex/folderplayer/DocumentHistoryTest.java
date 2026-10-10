package dev.alex.folderplayer;
import org.json.*;
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk={28})
public final class DocumentHistoryTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static JSONObject row(String id,String source,String text)throws Exception{return new JSONObject().put("id",id).put("source",source).put("translation",text).put("created",123L).put("model","test").put("blocks",new JSONArray().put(text)).put("sentences",new JSONArray().put(text)).put("warnings",new JSONArray().put("")).put("choices",new JSONArray().put("sentences"));}
 static void fails(String archive)throws Exception{try{DocumentHistory.load(archive,null);throw new AssertionError();}catch(JSONException expected){}}
 @org.junit.Test public void scenarios()throws Exception{
  JSONArray empty=DocumentHistory.load(null,null);check(empty.length()==0);
  JSONObject legacy=row("old","I came home.","Я пришёл домой.");legacy.remove("id");JSONArray migrated=DocumentHistory.load(null,legacy.toString());check(migrated.length()==1);check(migrated.getJSONObject(0).getString("id").equals("legacy-full-document"));
  JSONArray next=DocumentHistory.append(migrated,row("new","Go home.","Иди домой."));check(migrated.length()==1);check(next.length()==2);
  JSONArray reloaded=DocumentHistory.load(DocumentHistory.serialize(next),legacy.toString());check(reloaded.length()==2);check(reloaded.getJSONObject(0).getString("translation").equals("Я пришёл домой."));check(reloaded.getJSONObject(1).getString("translation").equals("Иди домой."));
  next.getJSONObject(1).put("translation","bad");check(reloaded.getJSONObject(1).getString("translation").equals("Иди домой."));fails(DocumentHistory.serialize(next));
  fails("broken");fails("{\"schema\":2,\"documents\":[]}");fails("{\"schema\":1,\"documents\":[null]}");
  JSONObject bad=row("bad","A.","A");bad.put("choices",new JSONArray());fails(DocumentHistory.serialize(new JSONArray().put(bad)));
  JSONArray duplicate=new JSONArray().put(row("same","A.","A")).put(row("same","B.","B"));fails(DocumentHistory.serialize(duplicate));
  JSONObject wrong=row("wrong","A.\n\nB.","A");fails(DocumentHistory.serialize(new JSONArray().put(wrong)));
  check(DocumentHistory.load(DocumentHistory.serialize(reloaded),"broken").length()==2);
  System.out.println("PASS: history append, legacy migration, reload, copy isolation, corrupt/schema/choice/duplicate/source validation");
 }
}
