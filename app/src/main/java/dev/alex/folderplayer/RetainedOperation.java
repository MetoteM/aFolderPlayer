package dev.alex.folderplayer;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Work owns no Activity. Attach/detach screens without restarting requests. */
public final class RetainedOperation<T> implements AutoCloseable {
  public interface Progress {
    void update(String message);
  }

  public interface Work<T> {
    T run(AtomicBoolean cancel, Progress progress) throws Exception;
  }

  public interface Observer<T> {
    void changed(Snapshot<T> state);
  }

  public static final class Snapshot<T> {
    public final String key, message;
    public final boolean busy;
    public final T value;
    public final Exception failure;

    Snapshot(String key, String message, boolean busy, T value, Exception failure) {
      this.key = key;
      this.message = message;
      this.busy = busy;
      this.value = value;
      this.failure = failure;
    }
  }

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "AFP-operation");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
          });
  private final Handler main = new Handler(Looper.getMainLooper());
  private Observer<T> observer;
  private volatile Snapshot<T> state = new Snapshot<>(null, "", false, null, null);
  private AtomicBoolean cancel = new AtomicBoolean();
  private Future<?> future;
  private Runnable cancellation;

  public Snapshot<T> state() {
    return state;
  }

  public void onCancel(Runnable action) {
    cancellation = action;
  }

  public void attach(Observer<T> value) {
    observer = value;
    value.changed(state);
  }

  public void detach() {
    observer = null;
  }

  public void start(String key, Work<T> work) {
    cancel();
    AtomicBoolean token = new AtomicBoolean();
    cancel = token;
    state = new Snapshot<>(key, "Подготовка…", true, null, null);
    if (observer != null) observer.changed(state);
    future =
        worker.submit(
            () -> {
              try {
                if (token.get()) return;
                T value =
                    work.run(
                        token,
                        message -> publish(token, new Snapshot<>(key, message, true, null, null)));
                if (!token.get()) publish(token, new Snapshot<>(key, "", false, value, null));
              } catch (Exception e) {
                if (!token.get()) publish(token, new Snapshot<>(key, "", false, null, e));
              }
            });
  }

  private void publish(AtomicBoolean token, Snapshot<T> next) {
    main.post(
        () -> {
          if (token != cancel || token.get()) return;
          state = next;
          if (observer != null) observer.changed(next);
        });
  }

  public void cancel() {
    cancel.set(true);
    if (cancellation != null) cancellation.run();
    cancellation = null;
    if (future != null) future.cancel(true);
    future = null;
    state = new Snapshot<>(null, "", false, null, null);
  }

  @Override
  public void close() {
    detach();
    cancel();
    worker.shutdownNow();
    main.removeCallbacksAndMessages(null);
  }
}
