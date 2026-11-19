package br.com.arca.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.RemoteException;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

final class PdfPreview implements ServiceConnection {
    private static final long TIMEOUT_MS = 20_000L;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread sourceThread = new HandlerThread("arca-pdf-source");
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final AtomicReference<ParcelFileDescriptor> activeRead = new AtomicReference<>();
    private final MemorySource source;
    private final AlertDialog dialog;
    private final ImageView image;
    private final TextView status;
    private final Button previous;
    private final Button next;

    private final Runnable timeout = () -> fail("A prévia demorou demais para abrir.");
    private Messenger renderer;
    private boolean bound;
    private volatile boolean closed;
    private int pageIndex;
    private int pageCount;

    private PdfPreview(Activity activity, String fileName, byte[] bytes) {
        this.activity = activity;
        source = new MemorySource(bytes);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(12 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, 0);

        status = new TextView(activity);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText("Preparando PDF…");
        layout.addView(status);

        image = new ImageView(activity);
        image.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int height = activity.findViewById(android.R.id.content).getHeight() / 2;
        layout.addView(image, new LinearLayout.LayoutParams(-1, Math.max(height, padding * 8)));

        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        previous = new Button(activity);
        previous.setText("Anterior");
        previous.setEnabled(false);
        previous.setOnClickListener(view -> load(pageIndex - 1));
        controls.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
        next = new Button(activity);
        next.setText("Próxima");
        next.setEnabled(false);
        next.setOnClickListener(view -> load(pageIndex + 1));
        controls.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(controls);

        dialog = new AlertDialog.Builder(activity)
                .setTitle(fileName)
                .setView(layout)
                .setPositiveButton("Fechar", null)
                .create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnDismissListener(ignored -> release());
    }

    static AlertDialog show(Activity activity, String fileName, byte[] bytes) {
        PdfPreview preview = new PdfPreview(activity, fileName, bytes);
        preview.dialog.show();
        preview.start();
        return preview.dialog;
    }

    private void start() {
        sourceThread.start();
        try {
            bound = activity.bindService(new Intent(activity, PdfRenderService.class),
                    this, Context.BIND_AUTO_CREATE);
            if (bound) {
                main.postDelayed(timeout, TIMEOUT_MS);
                return;
            }
        } catch (RuntimeException ignored) {
            // The preview is optional on devices where the service cannot be started.
        }
        fail("O visualizador de PDF está indisponível neste dispositivo.");
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        if (closed) return;
        renderer = new Messenger(binder);
        load(0);
    }

    @Override public void onServiceDisconnected(ComponentName name) {
        fail("A leitura do PDF foi interrompida.");
    }

    @Override public void onBindingDied(ComponentName name) {
        fail("A leitura do PDF foi interrompida.");
    }

    @Override public void onNullBinding(ComponentName name) {
        fail("O visualizador de PDF está indisponível neste dispositivo.");
    }

    private void load(int requestedPage) {
        if (closed || renderer == null || requestedPage < 0
                || (pageCount > 0 && requestedPage >= pageCount)) return;
        previous.setEnabled(false);
        next.setEnabled(false);
        status.setText("Carregando página…");
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, TIMEOUT_MS);

        Messenger target = renderer;
        Handler sourceHandler = new Handler(sourceThread.getLooper());
        reader.execute(() -> readPage(target, sourceHandler, requestedPage));
    }

    private void readPage(Messenger target, Handler sourceHandler, int requestedPage) {
        try {
            StorageManager storage = activity.getSystemService(StorageManager.class);
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try (ParcelFileDescriptor read = pipe[0];
                 ParcelFileDescriptor write = pipe[1];
                 ParcelFileDescriptor pdf = storage.openProxyFileDescriptor(
                         ParcelFileDescriptor.MODE_READ_ONLY, source, sourceHandler)) {
                activeRead.set(read);
                if (closed) return;
                Message request = Message.obtain(null, PdfRenderService.RENDER, requestedPage, 0);
                Bundle descriptors = new Bundle();
                descriptors.putParcelable(PdfRenderService.SOURCE, pdf);
                descriptors.putParcelable(PdfRenderService.OUTPUT, write);
                request.setData(descriptors);
                target.send(request);
                write.close();
                try (DataInputStream stream = new DataInputStream(
                        new ParcelFileDescriptor.AutoCloseInputStream(read))) {
                    int count = stream.readInt();
                    Bitmap page = BitmapFactory.decodeStream(stream);
                    if (count <= requestedPage || page == null) {
                        throw new IOException("Invalid PDF preview");
                    }
                    main.post(() -> showPage(page, requestedPage, count));
                }
            } finally {
                activeRead.set(null);
            }
        } catch (IOException | RemoteException | RuntimeException error) {
            main.post(() -> fail("Não foi possível abrir o PDF. O arquivo pode estar protegido ou danificado."));
        }
    }

    private void showPage(Bitmap page, int index, int count) {
        if (closed) {
            page.recycle();
            return;
        }
        main.removeCallbacks(timeout);
        image.setImageBitmap(page);
        pageIndex = index;
        pageCount = count;
        status.setText("Página " + (index + 1) + " de " + count);
        previous.setEnabled(index > 0);
        next.setEnabled(index < count - 1);
    }

    private void fail(String message) {
        if (closed) return;
        release();
        status.setText(message);
        previous.setEnabled(false);
        next.setEnabled(false);
    }

    private void release() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(timeout);
        image.setImageDrawable(null);
        source.clear();
        if (bound) activity.unbindService(this);
        ParcelFileDescriptor read = activeRead.getAndSet(null);
        if (read != null) {
            try { read.close(); } catch (IOException ignored) { }
        }
        reader.shutdownNow();
        sourceThread.quitSafely();
    }

    private static final class MemorySource extends ProxyFileDescriptorCallback {
        private byte[] data;

        MemorySource(byte[] data) {
            this.data = data;
        }

        @Override public synchronized long onGetSize() {
            return data == null ? 0 : data.length;
        }

        @Override public synchronized int onRead(long offset, int size, byte[] buffer)
                throws ErrnoException {
            if (data == null || offset < 0) {
                throw new ErrnoException("read", OsConstants.EIO);
            }
            if (offset >= data.length) return 0;
            int count = Math.min(Math.min(size, buffer.length), data.length - (int) offset);
            System.arraycopy(data, (int) offset, buffer, 0, count);
            return count;
        }

        @Override public void onRelease() {
        }

        synchronized void clear() {
            if (data != null) Arrays.fill(data, (byte) 0);
            data = null;
        }
    }
}
