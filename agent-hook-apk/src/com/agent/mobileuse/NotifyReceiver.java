package com.agent.mobileuse;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class NotifyReceiver extends BroadcastReceiver {
    private static final String TAG = "AgentNotifyReceiver";
    public static final String ACTION_NOTIFY = "com.agent.mobileuse.ACTION_NOTIFY";
    public static final String ACTION_CLEAR = "com.agent.mobileuse.ACTION_CLEAR";

    public static final String CHANNEL_ID = "dsh_agent_completed";
    public static final String CHANNEL_NAME = "DeepSeek Agent 任务完成";
    public static final String DEFAULT_TAG = "dsh_agent";
    public static final int DEFAULT_ID = 2020;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "onReceive action: " + action);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            Log.e(TAG, "NotificationManager is null");
            return;
        }

        String tag = intent.getStringExtra("tag");
        if (tag == null || tag.isEmpty()) {
            tag = DEFAULT_TAG;
        }
        int id = intent.getIntExtra("id", DEFAULT_ID);

        if (ACTION_CLEAR.equals(action)) {
            Log.i(TAG, "Canceling notification: tag=" + tag + ", id=" + id);
            nm.cancel(tag, id);
            return;
        }

        if (ACTION_NOTIFY.equals(action)) {
            String title = intent.getStringExtra("title");
            String subtext = intent.getStringExtra("subtext");
            String content = intent.getStringExtra("content");
            String sessionId = intent.getStringExtra("session_id");
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = intent.getStringExtra("session");
            }
            int total = intent.getIntExtra("total", 0);
            int completed = intent.getIntExtra("completed", 0);
            boolean isCompleted = intent.getBooleanExtra("is_completed", false) || (total > 0 && completed >= total);

            Log.i(TAG, "Received notification signal: title=" + title + " subtext=" + subtext + " (" + completed + "/" + total + ") isCompleted=" + isCompleted + " sessionId=" + sessionId);

            // Cancel any previous notification to keep notification drawer clean
            nm.cancel(tag, id);

            // Trigger high-priority heads-up notification when task completion signal is given
            if (isCompleted) {
                postCompletedNotification(context, nm, tag, id, title, subtext, content, total, completed, sessionId);
            } else {
                Log.i(TAG, "In-progress step (" + completed + "/" + total + ") suppressed to avoid disturbance.");
            }
        }
    }

    /**
     * Clean all emojis, symbols, and formatting artifacts for a crisp, professional text presentation.
     */
    private static String cleanEmoji(String text) {
        if (text == null) return "";
        // Replace unicode todo indicators with clean text prefixes
        text = text.replace("☑", "[已完成] ")
                   .replace("◉", "[进行中] ")
                   .replace("☐", "[待办] ")
                   .replace("🎉", "")
                   .replace(">>", "");
        // Remove unicode emoji ranges and miscellaneous symbols
        text = text.replaceAll("[\\p{So}\\p{Cn}]", "");
        text = text.replaceAll("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]", "");
        text = text.replaceAll("[\\u2600-\\u27BF]", "");
        text = text.replaceAll("[\\uE000-\\uF8FF]", "");
        return text.trim();
    }

    private static CharSequence parseCleanHtml(String text) {
        if (text == null) return "";
        String cleaned = cleanEmoji(text);
        if (!cleaned.contains("<") && !cleaned.contains("&")) {
            return cleaned;
        }
        try {
            String formatted = cleaned.replace("\n", "<br>");
            return Html.fromHtml(formatted);
        } catch (Throwable t) {
            return cleaned;
        }
    }

    public static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                Constructor<?> ctor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
                // IMPORTANCE_HIGH = 4 (Heads-up banner notification with sound and vibration)
                Object channel = ctor.newInstance(CHANNEL_ID, CHANNEL_NAME, 4);

                Method setDesc = channelClass.getMethod("setDescription", String.class);
                setDesc.invoke(channel, "DeepSeek Harness Agent 任务全部完成提醒");

                Method enableLights = channelClass.getMethod("enableLights", boolean.class);
                enableLights.invoke(channel, true);

                Method enableVibration = channelClass.getMethod("enableVibration", boolean.class);
                enableVibration.invoke(channel, true);

                try {
                    AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build();
                    Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                    Method setSound = channelClass.getMethod("setSound", Uri.class, AudioAttributes.class);
                    setSound.invoke(channel, soundUri, audioAttributes);
                } catch (Throwable ignored) {}

                Method createMethod = nm.getClass().getMethod("createNotificationChannel", channelClass);
                createMethod.invoke(nm, channel);
                Log.d(TAG, "NotificationChannel created/ensured: " + CHANNEL_ID);
            } catch (Throwable t) {
                Log.w(TAG, "ensureChannel reflection warning: " + t.getMessage());
            }
        }
    }

    public static void postCompletedNotification(Context context, NotificationManager nm, String tag, int id,
                                                 String title, String content,
                                                 int total, int completed) {
        postCompletedNotification(context, nm, tag, id, title, null, content, total, completed);
    }

    /**
     * Create a crisp vector-drawn LargeIcon: Pure white circle background with a vibrant emerald green checkmark.
     */
    private static Bitmap createWhiteGreenCheckBitmap(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        float center = size / 2.0f;
        float radius = center - 4.0f;

        // 1. Draw smooth white circular background
        android.graphics.Paint bgPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0xFFFFFFFF);
        bgPaint.setStyle(android.graphics.Paint.Style.FILL);
        canvas.drawCircle(center, center, radius, bgPaint);

        // 2. Draw vibrant emerald green checkmark
        android.graphics.Paint checkPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        checkPaint.setColor(0xFF10B981); // Emerald / Material Green
        checkPaint.setStyle(android.graphics.Paint.Style.STROKE);
        checkPaint.setStrokeWidth(size * 0.11f);
        checkPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(android.graphics.Paint.Join.ROUND);

        android.graphics.Path path = new android.graphics.Path();
        float p1X = size * 0.28f;
        float p1Y = size * 0.52f;
        float p2X = size * 0.44f;
        float p2Y = size * 0.68f;
        float p3X = size * 0.74f;
        float p3Y = size * 0.36f;

        path.moveTo(p1X, p1Y);
        path.lineTo(p2X, p2Y);
        path.lineTo(p3X, p3Y);

        canvas.drawPath(path, checkPaint);
        return bitmap;
    }

    public static void postCompletedNotification(Context context, NotificationManager nm, String tag, int id,
                                                 String title, String subtext, String content,
                                                 int total, int completed) {
        postCompletedNotification(context, nm, tag, id, title, subtext, content, total, completed, null);
    }

    public static void postCompletedNotification(Context context, NotificationManager nm, String tag, int id,
                                                 String title, String subtext, String content,
                                                 int total, int completed, String sessionId) {
        try {
            ensureChannel(nm);

            Notification.Builder builder = new Notification.Builder(context);
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Method setChannelMethod = builder.getClass().getMethod("setChannelId", String.class);
                    setChannelMethod.invoke(builder, CHANNEL_ID);
                } catch (Throwable t) {
                    Log.w(TAG, "setChannelId reflection warning: " + t.getMessage());
                }
            }

            // Crisp pure title without emoji or count numbers
            String cleanTitle = cleanEmoji(title);
            if (cleanTitle.isEmpty() || cleanTitle.contains("完成")) {
                cleanTitle = "任务已经完成！";
            }
            builder.setContentTitle(cleanTitle);

            // Clean full content text for both collapsed summary and expanded big text
            String cleanContent = cleanEmoji(content);
            if (cleanContent.isEmpty()) {
                cleanContent = "所有执行事项均已处理完毕";
            }
            builder.setContentText(cleanContent);

            // SubText: Session Title if provided, otherwise default prompt
            String cleanSubtext = cleanEmoji(subtext);
            if (cleanSubtext.isEmpty()) {
                cleanSubtext = "执行完毕 · 点击进入控制台";
            }
            builder.setSubText(cleanSubtext);

            // BigTextStyle for rich clean view without emoji
            Notification.BigTextStyle bigStyle = new Notification.BigTextStyle();
            bigStyle.setBigContentTitle(cleanTitle);
            bigStyle.bigText(parseCleanHtml(cleanContent));
            builder.setStyle(bigStyle);

            // Set SmallIcon
            builder.setSmallIcon(R.drawable.dsh_whale_icon);

            // Set LargeIcon: Crisp White Circle with vibrant Green Checkmark
            try {
                Bitmap checkIcon = createWhiteGreenCheckBitmap(192);
                if (checkIcon != null) {
                    builder.setLargeIcon(checkIcon);
                }
            } catch (Throwable t) {
                Log.w(TAG, "createWhiteGreenCheckBitmap warning: " + t.getMessage());
            }

            // Click Jump PendingIntent -> Launch DemoDialogActivity (Action Button Overlay / 灵动坞)
            Intent overlayIntent = new Intent(context, DemoDialogActivity.class);
            overlayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (sessionId != null && !sessionId.isEmpty()) {
                overlayIntent.putExtra("session_id", sessionId);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= 0x04000000; // FLAG_IMMUTABLE
            }
            int requestCode = (sessionId != null && !sessionId.isEmpty()) ? sessionId.hashCode() : id;
            PendingIntent pi = PendingIntent.getActivity(context, requestCode, overlayIntent, flags);
            builder.setContentIntent(pi);
            builder.setAutoCancel(true); // Dismiss notification when clicked

            builder.setPriority(2); // Notification.PRIORITY_MAX = 2
            builder.setShowWhen(true);
            builder.setOngoing(false);

            Notification notification = builder.build();
            nm.notify(tag, id, notification);
            Log.i(TAG, "Task completed heads-up notification posted: tag=" + tag + ", id=" + id);
        } catch (Throwable t) {
            Log.e(TAG, "postCompletedNotification failed: " + t.getMessage(), t);
        }
    }
}
