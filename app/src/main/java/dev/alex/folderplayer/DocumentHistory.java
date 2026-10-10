package dev.alex.folderplayer;
import org.json.*;
/** Codec only: callers commit atomically before replacing their in-memory history. */
public final class DocumentHistory {
 public static JSONArray load(String archive,String legacy)throws JSONException{
  JSONArray rows;
  if(archive!=null){JSONObject envelope=new JSONObject(archive);if(envelope.getInt("schema")!=1)throw new JSONException("Неизвестная версия истории");rows=envelope.getJSONArray("documents");}
  else{rows=new JSONArray();if(legacy!=null){JSONObject row=new JSONObject(legacy);row.put("id","legacy-full-document");rows.put(row);}}
  java.util.Set<String> ids=new java.util.HashSet<>();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);validate(row);if(!ids.add(row.getString("id")))throw new JSONException("Повтор номера сохранения");}return rows;
 }
 public static void validate(JSONObject row)throws JSONException{
  if(row.getString("id").isEmpty())throw new JSONException("Нет номера сохранения");row.getString("source");row.getString("translation");row.getString("model");row.getLong("created");
  JSONArray blocks=row.getJSONArray("blocks"),sentences=row.getJSONArray("sentences"),warnings=row.getJSONArray("warnings"),choices=row.getJSONArray("choices");int n=blocks.length();if(n!=sentences.length()||n!=warnings.length()||n!=choices.length()||n==0)throw new JSONException("Неполное сохранение");
  if(DocumentTranslation.paragraphs(row.getString("source")).size()!=n)throw new JSONException("Части не совпадают с исходником");
  java.util.List<String> selected=new java.util.ArrayList<>();for(int i=0;i<n;i++){blocks.getString(i);sentences.getString(i);warnings.getString(i);String c=choices.getString(i);if(!c.equals("block")&&!c.equals("sentences"))throw new JSONException("Неверный выбор");selected.add(c.equals("block")?blocks.getString(i):sentences.getString(i));}
  if(!DocumentTranslation.assemble(selected).equals(row.getString("translation")))throw new JSONException("Результат не совпадает с выбором");
 }
 public static JSONArray append(JSONArray previous,JSONObject row)throws JSONException{validate(row);JSONArray next=new JSONArray(previous.toString());next.put(new JSONObject(row.toString()));return load(serialize(next),null);}
 public static String serialize(JSONArray rows)throws JSONException{return new JSONObject().put("schema",1).put("documents",rows).toString(2);}
}
