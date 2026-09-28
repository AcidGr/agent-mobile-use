package com.agent.mobileuse;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class QuestionActivity extends Activity {
    private static final String TAG = "QuestionActivity";
    public static final int NOTIFICATION_ID = 20086;
    private static final String CHANNEL_ID = "agent_question_channel";
    private static final String CHANNEL_NAME = "Agent 交互确认";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleActionIntent(getIntent());
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleActionIntent(intent);
        finish();
        overridePendingTransition(0, 0);
    }

    private void handleActionIntent(Intent intent) {
        if (intent == null) return;

        // 1. Capsule action for GlowService (START_FOREGROUND, START_BACKGROUND, STOP)
        String capsuleAction = intent.getStringExtra("capsule_action");
        if (capsuleAction != null) {
            try {
                Intent sIntent = new Intent(this, GlowService.class);
                sIntent.setAction(capsuleAction);
                String sid = intent.getStringExtra("session_id");
                if (sid != null) sIntent.putExtra("session_id", sid);
                String title = intent.getStringExtra("session_title");
                if (title != null) sIntent.putExtra("session_title", title);
                if ("STOP".equals(capsuleAction)) {
                    dismissCapsule((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE));
                } else {
                    boolean started = false;
                    if (Build.VERSION.SDK_INT >= 26) {
                        try {
                            Method m = getClass().getMethod("startForegroundService", Intent.class);
                            m.invoke(this, sIntent);
                            started = true;
                        } catch (Throwable ignored) {}
                    }
                    if (!started) {
                        startService(sIntent);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to handle capsule_action: " + t.getMessage(), t);
            }
            return;
        }

        // 2. Notification cancel
        if (intent.getBooleanExtra("cancel_question", false)) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(NOTIFICATION_ID);
                }
            } catch (Throwable ignored) {}
            return;
        }

        if (intent.getBooleanExtra("clear_completed", false)) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(NotifyReceiver.DEFAULT_TAG, NotifyReceiver.DEFAULT_ID);
                }
            } catch (Throwable ignored) {}
            return;
        }

        // 3. Task Completion Notification
        if (intent.getBooleanExtra("is_completed", false)) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    String title = intent.getStringExtra("title");
                    String sessionTitle = intent.getStringExtra("session_title");
                    if (sessionTitle == null || sessionTitle.isEmpty()) {
                        sessionTitle = intent.getStringExtra("subtext");
                    }
                    String content = intent.getStringExtra("content");
                    String tag = intent.getStringExtra("tag");
                    if (tag == null || tag.isEmpty()) tag = NotifyReceiver.DEFAULT_TAG;
                    int id = intent.getIntExtra("id", NotifyReceiver.DEFAULT_ID);
                    String sessionId = intent.getStringExtra("session_id");
                    if (sessionId == null || sessionId.isEmpty()) {
                        sessionId = intent.getStringExtra("session");
                    }

                    dismissCapsule(nm);
                    NotifyReceiver.postCompletedNotification(this, nm, tag, id, title, sessionTitle, content, sessionId);
                }
            } catch (Throwable t) {
                Log.e(TAG, "postNotification failed: " + t.getMessage(), t);
            }
            return;
        }

        // 4. Question Notification
        String reqId = intent.getStringExtra("request_id");
        String dataJson = intent.getStringExtra("data");
        String sessionId = intent.getStringExtra("session_id");
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = intent.getStringExtra("session");
        }
        if (reqId != null && dataJson != null) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    showQuestionNotification(this, nm, reqId, sessionId, dataJson);
                }
            } catch (Throwable t) {
                Log.e(TAG, "showQuestionNotification failed: " + t.getMessage(), t);
            }
        }
    }

    private void dismissCapsule(NotificationManager nm) {
        try {
            stopService(new Intent(this, GlowService.class));
        } catch (Throwable ignored) {}
        try {
            if (nm != null) {
                nm.cancel(10086);
            }
        } catch (Throwable ignored) {}
        try {
            getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignored) {}
    }

    public static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                Constructor<?> ctor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
                Object channel = ctor.newInstance(CHANNEL_ID, CHANNEL_NAME, 4);

                Method setDesc = channelClass.getMethod("setDescription", String.class);
                setDesc.invoke(channel, "DeepSeek Harness Agent 交互提问与选择通知");

                Method enableLights = channelClass.getMethod("enableLights", boolean.class);
                enableLights.invoke(channel, true);

                Method enableVibration = channelClass.getMethod("enableVibration", boolean.class);
                enableVibration.invoke(channel, true);

                try {
                    android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build();
                    android.net.Uri soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
                    Method setSound = channelClass.getMethod("setSound", android.net.Uri.class, android.media.AudioAttributes.class);
                    setSound.invoke(channel, soundUri, audioAttributes);
                } catch (Throwable ignored) {}

                Method createMethod = nm.getClass().getMethod("createNotificationChannel", channelClass);
                createMethod.invoke(nm, channel);
            } catch (Throwable t) {
                Log.w(TAG, "ensureChannel warning: " + t.getMessage());
            }
        }
    }

    public static void showQuestionNotification(Context context, NotificationManager nm, String requestId, String sessionId, String dataJson) {
        try {
            ensureChannel(nm);

            JSONObject root = new JSONObject(dataJson);
            JSONArray questions = root.optJSONArray("questions");
            if (questions == null || questions.length() == 0) return;

            int qCount = questions.length();
            JSONObject firstQ = questions.getJSONObject(0);
            String header = firstQ.optString("header", "DeepSeek Agent 需要您的选择");
            if (qCount > 1) {
                header = header + " (共 " + qCount + " 个问题)";
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < qCount; i++) {
                JSONObject q = questions.getJSONObject(i);
                if (i > 0) sb.append("\n");
                if (qCount > 1) sb.append((i + 1)).append(". ");
                sb.append(q.optString("question", ""));
            }
            String questionText = sb.toString();

            Notification.Builder builder = new Notification.Builder(context);
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Method setChannelMethod = builder.getClass().getMethod("setChannelId", String.class);
                    setChannelMethod.invoke(builder, CHANNEL_ID);
                } catch (Throwable t) {
                    Log.w(TAG, "setChannelId warning: " + t.getMessage());
                }
            }

            builder.setContentTitle("有问题");

            try {
                Bitmap questionIcon = createCyberQuestionBitmap(192);
                if (questionIcon != null && Build.VERSION.SDK_INT >= 23) {
                    Icon icon = Icon.createWithBitmap(questionIcon);
                    builder.setSmallIcon(icon);
                    builder.setLargeIcon(questionIcon);
                    Bundle extras = new Bundle();
                    extras.putParcelable("oplus_small_icon", icon);
                    builder.addExtras(extras);
                } else {
                    builder.setSmallIcon(R.drawable.dsh_whale_icon);
                }
            } catch (Throwable ignored) {
                builder.setSmallIcon(R.drawable.dsh_whale_icon);
            }

            Notification.BigTextStyle bigStyle = new Notification.BigTextStyle();
            bigStyle.setBigContentTitle(header);
            bigStyle.bigText(questionText);
            builder.setStyle(bigStyle);

            // Direct Jump to DemoDialogActivity (Web Console / 灵动坞) with target session
            Intent consoleIntent = new Intent(context, DemoDialogActivity.class);
            consoleIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (sessionId != null && !sessionId.isEmpty()) {
                consoleIntent.putExtra("session_id", sessionId);
            }

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                piFlags |= 0x04000000;
            }
            int reqCode = (sessionId != null && !sessionId.isEmpty()) ? sessionId.hashCode() : 101;
            PendingIntent contentPi = PendingIntent.getActivity(context, reqCode, consoleIntent, piFlags);
            builder.setContentIntent(contentPi);

            builder.setDefaults(Notification.DEFAULT_ALL);
            builder.setPriority(2);
            builder.setAutoCancel(true);
            builder.setShowWhen(true);

            Notification notification = builder.build();
            nm.notify(NOTIFICATION_ID, notification);
            Log.i(TAG, "Question notification posted: requestId=" + requestId + " sessionId=" + sessionId);
        } catch (Throwable t) {
            Log.e(TAG, "showQuestionNotification failed: " + t.getMessage(), t);
        }
    }

    private static Bitmap createCyberQuestionBitmap(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        float center = size / 2.0f;

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFA726); // Vivid Amber Orange
        textPaint.setTextSize(size * 0.82f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = center - (fm.descent + fm.ascent) / 2.0f;
        canvas.drawText("?", center, textY, textPaint);

        return bitmap;
    }
}
