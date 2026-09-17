package com.agent;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

public class DaemonMain {
    private static final String STATUS_FILE = "/data/local/tmp/vd_status.json";
    private static final String STOP_SIGNAL = "/data/local/tmp/vd_stop";

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

        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            Object displayBinder = getService.invoke(null, "display");

            Class<?> stubClass = Class.forName("android.hardware.display.IDisplayManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", android.os.IBinder.class);
            Object displayService = asInterface.invoke(null, displayBinder);

            Class<?> dmClass = Class.forName("android.hardware.display.DisplayManager");
            java.lang.reflect.Constructor<?> dmCtor = dmClass.getDeclaredConstructor(android.content.Context.class);
            dmCtor.setAccessible(true);
            DisplayManager dm = (DisplayManager) dmCtor.newInstance((Object) null);

            java.lang.reflect.Field serviceField = dmClass.getDeclaredField("mGlobal");
            serviceField.setAccessible(true);

            HandlerThread drainThread = new HandlerThread("ImageReaderDrainer");
            drainThread.start();
            Handler drainHandler = new Handler(drainThread.getLooper());

            ImageReader reader = ImageReader.newInstance(sWidth, sHeight, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    try {
                        Image img = r.acquireLatestImage();
                        if (img != null) {
                            img.close();
                        }
                    } catch (Throwable t) {}
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
            new File(STATUS_FILE).delete();
            System.out.println("[AgentDaemon] Daemon safely terminated.");
            System.exit(0);

        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
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
