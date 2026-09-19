package com.agent.mobileuse;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class NotifyReceiver extends BroadcastReceiver {
    private static final String TAG = "AgentNotifyReceiver";
    public static final String ACTION_NOTIFY = "com.agent.mobileuse.ACTION_NOTIFY";
    public static final String ACTION_CLEAR = "com.agent.mobileuse.ACTION_CLEAR";

    public static final String CHANNEL_ID = "dsh_agent_tasks";
    public static final String CHANNEL_NAME = "DeepSeek Agent 任务待办";
    public static final String DEFAULT_TAG = "dsh_agent";
    public static final int DEFAULT_ID = 2020;
    public static final String DEFAULT_URL = "http://127.0.0.1:3080";

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
            String url = intent.getStringExtra("url");
            if (url == null || url.isEmpty()) {
                url = DEFAULT_URL;
            }

            if (title == null) title = "DeepSeek Mobile Agent";
            if (content == null) content = "";

            postNotification(context, nm, tag, id, title, content, url);
        }
    }

    private void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                // android.app.NotificationChannel(String id, CharSequence name, int importance)
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                Constructor<?> ctor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
                // IMPORTANCE_DEFAULT = 3, IMPORTANCE_HIGH = 4
                Object channel = ctor.newInstance(CHANNEL_ID, CHANNEL_NAME, 3);

                Method setDesc = channelClass.getMethod("setDescription", String.class);
                setDesc.invoke(channel, "DeepSeek Harness Agent 任务待办同步通知");

                Method createMethod = nm.getClass().getMethod("createNotificationChannel", channelClass);
                createMethod.invoke(nm, channel);
                Log.d(TAG, "NotificationChannel created/ensured: " + CHANNEL_ID);
            } catch (Throwable t) {
                Log.w(TAG, "ensureChannel reflection warning: " + t.getMessage());
            }
        }
    }

    private void postNotification(Context context, NotificationManager nm, String tag, int id,
                                  String title, String content, String url) {
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

            builder.setContentTitle(title);

            // Extract first non-empty line as brief summary
            String summary = "";
            String[] lines = content.split("\n");
            for (String l : lines) {
                String trimmed = l.trim();
                if (!trimmed.isEmpty()) {
                    summary = trimmed;
                    break;
                }
            }
            if (summary.isEmpty()) summary = content;
            builder.setContentText(summary);

            // BigTextStyle for expandable multiline view
            Notification.BigTextStyle bigStyle = new Notification.BigTextStyle();
            bigStyle.setBigContentTitle(title);
            bigStyle.bigText(content);
            builder.setStyle(bigStyle);

            // Set Icons
            builder.setSmallIcon(R.drawable.dsh_whale_icon);
            try {
                Bitmap avatar = BitmapFactory.decodeResource(context.getResources(), R.drawable.dsh_whale_avatar);
                if (avatar != null) {
                    builder.setLargeIcon(avatar);
                }
            } catch (Throwable t) {
                Log.w(TAG, "decodeResource avatar warning: " + t.getMessage());
            }

            // Click Jump PendingIntent -> Web UI
            try {
                Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // 0x04000000 = PendingIntent.FLAG_IMMUTABLE, 0x08000000 = FLAG_UPDATE_CURRENT
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) {
                    flags |= 0x04000000;
                }
                PendingIntent pi = PendingIntent.getActivity(context, 0, viewIntent, flags);
                builder.setContentIntent(pi);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to create PendingIntent: " + t.getMessage(), t);
            }

            builder.setOngoing(false);
            builder.setAutoCancel(false);

            Notification notification = builder.build();
            nm.notify(tag, id, notification);
            Log.i(TAG, "Notification posted successfully: tag=" + tag + ", id=" + id);
        } catch (Throwable t) {
            Log.e(TAG, "postNotification failed: " + t.getMessage(), t);
        }
    }
}
