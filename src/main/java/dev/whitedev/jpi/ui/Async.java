package dev.whitedev.jpi.ui;

import javax.swing.SwingWorker;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public final class Async {
    private Async() {}

    public static <T> void run(final Callable<T> task, final Consumer<T> success, final Consumer<Throwable> failure) {
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                try { success.accept(get()); }
                catch (Throwable error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    failure.accept(cause);
                }
            }
        }.execute();
    }
}
