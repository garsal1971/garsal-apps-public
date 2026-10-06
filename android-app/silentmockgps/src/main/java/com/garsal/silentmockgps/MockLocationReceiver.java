package com.garsal.silentmockgps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import android.util.Log;

public class MockLocationReceiver extends BroadcastReceiver {

    private static final String TAG = "SilentMockGPS";
    private static final String ACTION = "com.garsal.silentmockgps.SET_LOCATION";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive called, action=" + intent.getAction());

        if (!ACTION.equals(intent.getAction())) return;

        String latStr = intent.getStringExtra("lat");
        String lonStr = intent.getStringExtra("lon");

        if (latStr == null || lonStr == null) {
            Log.e(TAG, "Missing lat/lon extras");
            setResult(0, "MISSING_EXTRAS", null);
            return;
        }

        try {
            double lat = Double.parseDouble(latStr);
            double lon = Double.parseDouble(lonStr);
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            pushMock(lm, LocationManager.GPS_PROVIDER, lat, lon);
            pushMock(lm, LocationManager.NETWORK_PROVIDER, lat, lon);
            Log.d(TAG, "Location set: " + lat + ", " + lon);
            setResult(1, "OK", null);
        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage());
            setResult(0, e.getMessage(), null);
        }
    }

    private void pushMock(LocationManager lm, String provider, double lat, double lon) {
        try {
            lm.addTestProvider(
                provider,
                false, false, false, false,
                true, true, true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            );
        } catch (IllegalArgumentException e) {
            // provider already registered
        }
        lm.setTestProviderEnabled(provider, true);

        Location loc = new Location(provider);
        loc.setLatitude(lat);
        loc.setLongitude(lon);
        loc.setAltitude(0.0);
        loc.setAccuracy(1.0f);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

        lm.setTestProviderLocation(provider, loc);
    }
}
