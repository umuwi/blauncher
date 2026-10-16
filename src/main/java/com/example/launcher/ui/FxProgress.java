package com.example.launcher.ui;

import com.example.launcher.service.Http;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Шкала загрузки, которую можно кормить из фонового потока.
 *
 * <p>Http.download сообщает ход после каждого блока в 64 КБ — на быстром канале это сотни раз в
 * секунду. Каждый вызов отдельным Platform.runLater забил бы очередь UI-потока, и лаунчер
 * «замерзал» бы как раз во время загрузки (то же было с выводом установщика Forge, см.
 * ModLoaderService.runInstaller). Поэтому здесь хранится только последнее значение, а в UI-поток
 * уходит не больше одного обновления за раз.
 *
 * <p>Подписи: {@link Style#PERCENT} — «45 %» (узкое место в строке результата поиска),
 * {@link Style#BYTES} — «45 % · 12,3 из 45,6 МБ».
 */
public final class FxProgress {

    public enum Style { PERCENT, BYTES }

    private final ProgressBar bar;
    private final Label label;
    private final Style style;

    private final Object lock = new Object();
    private long done;
    private long total = -1;
    private final AtomicBoolean scheduled = new AtomicBoolean();

    /** @param label подпись к шкале, может быть null */
    public FxProgress(ProgressBar bar, Label label, Style style) {
        this.bar = bar;
        this.label = label;
        this.style = style;
    }

    /** Ход одного файла: «сделано байт из всего». */
    public Http.Progress bytes() {
        return this::post;
    }

    /**
     * Ход файла номер {@code index} (с нуля) из {@code count}: шкала показывает общий ход по всем
     * файлам — для «мод и его зависимости» и для импорта сборки по коду, где размеров заранее нет.
     */
    public Http.Progress part(int index, int count) {
        final long slot = 1000;
        final int n = Math.max(1, count);
        return (d, t) -> {
            long inFile = t > 0 ? Math.min(slot, d * slot / t) : 0;
            post(index * slot + inFile, n * slot);
        };
    }

    /** Файл номер {@code index} из {@code count} начинается — шкала встаёт на его начало. */
    public void startPart(int index, int count) {
        part(index, count).update(0, 1);
    }

    /** «Неизвестно сколько» — бегущая шкала, пока, например, ищется подходящая версия. */
    public void indeterminate() {
        post(0, -1);
    }

    public void post(long done, long total) {
        synchronized (lock) {
            this.done = done;
            this.total = total;
        }
        if (scheduled.compareAndSet(false, true)) {
            Platform.runLater(this::apply);
        }
    }

    private void apply() {
        scheduled.set(false);
        long d, t;
        synchronized (lock) {
            d = done;
            t = total;
        }
        if (t <= 0) {
            bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            if (label != null) label.setText(style == Style.BYTES && d > 0 ? mb(d) + " МБ" : "");
            return;
        }
        double f = Math.max(0, Math.min(1, (double) d / t));
        bar.setProgress(f);
        if (label != null) {
            String pct = (int) Math.floor(f * 100) + " %";
            label.setText(style == Style.BYTES ? pct + " · " + mb(d) + " из " + mb(t) + " МБ" : pct);
        }
    }

    private static String mb(long bytes) {
        return String.format(new Locale("ru"), "%.1f", bytes / 1048576.0);
    }
}
