package dev.alex.afptranslation;
import android.os.ParcelFileDescriptor;
import dev.alex.afptranslation.ITranslationCallback;
interface ITranslationService {
 int apiVersion();
 boolean modelReady(String modelId);
 long modelBytes(String modelId);
 void installModel(String requestId, in ParcelFileDescriptor file, ITranslationCallback callback);
 void translate(String requestId, String modelId, String text, ITranslationCallback callback);
 void cancel(String requestId);
 void removeModel(String modelId);
}
