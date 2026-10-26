package dev.alex.afptranslation;
oneway interface ITranslationCallback {
 void progress(String requestId, int done, int total);
 void complete(String requestId, String text);
 void failed(String requestId, String message);
}
