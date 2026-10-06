package com.garsal.silentmockgps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class MockLocationService extends Service {

    private static final String TAG = "SilentMockGPS";
    private static final String CHANNEL_ID = "silentmockgps";
    private static final int NOTIF_ID = 1;

    private MockLocationReceiver receiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());

        receiver = new MockLocationReceiver();
        IntentFilter filter = new IntentFilter("com.garsal.silentmockgps.SET_LOCATION");
        registerReceiver(receiver, filter);
        Log.d(TAG, "Service started, receiver registered dynamically");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (receiver != null) {
            unregisterReceiver(receiver);
            Log.d(TAG, "Receiver unregistered");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "SilentMockGPS", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mock GPS location service");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SilentMockGPS")
                .setContentText("In ascolto per broadcast ADB")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
        } else {
            return new Notification.Builder(this)
                .setContentTitle("SilentMockGPS")
                .setContentText("In ascolto per broadcast ADB")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
        }
    }
}
