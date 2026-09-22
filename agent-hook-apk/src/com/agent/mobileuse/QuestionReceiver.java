package com.agent.mobileuse;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

public class QuestionReceiver extends BroadcastReceiver {
    private static final String TAG = "AgentQuestionReceiver";
    public static final String ACTION_QUESTION = "com.agent.mobileuse.ACTION_QUESTION";
    public static final String ACTION_QUESTION_CANCEL = "com.agent.mobileuse.ACTION_QUESTION_CANCEL";
    public static final String ACTION_QUESTION_DIRECT_ANSWER = "com.agent.mobileuse.ACTION_QUESTION_DIRECT_ANSWER";
    public static final String ACTION_DISMISS_CARD = "com.agent.mobileuse.ACTION_DISMISS_CARD";

    private static final String CHANNEL_ID = "agent_question_channel";
    private static final String CHANNEL_NAME = "Agent 交互确认";
    public static final int NOTIFICATION_ID = 20086;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "onReceive action: " + action);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (ACTION_QUESTION_CANCEL.equals(action)) {
            Log.i(TAG, "Canceling question notification & dismissing card");
            nm.cancel(NOTIFICATION_ID);
            // Dismiss card activity if open
            Intent dismissIntent = new Intent(ACTION_DISMISS_CARD);
            dismissIntent.setPackage(context.getPackageName());
            context.sendBroadcast(dismissIntent);
            return;
        }

        if (ACTION_QUESTION_DIRECT_ANSWER.equals(action)) {
            String requestId = intent.getStringExtra("request_id");
            String questionId = intent.getStringExtra("question_id");
            String selected = intent.getStringExtra("selected");
            nm.cancel(NOTIFICATION_ID);
            if (requestId != null && questionId != null && selected != null) {
                postDirectAnswer(requestId, questionId, selected);
            }
            return;
        }

        if (ACTION_QUESTION.equals(action)) {
            String requestId = intent.getStringExtra("request_id");
            String dataJson = intent.getStringExtra("data");
            if (dataJson == null || dataJson.isEmpty()) {
                Log.w(TAG, "Empty question data");
                return;
            }
            showQuestionNotification(context, nm, requestId, dataJson);
        }
    }

    public static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                Constructor<?> ctor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
                // IMPORTANCE_HIGH = 4 (Heads-up banner notification)
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
                Log.d(TAG, "NotificationChannel created/ensured: " + CHANNEL_ID);
            } catch (Throwable t) {
                Log.w(TAG, "ensureChannel reflection warning: " + t.getMessage());
            }
        }
    }

    public static void showQuestionNotification(Context context, NotificationManager nm, String requestId, String dataJson) {
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

            builder.setContentTitle(header);
            builder.setContentText(questionText);
            builder.setSubText("点击处理交互提问");
            builder.setSmallIcon(R.drawable.dsh_whale_icon);

            // LargeIcon: Pure white circle background with a crisp black question mark
            try {
                Bitmap questionIcon = createWhiteBlackQuestionBitmap(192);
                if (questionIcon != null) {
                    builder.setLargeIcon(questionIcon);
                }
            } catch (Throwable ignored) {}

            // BigText style
            Notification.BigTextStyle bigStyle = new Notification.BigTextStyle();
            bigStyle.setBigContentTitle(header);
            bigStyle.bigText(questionText);
            builder.setStyle(bigStyle);

            // Intent to launch QuestionActivity (BottomSheet Card)
            Intent cardIntent = new Intent(context, QuestionActivity.class);
            cardIntent.putExtra("request_id", requestId);
            cardIntent.putExtra("data", dataJson);
            cardIntent.putExtra("only_notify", false);
            cardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                piFlags |= 0x04000000; // FLAG_IMMUTABLE
            }
            PendingIntent contentPi = PendingIntent.getActivity(context, 101, cardIntent, piFlags);
            builder.setContentIntent(contentPi);

            builder.setDefaults(Notification.DEFAULT_ALL);
            builder.setPriority(2); // PRIORITY_MAX = 2
            builder.setAutoCancel(true);
            builder.setShowWhen(true);

            Notification notification = builder.build();
            nm.notify(NOTIFICATION_ID, notification);
            Log.i(TAG, "Question notification posted: requestId=" + requestId);
        } catch (Throwable t) {
            Log.e(TAG, "showQuestionNotification failed: " + t.getMessage(), t);
        }
    }

    /**
     * Create a crisp vector-drawn LargeIcon: Pure white circle background with a bold black question mark (?).
     */
    private static Bitmap createWhiteBlackQuestionBitmap(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        float center = size / 2.0f;
        float radius = center - 4.0f;

        // 1. Draw smooth white circular background
        android.graphics.Paint bgPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0xFFFFFFFF);
        bgPaint.setStyle(android.graphics.Paint.Style.FILL);
        canvas.drawCircle(center, center, radius, bgPaint);

        // 2. Draw bold black question mark (?)
        android.graphics.Paint textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFF1E2024); // Deep Black / Charcoal
        textPaint.setTextSize(size * 0.65f);
        textPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);

        // Vertically center alignment
        android.graphics.Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = center - (fm.descent + fm.ascent) / 2.0f;
        canvas.drawText("?", center, textY, textPaint);

        return bitmap;
    }

    public static void postDirectAnswer(final String requestId, final String questionId, final String selected) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("http://127.0.0.1:3070/api/answer");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);

                    JSONObject payload = new JSONObject();
                    payload.put("request_id", requestId);
                    JSONArray answers = new JSONArray();
                    JSONObject ansItem = new JSONObject();
                    ansItem.put("id", questionId);
                    JSONArray selArr = new JSONArray();
                    selArr.put(selected);
                    ansItem.put("selected", selArr);
                    answers.put(ansItem);
                    payload.put("answers", answers);

                    byte[] bytes = payload.toString().getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(bytes.length);
                    OutputStream os = conn.getOutputStream();
                    os.write(bytes);
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    Log.i(TAG, "postDirectAnswer HTTP response: " + code);
                } catch (Throwable t) {
                    Log.e(TAG, "postDirectAnswer failed: " + t.getMessage(), t);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }
}
