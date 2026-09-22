package com.agent.mobileuse;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Lightweight background notification service.
 * Direct am start-service bypasses ColorOS broadcast freeze, does not disrupt ActivityStack,
 * and ensures 100% reliable instant heads-up notification delivery while keeping DemoDialogActivity alive in background.
 */
public class NotifyService extends Service {
    private static final String TAG = "AgentNotifyService";

    public static final String ACTION_NOTIFY_COMPLETED = "com.agent.mobileuse.ACTION_NOTIFY_COMPLETED";
    public static final String ACTION_NOTIFY_QUESTION = "com.agent.mobileuse.ACTION_NOTIFY_QUESTION";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand action=" + action);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            Log.e(TAG, "NotificationManager is null");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        try {
            if (ACTION_NOTIFY_QUESTION.equals(action) || intent.hasExtra("data")) {
                String requestId = intent.getStringExtra("request_id");
                String dataJson = intent.getStringExtra("data");
                boolean isForeground = intent.getBooleanExtra("is_foreground", false);

                if (requestId != null && dataJson != null) {
                    QuestionReceiver.showQuestionNotification(this, nm, requestId, dataJson);
                    Log.i(TAG, "Posted question notification for requestId=" + requestId);

                    // If in foreground mode, seamlessly and directly pop up QuestionActivity card!
                    if (isForeground) {
                        try {
                            Intent cardIntent = new Intent(this, QuestionActivity.class);
                            cardIntent.putExtra("request_id", requestId);
                            cardIntent.putExtra("data", dataJson);
                            cardIntent.putExtra("only_notify", false);
                            cardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(cardIntent);
                            Log.i(TAG, "Foreground mode detected: QuestionActivity directly launched!");
                        } catch (Throwable actErr) {
                            Log.e(TAG, "Failed to directly launch QuestionActivity: " + actErr.getMessage(), actErr);
                        }
                    }
                }
            } else {
                // Default: Task completion notification
                String title = intent.getStringExtra("title");
                String subtext = intent.getStringExtra("subtext");
                String content = intent.getStringExtra("content");
                String tag = intent.getStringExtra("tag");
                if (tag == null || tag.isEmpty()) tag = NotifyReceiver.DEFAULT_TAG;
                int id = intent.getIntExtra("id", NotifyReceiver.DEFAULT_ID);
                int total = intent.getIntExtra("total", 0);
                int completed = intent.getIntExtra("completed", 0);

                NotifyReceiver.postCompletedNotification(this, nm, tag, id, title, subtext, content, total, completed);
                Log.i(TAG, "Posted completed notification: title=" + title + " subtext=" + subtext);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error handling notification in NotifyService: " + t.getMessage(), t);
        }

        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
