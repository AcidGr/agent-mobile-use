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
            String content = intent.getStringExtra("content");
            int total = intent.getIntExtra("total", 0);
            int completed = intent.getIntExtra("completed", 0);
            boolean isCompleted = intent.getBooleanExtra("is_completed", false) || (total > 0 && completed >= total);

            Log.i(TAG, "Received notification signal: title=" + title + " (" + completed + "/" + total + ") isCompleted=" + isCompleted);

            // Cancel any previous notification to keep notification drawer clean
            nm.cancel(tag, id);

            // Trigger high-priority heads-up notification when task completion signal is given
            if (isCompleted) {
                postCompletedNotification(context, nm, tag, id, title, content, total, completed);
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

            // Crisp title without emoji
            String cleanTitle = cleanEmoji(title);
            if (cleanTitle.isEmpty() || !cleanTitle.contains("完成")) {
                if (total > 0) {
                    cleanTitle = "[Mobile Agent] 任务已全部完成 (" + completed + "/" + total + ")";
                } else {
                    cleanTitle = "[Mobile Agent] 任务已全部完成";
                }
            }
            builder.setContentTitle(cleanTitle);

            // Summary text for collapsed notification view
            String summary = "";
            String[] lines = content.split("<br\\s*/?>|\n");
            for (String l : lines) {
                String plain = cleanEmoji(l.replaceAll("<[^>]*>", "").trim());
                if (!plain.isEmpty() && !plain.startsWith("───")) {
                    summary = plain;
                    break;
                }
            }
            if (summary.isEmpty()) summary = "所有执行事项均已处理完毕";
            builder.setContentText(summary);

            builder.setSubText("执行完毕 · 点击进入控制台");

            // BigTextStyle for rich clean view without emoji
            Notification.BigTextStyle bigStyle = new Notification.BigTextStyle();
            bigStyle.setBigContentTitle(cleanTitle);
            bigStyle.bigText(parseCleanHtml(content));
            builder.setStyle(bigStyle);

            // Set SmallIcon
            builder.setSmallIcon(R.drawable.dsh_whale_icon);

            // Set LargeIcon: Green Whale Avatar symbolizing successful completion
            try {
                Bitmap greenWhale = BitmapFactory.decodeResource(context.getResources(), R.drawable.dsh_whale_avatar_green);
                if (greenWhale != null) {
                    builder.setLargeIcon(greenWhale);
                }
            } catch (Throwable t) {
                Log.w(TAG, "decodeResource green whale avatar warning: " + t.getMessage());
            }

            // Click Jump PendingIntent -> Launch DemoDialogActivity (Action Button Overlay / 灵动坞)
            Intent overlayIntent = new Intent(context, DemoDialogActivity.class);
            overlayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= 0x04000000; // FLAG_IMMUTABLE
            }
            PendingIntent pi = PendingIntent.getActivity(context, 0, overlayIntent, flags);
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
