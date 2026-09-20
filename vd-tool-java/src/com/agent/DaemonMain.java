package com.agent;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Display;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class DaemonMain {
    private static final String STATUS_FILE = "/data/local/tmp/vd_status.json";
    private static final String STOP_SIGNAL = "/data/local/tmp/vd_stop";

    /**
     * Newest frame of the virtual display, published as JPEG for whoever asks for a
     * screenshot. This daemon already owns the display's output surface, so caching
     * a frame here costs one encode per changed frame — against the ~1.8s of CPU
     * `screencap -p` burns inside the PNG encoder to produce a 2.9 MB file.
     *
     * It MUST stay at the display's full resolution: the screenshot tool derives the
     * model-facing scale factor from these pixel dimensions, so a downscaled cache
     * would silently break its coordinate mapping.
     */
    private static final String FRAME_CACHE = "/data/local/tmp/vd_latest.jpg";
    private static final int FRAME_JPEG_QUALITY = 85;
    /**
     * Ceiling on cache updates, not a rate paid continuously: the display only
     * composites when its content changes, so a still screen produces no frames at
     * all. 66ms keeps the cache within about one frame of the screen.
     */
    private static final long FRAME_MIN_INTERVAL_MS = 66;

    private static int sWidth = 1080;
    private static int sHeight = 2400;
    private static int sDpi = 420;

    public static void main(String[] args) {
        if (args.length >= 3) {
            try {
                sWidth = Integer.parseInt(args[0]);
                sHeight = Integer.parseInt(args[1]);
                sDpi = Integer.parseInt(args[2]);
            } catch (Exception e) {
                System.err.println("[AgentDaemon] Failed to parse display args: " + e.getMessage());
            }
        }

        System.out.println("[AgentDaemon] Starting virtual display: " + sWidth + "x" + sHeight + " @ " + sDpi + " DPI");

        // Drop any cache left behind by a previous run before this daemon publishes
        // anything, so a stale frame can never be served as if it were current.
        new File(FRAME_CACHE).delete();

        try {
            if (android.os.Looper.myLooper() == null) {
                android.os.Looper.prepare();
            }
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method systemMain = atClass.getMethod("systemMain");
            Object at = systemMain.invoke(null);
            Method getSysCtx = atClass.getMethod("getSystemContext");
            android.content.Context ctx = (android.content.Context) getSysCtx.invoke(at);

            Class<?> dmClass = Class.forName("android.hardware.display.DisplayManager");
            java.lang.reflect.Constructor<?> dmCtor = dmClass.getDeclaredConstructor(android.content.Context.class);
            dmCtor.setAccessible(true);
            DisplayManager dm = (DisplayManager) dmCtor.newInstance(ctx);

            try {
                java.lang.reflect.Field mirrorField = dmClass.getDeclaredField("mDisplayIdToMirror");
                mirrorField.setAccessible(true);
                mirrorField.setInt(dm, 0);
            } catch (Throwable ignored) {}

            java.lang.reflect.Field serviceField = dmClass.getDeclaredField("mGlobal");
            serviceField.setAccessible(true);

            HandlerThread drainThread = new HandlerThread("ImageReaderDrainer");
            drainThread.start();
            Handler drainHandler = new Handler(drainThread.getLooper());

            // Encoding gets its own thread: a ~16ms JPEG encode on the drainer would
            // keep the reader's buffer checked out longer than it needs to be.
            HandlerThread encodeThread = new HandlerThread("FrameJpegEncoder");
            encodeThread.start();
            final Handler encodeHandler = new Handler(encodeThread.getLooper());

            final Bitmap frameBuf = Bitmap.createBitmap(sWidth, sHeight, Bitmap.Config.ARGB_8888);
            final AtomicBoolean encoding = new AtomicBoolean(false);
            final long[] lastFrameAt = {0L};

            ImageReader reader = ImageReader.newInstance(sWidth, sHeight, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null) return;
                        long now = SystemClock.uptimeMillis();
                        if (now - lastFrameAt[0] >= FRAME_MIN_INTERVAL_MS && encoding.compareAndSet(false, true)) {
                            lastFrameAt[0] = now;
                            try {
                                // Copy out, then leave the buffer alone: the display owns
                                // only two of these, and holding one while encoding would
                                // starve its producer.
                                copyImageToBitmap(img, frameBuf);
                                encodeHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            writeFrameJpeg(frameBuf);
                                        } catch (Throwable t) {
                                            System.err.println("[AgentDaemon] Frame cache failed: " + t);
                                        } finally {
                                            encoding.set(false);
                                        }
                                    }
                                });
                            } catch (Throwable t) {
                                encoding.set(false); // never leave the cache permanently latched
                            }
                        }
                    } catch (Throwable t) {
                        // This listener MUST NOT die: the display's output queue is drained
                        // here, and a dead drainer stops the display producing frames. A
                        // failed cache update is never worth that.
                    } finally {
                        if (img != null) {
                            try { img.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            }, drainHandler);

            // 0x609 = FLAG_PUBLIC (1) | FLAG_OWN_CONTENT_ONLY (8) | FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS (512) | FLAG_TRUSTED (1024)
            int flags = 1545;
            VirtualDisplay vd = dm.createVirtualDisplay("AgentVirtualDisplay", sWidth, sHeight, sDpi, reader.getSurface(), flags);

            if (vd == null || vd.getDisplay() == null) {
                System.err.println("[AgentDaemon] Failed to create virtual display!");
                writeStatus("stopped", -1);
                System.exit(1);
                return;
            }

            Display display = vd.getDisplay();
            int displayId = display.getDisplayId();
            System.out.println("[AgentDaemon] Virtual Display created successfully! ID: " + displayId);

            // Optional: set IME policy to local virtual display (0 = DISPLAY_IME_POLICY_LOCAL)
            try {
                Class<?> wmClass = Class.forName("android.view.WindowManagerGlobal");
                Method getWmService = wmClass.getMethod("getWindowManagerService");
                Object wmService = getWmService.invoke(null);
                Method setImePolicy = wmService.getClass().getMethod("setDisplayImePolicy", int.class, int.class);
                setImePolicy.invoke(wmService, displayId, 0);
                System.out.println("[AgentDaemon] Set Display " + displayId + " IME policy to LOCAL (0)");
            } catch (Throwable t) {
                System.err.println("[AgentDaemon] Warning: Failed to set IME policy: " + t.getMessage());
            }

            // Write status
            int pid = android.os.Process.myPid();
            writeStatus("running", displayId, pid, sWidth, sHeight, sDpi);

            File stopFile = new File(STOP_SIGNAL);
            if (stopFile.exists()) stopFile.delete();

            // Loop checking stop signal
            while (!stopFile.exists()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }

            System.out.println("[AgentDaemon] Stop signal detected. Cleaning up...");
            vd.release();
            reader.close();
            drainThread.quitSafely();
            encodeThread.quitSafely();
            new File(STATUS_FILE).delete();
            new File(FRAME_CACHE).delete();
            System.out.println("[AgentDaemon] Daemon safely terminated.");
            System.exit(0);

        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Copy one frame out of the reader's buffer.
     *
     * The plane may carry row padding — gralloc aligns the stride, and 1272*4 = 5088
     * is not a multiple of the usual 64-byte alignment — while copyPixelsFromBuffer
     * assumes tightly packed rows. A padded plane is therefore repacked row by row
     * instead of copied whole, which would skew the image.
     */
    private static void copyImageToBitmap(Image img, Bitmap out) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer src = plane.getBuffer();
        int packedRow = sWidth * 4;
        if (plane.getPixelStride() == 4 && plane.getRowStride() == packedRow) {
            out.copyPixelsFromBuffer(src);
            return;
        }
        byte[] packed = new byte[packedRow * sHeight];
        for (int y = 0; y < sHeight; y++) {
            src.position(y * plane.getRowStride());
            src.get(packed, y * packedRow, packedRow);
        }
        out.copyPixelsFromBuffer(ByteBuffer.wrap(packed));
    }

    /**
     * Publish the cached frame atomically, so a reader can never pick up a
     * half-written file.
     */
    private static void writeFrameJpeg(Bitmap bmp) throws Exception {
        File tmp = new File(FRAME_CACHE + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, fos)) {
                throw new IllegalStateException("JPEG encoder reported failure");
            }
            fos.flush();
        } finally {
            try { fos.close(); } catch (Throwable ignored) {}
        }
        if (!tmp.renameTo(new File(FRAME_CACHE))) {
            throw new IllegalStateException("could not publish " + FRAME_CACHE);
        }
    }

    private static void writeStatus(String status, int displayId) {
        writeStatus(status, displayId, android.os.Process.myPid(), sWidth, sHeight, sDpi);
    }

    private static void writeStatus(String status, int displayId, int pid, int w, int h, int dpi) {
        try {
            String json = String.format("{\"status\":\"%s\",\"pid\":%d,\"display_id\":%d,\"width\":%d,\"height\":%d,\"dpi\":%d}\n",
                    status, pid, displayId, w, h, dpi);
            FileOutputStream fos = new FileOutputStream(STATUS_FILE);
            fos.write(json.getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Exception e) {
            System.err.println("[AgentDaemon] Failed to write status file: " + e.getMessage());
        }
    }
}
