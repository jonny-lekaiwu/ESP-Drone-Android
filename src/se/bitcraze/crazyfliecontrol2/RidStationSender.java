package se.bitcraze.crazyfliecontrol2;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import se.bitcraze.crazyflie.lib.crtp.CrtpPacket;
import se.bitcraze.crazyflie.lib.crtp.CrtpPort;

/** Sends the phone's remote-station position and clock to TinyDrone RID. */
final class RidStationSender implements LocationListener {
    private static final String TAG = "RidStationSender";
    private static final int PAYLOAD_LENGTH = 24;
    private static final byte MESSAGE_TYPE = 0x52;
    private static final byte MESSAGE_VERSION = 1;
    private static final int FLAG_POSITION_VALID = 0x01;
    private static final int FLAG_ALTITUDE_VALID = 0x02;
    private static final int FLAG_TIME_VALID = 0x04;
    private static final long SEND_INTERVAL_MS = 1000L;
    private static final long LOCATION_MAX_AGE_MS = 10000L;
    private static final long MIN_VALID_UNIX_MS = 1704067200000L;

    private final Context mContext;
    private final EspUdpDriver mDriver;
    private final LocationManager mLocationManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile Location mLatestLocation;
    private boolean mRunning;

    private final Runnable mStartRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            try {
                registerLocationUpdates();
                mHandler.post(mSendRunnable);
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to start RID station sender", e);
                stop();
            }
        }
    };

    private final Runnable mSendRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            try {
                CrtpPacket.Header header = new CrtpPacket.Header(2, CrtpPort.PLATFORM);
                mDriver.sendPacket(new CrtpPacket(header.getByte(), buildPayload()));
                mHandler.postDelayed(this, SEND_INTERVAL_MS);
            } catch (RuntimeException e) {
                Log.e(TAG, "Disabling RID station sender after runtime failure", e);
                stop();
            }
        }
    };

    RidStationSender(Context context, EspUdpDriver driver) {
        TinyDroneLog.write("RID", "RidStationSender constructor entered");
        mContext = context.getApplicationContext();
        mDriver = driver;
        mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        TinyDroneLog.write("RID", "LocationManager available=" + (mLocationManager != null));
    }

    void start() {
        TinyDroneLog.write("RID", "start() requested");
        if (mRunning) return;
        mRunning = true;
        mHandler.post(mStartRunnable);
    }

    void stop() {
        mRunning = false;
        mHandler.removeCallbacks(mStartRunnable);
        mHandler.removeCallbacks(mSendRunnable);
        if (mLocationManager != null) {
            try {
                mLocationManager.removeUpdates(this);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to remove location updates", e);
            }
        }
        mLatestLocation = null;
    }

    private void registerLocationUpdates() {
        if (mLocationManager == null ||
                ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
                        PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission unavailable; RID will send time only");
            return;
        }

        requestProvider(LocationManager.GPS_PROVIDER);
        requestProvider(LocationManager.NETWORK_PROVIDER);
    }

    private void requestProvider(String provider) {
        try {
            if (!mLocationManager.isProviderEnabled(provider)) return;
            Location lastKnown = mLocationManager.getLastKnownLocation(provider);
            if (isNewer(lastKnown, mLatestLocation)) mLatestLocation = lastKnown;
            mLocationManager.requestLocationUpdates(provider, SEND_INTERVAL_MS, 0.0f, this,
                    Looper.getMainLooper());
        } catch (RuntimeException e) {
            Log.w(TAG, provider + " location provider unavailable", e);
        }
    }

    private byte[] buildPayload() {
        byte[] payload = new byte[PAYLOAD_LENGTH];
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long now = System.currentTimeMillis();
        Location location = mLatestLocation;
        boolean positionValid = isFreshAndValid(location, now);
        boolean altitudeValid = positionValid && location.hasAltitude() &&
                !Double.isNaN(location.getAltitude()) && !Double.isInfinite(location.getAltitude());
        boolean timeValid = now >= MIN_VALID_UNIX_MS && now < (1L << 48);

        int flags = 0;
        if (positionValid) flags |= FLAG_POSITION_VALID;
        if (altitudeValid) flags |= FLAG_ALTITUDE_VALID;
        if (timeValid) flags |= FLAG_TIME_VALID;

        buffer.put(MESSAGE_TYPE);
        buffer.put(MESSAGE_VERSION);
        buffer.put((byte) flags);
        buffer.put((byte) 0);
        buffer.putInt(positionValid ? degreesE7(location.getLongitude()) : 0);
        buffer.putInt(positionValid ? degreesE7(location.getLatitude()) : 0);
        buffer.putInt(altitudeValid ? altitudeCentimetres(location.getAltitude()) : 0);
        putLittleEndian48(payload, 16, timeValid ? now : 0);
        payload[22] = 0; // Phone system-clock accuracy is unknown.
        payload[23] = 0;
        return payload;
    }

    private static boolean isFreshAndValid(Location location, long now) {
        if (location == null || Math.abs(now - location.getTime()) > LOCATION_MAX_AGE_MS) return false;
        double longitude = location.getLongitude();
        double latitude = location.getLatitude();
        return !Double.isNaN(longitude) && !Double.isInfinite(longitude) &&
                !Double.isNaN(latitude) && !Double.isInfinite(latitude) &&
                longitude >= -180.0 && longitude <= 180.0 &&
                latitude >= -90.0 && latitude <= 90.0;
    }

    private static boolean isNewer(Location candidate, Location current) {
        return candidate != null && (current == null || candidate.getTime() > current.getTime());
    }

    private static int degreesE7(double degrees) {
        return (int) Math.round(degrees * 10000000.0);
    }

    private static int altitudeCentimetres(double metres) {
        double centimetres = metres * 100.0;
        if (centimetres > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (centimetres < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) Math.round(centimetres);
    }

    private static void putLittleEndian48(byte[] data, int offset, long value) {
        for (int i = 0; i < 6; i++) data[offset + i] = (byte) (value >> (8 * i));
    }

    @Override
    public void onLocationChanged(Location location) {
        if (isNewer(location, mLatestLocation)) mLatestLocation = location;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}
