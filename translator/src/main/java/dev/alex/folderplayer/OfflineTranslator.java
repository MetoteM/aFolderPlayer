package dev.alex.folderplayer;

import ai.onnxruntime.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Pure local CPU inference; this class has no networking or Android dependencies. */
public final class OfflineTranslator implements AutoCloseable {
 public static final String MODEL_ID="opus-en-ru-v1";
 private final OrtEnvironment environment=OrtEnvironment.getEnvironment();
 private final OrtSession encoder,decoder;
 private final Node trie=new Node();
 private final String[] vocabulary;
 private double unknownScore=-100;
 private static final class Node { final Map<Character,Node> children=new HashMap<>();int id=-1;double score; }
 public OfflineTranslator(File directory) throws Exception {
  environment.setTelemetry(false);
  JSONArray words=new JSONObject(read(new File(directory,"tokenizer.json"))).getJSONObject("model").getJSONArray("vocab");
  vocabulary=new String[words.length()];double minimum=0;
  for(int i=0;i<words.length();i++) {
   JSONArray item=words.getJSONArray(i);String token=item.getString(0);double score=item.getDouble(1);vocabulary[i]=token;if(score<=-1e8)continue;minimum=Math.min(minimum,score);
   if(i==0 || i==1 || i==62517)continue;
   Node node=trie;for(int j=0;j<token.length();j++)node=node.children.computeIfAbsent(token.charAt(j),k->new Node());node.id=i;node.score=score;
  }
  unknownScore=minimum-10;
  try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()) {
   options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);
   encoder=environment.createSession(new File(directory,"encoder.onnx").getAbsolutePath(),options);
   try { decoder=environment.createSession(new File(directory,"decoder.onnx").getAbsolutePath(),options); } catch(Exception e) { encoder.close();throw e; }
  }
 }
 static String read(File f) throws IOException { try(InputStream in=new FileInputStream(f)) { ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8); } }
 public long[] encode(String text) {
  String normalized=Normalizer.normalize(text,Normalizer.Form.NFKC).trim();List<Long> ids=new ArrayList<>();
  for(String word:normalized.split("\\s+"))if(!word.isEmpty()) {
   String value="▁"+word;int length=value.length();double[] best=new double[length+1];Arrays.fill(best,Double.NEGATIVE_INFINITY);best[0]=0;
   int[] previous=new int[length+1],tokens=new int[length+1];
   for(int i=0;i<length;i++)if(Double.isFinite(best[i])) {
    Node node=trie;boolean single=false;
    for(int j=i;j<length;j++) {
     node=node.children.get(value.charAt(j));if(node==null)break;
     if(node.id>=0) { if(j==i)single=true;double score=best[i]+node.score;if(score>best[j+1]) { best[j+1]=score;previous[j+1]=i;tokens[j+1]=node.id; } }
    }
    if(!single) { int end=i+Character.charCount(value.codePointAt(i));double score=best[i]+unknownScore;if(score>best[end]) { best[end]=score;previous[end]=i;tokens[end]=1; } }
   }
   List<Long> reversed=new ArrayList<>();for(int end=length;end>0;end=previous[end])reversed.add((long)tokens[end]);Collections.reverse(reversed);ids.addAll(reversed);
  }
  ids.add(0L);long[] result=new long[ids.size()];for(int i=0;i<result.length;i++)result[i]=ids.get(i);return result;
 }
 private void check(BooleanSupplier cancelled) throws InterruptedException { if(Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())throw new InterruptedException("Translation cancelled"); }
 public String sentence(String text,BooleanSupplier cancelled) throws Exception {
  check(cancelled);long[] input=encode(text);if(input.length>100)throw new IllegalArgumentException("Строка слишком длинная для перевода");
  long[][] mask=new long[1][input.length];Arrays.fill(mask[0],1);
  try(OnnxTensor ids=OnnxTensor.createTensor(environment,new long[][]{input});OnnxTensor attention=OnnxTensor.createTensor(environment,mask);
      OrtSession.Result encoded=encoder.run(Map.of("input_ids",ids,"attention_mask",attention))) {
   OnnxTensor hidden=(OnnxTensor)encoded.get("last_hidden_state").orElseThrow();List<Long> output=new ArrayList<>();output.add(62517L);
   boolean completed=false;
   for(int step=0;step<Math.min(160,input.length*3+24);step++) {
    check(cancelled);long[][] target=new long[1][output.size()];for(int i=0;i<output.size();i++)target[0][i]=output.get(i);
    try(OnnxTensor targetIds=OnnxTensor.createTensor(environment,target);OrtSession.Result decoded=decoder.run(Map.of("input_ids",targetIds,"encoder_hidden_states",hidden,"encoder_attention_mask",attention))) {
     OnnxTensor logits=(OnnxTensor)decoded.get("logits").orElseThrow();java.nio.FloatBuffer values=logits.getFloatBuffer();int size=vocabulary.length;int offset=(output.size()-1)*size;int best=0;float score=Float.NEGATIVE_INFINITY;
     for(int i=0;i<size;i++)if(i!=62517 && i!=1) { float value=values.get(offset+i);if(value>score) { score=value;best=i; } }
     if(best==0) { completed=true;break; }output.add((long)best);
    }
   }
   if(!completed)throw new IOException("Перевод строки не завершён. Попробуй сократить исходный текст");
   StringBuilder result=new StringBuilder();for(int i=1;i<output.size();i++)result.append(vocabulary[output.get(i).intValue()]);return result.toString().replace('▁',' ').trim();
  }
 }
 public interface Progress { void update(int done,int total); }
 public String translate(String text,BooleanSupplier cancelled,Progress progress) throws Exception {
  String[] lines=text.replace("\r","").split("\n",-1);if(lines.length>500 || text.length()>20000)throw new IllegalArgumentException("Перевод поддерживает до 20 000 символов и 500 строк");
  StringBuilder result=new StringBuilder();Map<String,String> repeated=new HashMap<>();
  for(int i=0;i<lines.length;i++) {
   check(cancelled);String line=lines[i].trim();if(i>0)result.append('\n');
   if(!line.isEmpty()) {
    String translated=repeated.get(line);
    if(translated==null) {
     StringBuilder value=new StringBuilder();StringBuilder chunk=new StringBuilder();
     for(String word:line.split("\\s+")) {
      String next=chunk.length()==0?word:chunk+" "+word;
      if(encode(next).length>70 && chunk.length()>0) { if(value.length()>0)value.append(' ');value.append(sentence(chunk.toString(),cancelled));chunk.setLength(0); }
      if(chunk.length()>0)chunk.append(' ');chunk.append(word);
     }
     if(chunk.length()>0) { if(value.length()>0)value.append(' ');value.append(sentence(chunk.toString(),cancelled)); }
     translated=value.toString();repeated.put(line,translated);
    }
    result.append(translated);
   }
   progress.update(i+1,lines.length);
  }
  return result.toString();
 }
 @Override public void close() throws OrtException { try { decoder.close(); } finally { encoder.close(); } }
}
