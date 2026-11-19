package br.com.arca.offline;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;

import java.io.DataOutputStream;
import java.io.IOException;

/** Renders one page in the isolated process and sends a bounded image through a pipe. */
public class PdfRenderService extends Service {
    static final int RENDER = 1;
    static final String SOURCE = "source";
    static final String OUTPUT = "output";
    private static final int MAX_SIDE = 1400;

    private HandlerThread worker;
    private Messenger messenger;

    @Override public void onCreate() {
        super.onCreate();
        worker = new HandlerThread("arca-pdf-render");
        worker.start();
        messenger = new Messenger(new Handler(worker.getLooper(), message -> {
            if (message.what != RENDER) return false;
            Bundle data = message.getData();
            render(data.getParcelable(SOURCE), data.getParcelable(OUTPUT), message.arg1);
            return true;
        }));
    }

    @Override public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override public void onDestroy() {
        worker.quitSafely();
        super.onDestroy();
    }

    private static void render(ParcelFileDescriptor source, ParcelFileDescriptor output, int index) {
        try (ParcelFileDescriptor input = source; ParcelFileDescriptor pipe = output) {
            if (input == null || pipe == null) return;
            try (DataOutputStream stream = new DataOutputStream(
                        new ParcelFileDescriptor.AutoCloseOutputStream(pipe));
                 PdfRenderer pdf = new PdfRenderer(input)) {
                int pageCount = pdf.getPageCount();
                if (index < 0 || index >= pageCount) return;
                try (PdfRenderer.Page page = pdf.openPage(index)) {
                    int width = page.getWidth();
                    int height = page.getHeight();
                    if (width <= 0 || height <= 0) return;
                    float scale = (float) MAX_SIDE / Math.max(width, height);
                    Bitmap image = Bitmap.createBitmap(Math.max(1, (int) (width * scale)),
                            Math.max(1, (int) (height * scale)), Bitmap.Config.ARGB_8888);
                    try {
                        image.eraseColor(Color.WHITE);
                        Matrix transform = new Matrix();
                        transform.setScale(scale, scale);
                        page.render(image, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        stream.writeInt(pageCount);
                        image.compress(Bitmap.CompressFormat.PNG, 100, stream);
                    } finally {
                        image.recycle();
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Closing the pipe tells the caller that the page could not be rendered.
        }
    }
}
