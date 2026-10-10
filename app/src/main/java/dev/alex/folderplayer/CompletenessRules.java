package dev.alex.folderplayer;
import java.util.*;import java.util.regex.*;
/** Experimental English sentence boundaries; blank paragraphs are preserved. */
public final class CompletenessRules {
 private static final Set<String> TITLES=new HashSet<>(Arrays.asList("mr","mrs","ms","dr","prof","st","jr","sr","etc","vs"));
 public static List<String> units(String source){
  List<String> result=new ArrayList<>();String[] paragraphs=source.replace("\r","").split("\\n[ \\t]*\\n",-1);
  for(int p=0;p<paragraphs.length;p++){if(p>0)result.add("");String[] lines=paragraphs[p].split("\\n",-1);StringBuilder pending=new StringBuilder();
   for(int i=0;i<lines.length;){String line=lines[i].trim();int end=i+1;while(!line.isEmpty()&&end<lines.length&&lines[end].trim().equals(line))end++;
    if(end-i>1){plainUnits(pending.toString(),result);pending.setLength(0);for(int j=i;j<end;j++)plainUnits(line,result);}
    else{if(pending.length()>0)pending.append(' ');pending.append(line);}i=end;
   }plainUnits(pending.toString(),result);
  }return result;
 }
 private static void plainUnits(String source,List<String> result){String text=source.replaceAll("\\s+"," ").trim();if(text.isEmpty())return;
  Matcher m=Pattern.compile("[.!?]+[\"”']?\\s+(?=[A-Z])").matcher(text);int start=0;
  while(m.find()){Matcher word=Pattern.compile("([A-Za-z]+)$").matcher(text.substring(0,m.start()));if(word.find()&&(word.group(1).length()==1||TITLES.contains(word.group(1).toLowerCase(Locale.ROOT))))continue;int end=m.start()+m.group().trim().length();result.add(text.substring(start,end));start=m.end();}
  result.add(text.substring(start));
 }
 public static String warning(String block,List<String> translated,List<String> sourceUnits){
  if(translated.size()!=sourceUnits.size())throw new IllegalArgumentException("Parts do not match");
  Map<String,Integer> counts=new HashMap<>();Map<String,String> values=new HashMap<>();for(int i=0;i<sourceUnits.size();i++){String source=sourceUnits.get(i).trim();if(source.isEmpty())continue;counts.put(source,counts.getOrDefault(source,0)+1);values.put(source,normalize(translated.get(i)));}
  String normalizedBlock=" "+normalize(block)+" ";for(String source:counts.keySet())if(counts.get(source)>1){String value=values.get(source);if(value.isEmpty())continue;String needle=" "+value+" ";int seen=0,start=0,pos;while((pos=normalizedBlock.indexOf(needle,start))>=0){seen++;start=pos+needle.length()-1;}if(seen<counts.get(source))return "Возможно, блок потерял повтор: одинаковая исходная часть встречается в нём реже. Формулировка блока могла измениться — сравни варианты.";}
  return warning(block,translated);
 }
 public static String block(String source){return source.replaceAll("\\s+"," ").trim();}
 public static String normalize(String s){Matcher m=Pattern.compile("[\\p{L}\\p{N}_]+").matcher(s.toLowerCase(Locale.ROOT));List<String> w=new ArrayList<>();while(m.find())w.add(m.group());return String.join(" ",w);}
 public static String warning(String block,List<String> translated){List<String> parts=new ArrayList<>();for(String s:translated)if(!s.trim().isEmpty())parts.add(s);if(parts.size()<2)return "";
  int separate=0;Set<String> distinct=new HashSet<>();for(String s:parts){String n=normalize(s);separate+=n.isEmpty()?0:n.split(" ").length;distinct.add(n);}String b=normalize(block);int count=b.isEmpty()?0:b.split(" ").length;
  if(distinct.size()>1&&distinct.contains(b))return "Возможно, блок пропустил часть текста: он совпал с переводом одного предложения.";
  if(separate>=8&&count<separate*.60)return "Возможно, блок пропустил часть текста: он заметно короче перевода предложений.";
  return "";
 }
}
