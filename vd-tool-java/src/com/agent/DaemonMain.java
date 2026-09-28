package com.agent;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;
import android.view.Surface;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

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

            ImageReader reader = ImageReader.newInstance(sWidth, sHeight, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                    } catch (Throwable ignored) {
                    } finally {
                        if (img != null) {
                            try { img.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            }, drainHandler);

            // 0x14609 = FLAG_PUBLIC (1) | FLAG_OWN_CONTENT_ONLY (8) | FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS (512) |
            //           FLAG_TRUSTED (1024) | VIRTUAL_DISPLAY_FLAG_OWN_FOCUS (16384) |
            //           VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED (65536)
            int flags = 1545 | 16384 | 65536;
            VirtualDisplay vd = null;

            // Attempt to dynamically copy Display 0 Cutout (notch / hole-punch) to match physical metrics 1:1
            try {
                Display defaultDisplay = dm.getDisplay(0);
                Object cutout = null;
                if (defaultDisplay != null) {
                    Method mGetCutout = defaultDisplay.getClass().getMethod("getCutout");
                    cutout = mGetCutout.invoke(defaultDisplay);
                }

                Class<?> cBuilder = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
                java.lang.reflect.Constructor<?> ctor = cBuilder.getConstructor(String.class, int.class, int.class, int.class);
                Object builder = ctor.newInstance("AgentVirtualDisplay", sWidth, sHeight, sDpi);
                cBuilder.getMethod("setSurface", Class.forName("android.view.Surface")).invoke(builder, reader.getSurface());
                cBuilder.getMethod("setFlags", int.class).invoke(builder, flags);

                if (cutout != null) {
                    cBuilder.getMethod("setDisplayCutout", Class.forName("android.view.DisplayCutout")).invoke(builder, cutout);
                    System.out.println("[AgentDaemon] Mirroring physical Display 0 Cutout to Virtual Display: " + cutout);
                }

                Object config = cBuilder.getMethod("build").invoke(builder);
                Method mCreateVD = dm.getClass().getMethod("createVirtualDisplay", Class.forName("android.hardware.display.VirtualDisplayConfig"));
                vd = (VirtualDisplay) mCreateVD.invoke(dm, config);
            } catch (Throwable t) {
                // Deliberately no legacy-API fallback: silently downgrading would start a
                // display with different flags/metrics (no notch mirroring, no OWN_FOCUS)
                // while the console still reports a healthy screen. Surface the real error
                // instead of masking it.
                System.err.println("[AgentDaemon] VirtualDisplayConfig creation failed (no fallback): " + t);
                t.printStackTrace();
            }

            if (vd == null || vd.getDisplay() == null) {
                System.err.println("[AgentDaemon] Virtual display creation failed; not reporting a running screen.");
                writeStatus("failed", -1);
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

            // Bind the stream server BEFORE publishing "running". The web console treats
            // status=="running" as "the video pipeline is up", so publishing it first made
            // clients race the 127.0.0.1:3071 listener and land on a black screen. Binding
            // first removes that window entirely.
            if (!startStreamServer(vd, reader)) {
                System.err.println("[AgentDaemon] Failed to bind stream server on 127.0.0.1:3071; not reporting a running screen.");
                writeStatus("failed", -1);
                System.exit(1);
                return;
            }
            System.out.println("[AgentDaemon] Stream server ready on 127.0.0.1:3071");

            // Publish status only after the encoder pipeline can accept clients.
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
            stopStreamServer();
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

    private static ServerSocket sStreamServer = null;

    /**
     * Binds the internal stream listener on 127.0.0.1:3071 and starts the accept loop.
     *
     * Synchronous on purpose: the caller must not publish status="running" until the port
     * is actually accepting connections, otherwise the web console races the listener.
     *
     * @return true when the socket is bound and the accept loop is running.
     */
    private static boolean startStreamServer(final VirtualDisplay vd, final ImageReader reader) {
        try {
            sStreamServer = new ServerSocket();
            sStreamServer.setReuseAddress(true);
            sStreamServer.bind(new InetSocketAddress("127.0.0.1", 3071));
        } catch (Throwable err) {
            System.err.println("[AgentDaemon] Stream server bind failed: " + err.getMessage());
            return false;
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("[AgentDaemon] Stream server listening on 127.0.0.1:3071");
                while (!sStreamServer.isClosed()) {
                    Socket client = null;
                    try {
                        client = sStreamServer.accept();
                        client.setTcpNoDelay(true);
                        System.out.println("[AgentDaemon] Stream client connected from " + client.getRemoteSocketAddress());
                        // Serve each client on its own thread so a long-lived encoder session
                        // cannot block the accept loop (the gateway is the single-writer hub,
                        // so this normally stays at one connection).
                        final Socket c = client;
                        Thread worker = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    handleStreamClient(vd, reader, c);
                                } catch (Throwable err) {
                                    System.err.println("[AgentDaemon] Stream client session closed: " + err.getMessage());
                                } finally {
                                    try { c.close(); } catch (Throwable ignored) {}
                                }
                            }
                        }, "StreamClientThread");
                        worker.setDaemon(true);
                        worker.start();
                        client = null; // ownership transferred to the worker thread
                    } catch (Throwable err) {
                        if (sStreamServer.isClosed()) break;
                        System.err.println("[AgentDaemon] Stream server error: " + err.getMessage());
                    } finally {
                        if (client != null) {
                            try { client.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }, "StreamServerThread");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void stopStreamServer() {
        if (sStreamServer != null) {
            try {
                sStreamServer.close();
            } catch (Throwable ignored) {}
        }
    }

    private static void handleStreamClient(VirtualDisplay vd, ImageReader reader, Socket client) throws Exception {
        MediaCodec codec = null;
        Surface encoderSurface = null;
        try {
            codec = MediaCodec.createEncoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", sWidth, sHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000); // 6 Mbps
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1s keyframe interval
            try {
                format.setLong("repeat-previous-frame-after", 100000L); // 100ms
            } catch (Throwable ignored) {}

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = codec.createInputSurface();
            codec.start();

            // Direct SurfaceFlinger GPU composition to hardware encoder
            vd.setSurface(encoderSurface);
            System.out.println("[AgentDaemon] VirtualDisplay output directed to MediaCodec");

            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(client.getOutputStream(), 65536));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] buf = null;

            while (!client.isClosed() && !client.isOutputShutdown()) {
                int outIndex = codec.dequeueOutputBuffer(info, 20000);
                if (outIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outIndex);
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);

                        if (buf == null || buf.length < info.size) {
                            buf = new byte[info.size];
                        }
                        outputBuffer.get(buf, 0, info.size);

                        dos.writeInt(info.size);
                        dos.writeInt(info.flags);
                        dos.writeLong(info.presentationTimeUs);
                        dos.write(buf, 0, info.size);
                        dos.flush();
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                }
            }
        } finally {
            // Restore surface back to ImageReader
            try {
                vd.setSurface(reader.getSurface());
                System.out.println("[AgentDaemon] VirtualDisplay output restored to ImageReader");
            } catch (Throwable t) {
                System.err.println("[AgentDaemon] Failed to restore surface: " + t.getMessage());
            }
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                try { codec.release(); } catch (Throwable ignored) {}
            }
            if (encoderSurface != null) {
                try { encoderSurface.release(); } catch (Throwable ignored) {}
            }
        }
    }
}
