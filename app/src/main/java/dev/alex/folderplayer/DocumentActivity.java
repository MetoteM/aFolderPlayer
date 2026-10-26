package dev.alex.folderplayer;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.util.AtomicFile;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

public final class DocumentActivity extends ThemedActivity {
  private RetainedOperation<DocumentResult> operation;
  private String appliedOperationId;
  private ScrollView reader;
  private String source, initialSource, track, model;
  private List<String> original;
  private List<String> blocks, parts, notices;
  private boolean[] useBlock;
  private boolean busy, complete;
  private TextView status, output;
  private Button run, stop, review, all, save, copy;

  private TextView label(String s) {
    TextView v = new TextView(this);
    v.setTextColor(AppAppearance.getTextColor());
    v.setTextSize(17);
    v.setText(s);
    v.setPadding(0, 12, 0, 12);
    v.setTextIsSelectable(true);
    return v;
  }

  private Button button(String s, View.OnClickListener l) {
    Button b = new Button(this);
    b.setText(s);
    b.setOnClickListener(l);
    return b;
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    operation =
        getLastNonConfigurationInstance() instanceof RetainedOperation
            ? (RetainedOperation<DocumentResult>) getLastNonConfigurationInstance()
            : new RetainedOperation<>();
    source = getIntent().getStringExtra("source");
    if (source == null) source = "";
    initialSource = source;
    track = getIntent().getStringExtra("track");
    model = getIntent().getStringExtra("translationModel");
    if (model == null || !TranslationClient.Companion.getMODELS().containsKey(model))
      model = new TranslationClient(this).getModelId();
    original = DocumentTranslation.paragraphs(source);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setBackgroundColor(AppAppearance.getPalette().getBackground());
    box.setPadding(24, 24, 24, 24);
    if (Build.VERSION.SDK_INT >= 30)
      box.setOnApplyWindowInsetsListener(
          (v, i) -> {
            android.graphics.Insets b = i.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(24, b.top + 16, 24, b.bottom + 16);
            return i;
          });
    ScrollView sc = new ScrollView(this);
    reader = sc;
    sc.addView(box);
    setContentView(sc);
    box.addView(label("Перевод"));
    status =
        label(
            "Перевод выполняется на устройстве. Язык: "
                + TranslationClient.Companion.getMODELS().get(model));
    box.addView(status);
    run = button("Перевести", v -> confirmTranslation());
    stop =
        button(
            "Отмена",
            v -> {
              operation.cancel();
              status.setText("Перевод отменён. Можно повторить.");
              active(false);
            });
    review = button("Места с возможным пропуском", v -> list(true));
    all = button("Все фрагменты", v -> list(false));
    save = button("Сохранить для песни", v -> save());
    if (track == null) save.setVisibility(View.GONE);
    box.addView(run);
    box.addView(stop);
    box.addView(save);
    copy = button("Копировать текущий результат", v -> copy(result()));
    box.addView(button("Дополнительно", v -> extras()));
    output = label("");
    box.addView(output);
    box.addView(button("Назад", v -> finish()));
    active(false);
    ReadingPosition.attach(this, sc, "document:" + initialSource + "\n" + model);
    if (state != null) {
      source = state.getString("documentSource", source);
      model = state.getString("documentModel", model);
      original = DocumentTranslation.paragraphs(source);
      String completed = state.getString("completed");
      if (completed != null)
        try {
          restore(new JSONObject(completed));
        } catch (Exception ignored) {
          status.setText("Не удалось восстановить результат");
        }
    }
    if (state != null) appliedOperationId = state.getString("appliedOperationId");
    operation.attach(this::showOperation);
    if (state == null
        && operation.state().key == null
        && getIntent().getBooleanExtra("translateNow", false)) translate();
  }

  private void active(boolean b) {
    busy = b;
    run.setEnabled(!b);
    run.setText(complete ? "Перевести заново" : "Перевести");
    stop.setEnabled(b);
    stop.setVisibility(b ? View.VISIBLE : View.GONE);
    for (Button x : new Button[] {review, all, copy}) x.setEnabled(!b && complete);
    save.setEnabled(!b && complete && track != null);
  }

  private void confirmTranslation() {
    if (!complete) {
      translate();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Перевести заново?")
        .setMessage("Текущий результат будет заменён после завершения.")
        .setPositiveButton("Перевести", (d, n) -> translate())
        .setNegativeButton("Отмена", null)
        .show();
  }

  private void extras() {
    List<String> items = new ArrayList<>();
    if (complete) {
      items.add("Копировать перевод");
      items.add("Сравнить фрагменты");
      items.add("Проверить возможные пропуски");
    }
    items.add("Как работает перевод");
    new AlertDialog.Builder(this)
        .setTitle("Дополнительно")
        .setItems(
            items.toArray(new String[0]),
            (d, n) -> {
              switch (items.get(n)) {
                case "Копировать перевод":
                  copy(result());
                  break;
                case "Сравнить фрагменты":
                  list(false);
                  break;
                case "Проверить возможные пропуски":
                  list(true);
                  break;
                default:
                  new AlertDialog.Builder(this)
                      .setMessage(
                          "Каждый абзац переводится по предложениям и целиком. Можно сравнить"
                              + " варианты и выбрать подходящий. Предупреждения о пропусках не"
                              + " проверяют весь смысл. Перевод не синхронизирован со временем"
                              + " LRC.")
                      .setPositiveButton("Закрыть", null)
                      .show();
              }
            })
        .setNegativeButton("Закрыть", null)
        .show();
  }

  private void translate() {
    if (busy) return;
    if (source.trim().isEmpty() || source.length() > 20000 || source.split("\n", -1).length > 500) {
      status.setText("Нужен непустой текст до 20 000 символов и 500 строк");
      return;
    }
    final String input = source, language = model;
    final Context context = getApplicationContext();
    active(true);
    operation.start(
        input + "\n" + language,
        (cancel, progress) -> DocumentResult.translate(context, input, language, cancel, progress));
  }

  private void showOperation(RetainedOperation.Snapshot<DocumentResult> state) {
    if (state.key == null) return;
    if (state.busy) {
      active(true);
      status.setText(state.message);
      return;
    }
    if (state.value != null && !state.value.id.equals(appliedOperationId)) {
      DocumentResult value = state.value;
      appliedOperationId = value.id;
      source = value.source;
      model = value.model;
      original = DocumentTranslation.paragraphs(source);
      blocks = value.blocks;
      parts = value.parts;
      notices = value.notices;
      useBlock = new boolean[blocks.size()];
      complete = true;
      refresh();
    }
    if (state.failure != null) status.setText("Перевод не завершён: " + state.failure.getMessage());
    active(false);
  }

  private String result() {
    List<String> selected = new ArrayList<>();
    for (int i = 0; i < parts.size(); i++) selected.add(useBlock[i] ? blocks.get(i) : parts.get(i));
    return DocumentTranslation.assemble(selected);
  }

  private void refresh() {
    output.setText(result());
    int n = 0;
    for (String s : notices) if (!s.isEmpty()) n++;
    status.setText(
        "Готово. Мест с возможным пропуском: " + n + ". Перевод может содержать ошибки.");
  }

  private void list(boolean suspicious) {
    if (busy || !complete) return;
    List<Integer> ids = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    for (int i = 0; i < original.size(); i++)
      if (!original.get(i).trim().isEmpty() && (!suspicious || !notices.get(i).isEmpty())) {
        ids.add(i);
        labels.add("Фрагмент " + (i + 1) + (useBlock[i] ? " · блок" : " · предложения"));
      }
    if (ids.isEmpty()) {
      status.setText(
          "Признаки крупного пропуска не найдены. Смысл и детали могут содержать ошибки.");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(suspicious ? "Возможные пропуски" : "Все фрагменты")
        .setItems(labels.toArray(new String[0]), (d, n) -> choose(ids.get(n)))
        .setNegativeButton("Закрыть", null)
        .show();
  }

  private void choose(int i) {
    ScrollView sc = new ScrollView(this);
    sc.addView(
        label(
            "Оригинал\n"
                + original.get(i)
                + "\n\nБлок\n"
                + blocks.get(i)
                + "\n\nПредложения\n"
                + parts.get(i)
                + "\n\n"
                + notices.get(i)));
    new AlertDialog.Builder(this)
        .setTitle("Фрагмент " + (i + 1))
        .setView(sc)
        .setPositiveButton(
            "Выбрать предложения",
            (d, n) -> {
              useBlock[i] = false;
              refresh();
            })
        .setNeutralButton(
            "Выбрать блок",
            (d, n) -> {
              useBlock[i] = true;
              refresh();
            })
        .setNegativeButton("Закрыть", null)
        .show();
  }

  private void copy(String value) {
    ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
        .setPrimaryClip(ClipData.newPlainText("AFP перевод", value));
    Toast.makeText(this, "Перевод скопирован", Toast.LENGTH_SHORT).show();
  }

  private void save() {
    if (busy || !complete || track == null) return;
    if (!source.equals(initialSource) || result().contains("⟦Не переведено⟧")) {
      status.setText("Неполный результат можно скопировать через «Дополнительно».");
      return;
    }
    try {
      new TranslationStore(this).save(track, source, result(), model);
      status.setText("Перевод сохранён для песни.");
    } catch (Exception e) {
      status.setText("Не удалось сохранить: " + e.getMessage());
    }
  }

  private void restore(JSONObject row) {
    try {
      source = row.getString("source");
      model = row.getString("model");
      original = DocumentTranslation.paragraphs(source);
      blocks = strings(row.getJSONArray("blocks"));
      parts = strings(row.getJSONArray("sentences"));
      notices = strings(row.getJSONArray("warnings"));
      useBlock = new boolean[blocks.size()];
      JSONArray choices = row.getJSONArray("choices");
      for (int i = 0; i < useBlock.length; i++) useBlock[i] = choices.getString(i).equals("block");
      if (original.size() != blocks.size()) throw new JSONException("Части не совпадают");
      complete = true;
      appliedOperationId = operation.state().value == null ? null : operation.state().value.id;
      refresh();
      active(false);
    } catch (Exception e) {
      complete = false;
      active(false);
      status.setText("Не удалось открыть сохранение: " + e.getMessage());
    }
  }

  private List<String> strings(JSONArray a) throws JSONException {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
    return out;
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return operation;
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putString("appliedOperationId", appliedOperationId);
    out.putString("documentSource", source);
    out.putString("documentModel", model);
    if (complete)
      try {
        JSONObject row =
            new JSONObject()
                .put("source", source)
                .put("model", model)
                .put("blocks", new JSONArray(blocks))
                .put("sentences", new JSONArray(parts))
                .put("warnings", new JSONArray(notices));
        JSONArray choices = new JSONArray();
        for (boolean b : useBlock) choices.put(b ? "block" : "sentences");
        row.put("choices", choices);
        out.putString("completed", row.toString());
      } catch (Exception ignored) {
      }
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onDestroy() {
    operation.detach();
    if (!isChangingConfigurations()) operation.close();
    super.onDestroy();
  }
}
