/*
 * Copyright 2024 Andrey Novikov
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */

package com.androzic.plugin.locationshare;

import static mobi.maptrek.provider.DataContract.MAPOBJECTS_URI;
import static mobi.maptrek.provider.DataContract.MAPOBJECT_BITMAP_COLUMN;
import static mobi.maptrek.provider.DataContract.MAPOBJECT_COLOR_COLUMN;
import static mobi.maptrek.provider.DataContract.MAPOBJECT_COLUMNS;
import static mobi.maptrek.provider.DataContract.MAPOBJECT_ID_SELECTION;
import static mobi.maptrek.provider.DataContract.MAPOBJECT_LATITUDE_COLUMN;
import static mobi.maptrek.provider.DataContract.MAPOBJECT_LONGITUDE_COLUMN;
import static mobi.maptrek.provider.DataContract.MAPOBJECT_NAME_COLUMN;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Typeface;
import android.location.Location;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.preference.PreferenceManager;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.androzic.data.Situation;
import com.androzic.util.StringFormatter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import mobi.maptrek.location.ILocationRemoteService;

public class SharingService extends Service implements OnSharedPreferenceChangeListener {
    /**
     * GPS status code
     */
    public static final int GPS_OFF = 1;
    /**
     * GPS status code
     */
    public static final int GPS_SEARCHING = 2;
    /**
     * GPS status code
     */
    public static final int GPS_OK = 3;

    private static final String TAG = "LocationSharing";
    private static final int NOTIFICATION_ID = 24164;

    public static final String BROADCAST_SITUATION_CHANGED = "com.androzic.plugin.locationshare.SituationChanged";

    private ILocationRemoteService mapTrekLocationService = null;

    private boolean notifyNewSituation = false;

    private PendingIntent contentIntent;

    ThreadPoolExecutor executorThread = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1));
    private Timer timer;
    RequestQueue requestQueue;

    private ContentProviderClient contentProvider;

    final Location currentLocation = new Location("fake");
    String session;
    String user;
    int updateInterval = 10000; // 10 seconds (default)
    int timeoutInterval = 600000; // 10 minutes (default)
    long timeCorrection = 0;
    double speedFactor = 1;
    String speedAbbr = "m/s";
    boolean isLocated = false;

    private final Map<String, Situation> situations = new HashMap<>();
    final List<Situation> situationList = new ArrayList<>();

    // Drawing resources
    private Paint linePaint;
    private Paint textPaint;
    private int pointWidth;

    @Override
    public void onCreate() {
        super.onCreate();

        // Create notification channel
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("ongoing",
                    getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
        }

        // Prepare notification components
        Intent intent = new Intent(this, SituationList.class);
        contentIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        // Initialize drawing resources
        Resources resources = getResources();
        @ColorInt int tagColor = resources.getColor(R.color.usertag, getTheme());
        linePaint = new Paint();
        linePaint.setAntiAlias(true);
        linePaint.setStrokeWidth(2);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setColor(tagColor);
        textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setStrokeWidth(2);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Align.LEFT);
        textPaint.setTextSize(20);
        textPaint.setTypeface(Typeface.SANS_SERIF);
        textPaint.setColor(tagColor);

        // Initialize preferences
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_session));
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_user));
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_updateinterval));
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_notifications));
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_tagcolor));
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_tagsize));
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_timeout));
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        // Connect to data provider
        contentProvider = getContentResolver().acquireContentProviderClient(MAPOBJECTS_URI);
        if (contentProvider == null) {
            stopSelf();
            return;
        }

        speedFactor = 3.6f;
        speedAbbr = "kmh";
        StringFormatter.speedFactor = 3.6f;
        StringFormatter.speedAbbr = "kmh";

        requestQueue = Volley.newRequestQueue(this);
        startForeground(NOTIFICATION_ID, getNotification(R.mipmap.ic_stat_sharing));
        startTimer();

        // Connect to location service
        connect();

        Log.i(TAG, "Service started");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Disconnect from location service
        disconnect();

        stopForeground(true);
        stopTimer();
        requestQueue.stop();
        requestQueue = null;

        // Clear data
        clearSituations();

        // Release data provider
        if (contentProvider != null)
            contentProvider.release();

        contentIntent = null;

        Log.i(TAG, "Service stopped");
    }

    private void clearSituations() {
        String[] args;
        synchronized (situationList) {
            args = new String[situationList.size()];
            int i = 0;
            for (Situation situation : situationList) {
                args[i] = String.valueOf(situation.id);
                i++;
            }
        }
        synchronized (situations) {
            synchronized (situationList) {
                situationList.clear();
            }
            situations.clear();
        }
        // Remove situations from map
        try {
            if (contentProvider != null)
                contentProvider.delete(MAPOBJECTS_URI, MAPOBJECT_ID_SELECTION, args);
        } catch (RemoteException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        sendBroadcast(new Intent(BROADCAST_SITUATION_CHANGED));
    }

    protected void updateSituations() {
        executorThread.getQueue().poll();
        executorThread.execute(() -> {
            Log.d(TAG, "updateSituations");
            try {
                String query;
                synchronized (currentLocation) {
                    query = "session=" + URLEncoder.encode(session, "UTF-8");
                    if (isLocated) {
                        query = query
                                + ";user=" + URLEncoder.encode(user, "UTF-8")
                                + ";lat=" + currentLocation.getLatitude()
                                + ";lon=" + currentLocation.getLongitude()
                                + ";track=" + currentLocation.getBearing()
                                + ";speed=" + currentLocation.getSpeed()
                                + ";ftime=" + currentLocation.getTime();
                    }
                }
                String url = "https://trekarta.info/sharing/?" + query;

                JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                        Request.Method.GET,
                        url,
                        null,
                        response -> {
                            updateNotification(R.mipmap.ic_stat_sharing_in);
                            try {
                                JSONArray entries = response.getJSONArray("users");
                                for (int i = 0; i < entries.length(); i++) {
                                    JSONObject situation = entries.getJSONObject(i);
                                    String name = situation.getString("user");
                                    if (name.equals(user))
                                        continue;
                                    synchronized (situations) {
                                        Situation s = situations.get(name);
                                        if (s == null) {
                                            s = new Situation(name);
                                            situations.put(name, s);
                                            synchronized (situationList) {
                                                situationList.add(s);
                                            }
                                        }
                                        s.latitude = situation.getDouble("lat");
                                        s.longitude = situation.getDouble("lon");
                                        s.speed = situation.getDouble("speed");
                                        s.track = situation.getDouble("track");
                                        s.time = situation.getLong("ftime");
                                    }
                                }
                                finishSituationsUpdate(true);
                            } catch (JSONException e) {
                                throw new RuntimeException(e);
                            }
                        },
                        error -> {
                            // TODO: Handle error
                            finishSituationsUpdate(false);
                        }
                );

                updateNotification(R.mipmap.ic_stat_sharing_out);
                requestQueue.add(jsonObjectRequest);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        });
    }

    private void finishSituationsUpdate(boolean updated) {
        Log.d(TAG, "finishSituations");
        synchronized (situations) {
            long curTime = System.currentTimeMillis() - timeCorrection;
            for (Situation situation : situations.values()) {
                situation.silent = situation.time + timeoutInterval < curTime;
            }
        }
        if (updated)
            sendBroadcast(new Intent(BROADCAST_SITUATION_CHANGED));

        try {
            sendMapObjects();
        } catch (RemoteException e) {
            // TODO Auto-generated catch block
            Log.e(TAG, "Failed to send map objects", e);
        }

        updateNotification(R.mipmap.ic_stat_sharing);
    }

    protected void sendNewSituationNotification(Situation situation) {
        Intent i = new Intent("com.androzic.COORDINATES_RECEIVED");
        i.putExtra("title", session);
        i.putExtra("sender", situation.name);
        i.putExtra("origin", getApplicationContext().getPackageName());
        i.putExtra("lat", situation.latitude);
        i.putExtra("lon", situation.longitude);

        String msg = getString(R.string.notif_newsession, situation.name);

        Notification.Builder builder = new Notification.Builder(this);
        builder.setWhen(situation.time);
        builder.setSmallIcon(R.mipmap.ic_stat_sharing);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, (int) situation.id, i, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(pendingIntent);
        builder.setContentTitle(getText(R.string.app_name));
        builder.setContentText(msg);
        builder.setTicker(msg);
        builder.setGroup("maptrek");
        builder.setAutoCancel(true);
        builder.setDefaults(Notification.DEFAULT_SOUND);
        //builder.setCategory(Notification.CATEGORY_SOCIAL);
        builder.setPriority(Notification.PRIORITY_LOW);
        //builder.setVisibility(Notification.VISIBILITY_PRIVATE);
        //builder.setColor(getResources().getColor(R.color.theme_accent_color));
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify((int) situation.id, builder.build());
    }

    private void sendMapObjects() throws RemoteException {
        synchronized (situationList) {
            for (Situation situation : situationList) {
                byte[] bitmap = getSituationBitmap(situation);
                ContentValues values = new ContentValues();
                // Name is not required if bitmap is used, but we need it for navigation service.
                // See SituationList for navigation initiation code.
                values.put(MAPOBJECT_COLUMNS[MAPOBJECT_NAME_COLUMN], situation.name);
                values.put(MAPOBJECT_COLUMNS[MAPOBJECT_LATITUDE_COLUMN], situation.latitude);
                values.put(MAPOBJECT_COLUMNS[MAPOBJECT_LONGITUDE_COLUMN], situation.longitude);
                values.put(MAPOBJECT_COLUMNS[MAPOBJECT_BITMAP_COLUMN], bitmap);
                values.put(MAPOBJECT_COLUMNS[MAPOBJECT_COLOR_COLUMN], linePaint.getColor());
                // If this is a new object insert it
                if (situation.id == 0) {
                    Uri uri = contentProvider.insert(MAPOBJECTS_URI, values);
                    situation.id = ContentUris.parseId(uri);
                    if (notifyNewSituation)
                        sendNewSituationNotification(situation);
                }
                // Otherwise update it
                else {
                    Uri uri = ContentUris.withAppendedId(MAPOBJECTS_URI, situation.id);
                    contentProvider.update(uri, values, null, null);
                }
            }
        }
    }

    private byte[] getSituationBitmap(Situation situation) {
        Bitmap b;
        b = Bitmap.createBitmap(pointWidth * 44, pointWidth * 44, Config.ARGB_8888);
        Canvas bc = new Canvas(b);
        linePaint.setAlpha(situation.silent ? 128 : 255);
        bc.translate(pointWidth * 22, pointWidth * 22);
        bc.drawCircle(0, 0, pointWidth, linePaint);
        bc.drawCircle(0, 0, pointWidth * 6, linePaint);
        bc.save();
        bc.rotate((float) situation.track, 0, 0);
        // https://graphsketch.com/?eqn1_color=1&eqn1_eqn=log(x%2F3.6*2%2B1)&eqn2_color=2&eqn2_eqn=&eqn3_color=3&eqn3_eqn=&eqn4_color=4&eqn4_eqn=&eqn5_color=5&eqn5_eqn=&eqn6_color=6&eqn6_eqn=&x_min=-20&x_max=300&y_min=-2&y_max=3&x_tick=10&y_tick=1&x_label_freq=5&y_label_freq=1&do_grid=0&do_grid=1&bold_labeled_lines=0&bold_labeled_lines=1&line_width=4&image_w=850&image_h=525
        int h = (int) Math.round(Math.log10(situation.speed * 2 + 1) * pointWidth * 6);
        bc.drawLine(0, -6 * pointWidth, 0, -6 * pointWidth - h, linePaint);
        bc.restore();

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, stream);
        return stream.toByteArray();
    }

    // This is not used in code, but included to demonstrate, how to remove
    // single map object from Androzic map.
    @SuppressWarnings("unused")
    private void removeMapObject(String name) {
        Situation situation = situations.get(name);
        if (situation == null)
            return;
        synchronized (situationList) {
            situationList.remove(situation);
        }
        synchronized (situations) {
            situations.remove(name);
        }
        Uri uri = ContentUris.withAppendedId(MAPOBJECTS_URI, situation.id);
        try {
            contentProvider.delete(uri, null, null);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void connect() {
        Intent intent = new Intent("mobi.maptrek.location");
        ResolveInfo ri = getPackageManager().resolveService(intent, 0);
        // This generally can not happen because plugin can be run only from parent application
        if (ri == null)
            return;
        ServiceInfo service = ri.serviceInfo;
        intent.setComponent(new ComponentName(service.applicationInfo.packageName, service.name));
        bindService(intent, mLocationConnection, BIND_AUTO_CREATE);
    }

    private void disconnect() {
        if (mapTrekLocationService != null) {
            try {
                mapTrekLocationService.unregisterCallback(mMapTrekLocationCallback);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            unbindService(mLocationConnection);
            mapTrekLocationService = null;
        }
    }

    private void startTimer() {
        Log.d(TAG, "startTimer");
        if (timer != null)
            stopTimer();

        timer = new Timer();
        TimerTask updateTask = new UpdateSituationsTask();
        timer.scheduleAtFixedRate(updateTask, 0, updateInterval);
    }

    private void stopTimer() {
        if (timer != null)
            timer.cancel();
        timer = null;
    }

    private Notification getNotification(int icon) {
        Notification.Builder builder = new Notification.Builder(this);
        if (Build.VERSION.SDK_INT > 25)
            builder.setChannelId("ongoing");
        builder.setWhen(0);
        builder.setSmallIcon(icon);
        builder.setContentIntent(contentIntent);
        builder.setContentTitle(getText(R.string.pref_sharing_title));
        builder.setContentText(getText(R.string.notif_sharing));
        builder.setGroup("maptrek");
        if (Build.VERSION.SDK_INT >= 28)
            builder.setCategory(Notification.CATEGORY_NAVIGATION);
        else
            builder.setCategory(Notification.CATEGORY_PROGRESS);
        builder.setPriority(Notification.PRIORITY_LOW);
        builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        //builder.setColor(getResources().getColor(R.color.theme_accent_color));
        builder.setOngoing(true);
        return builder.build();
    }

    private void updateNotification(int icon) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, getNotification(icon));
    }

    private void readAndrozicPreferences() {
        // Resolve content provider
        ContentProviderClient client = getContentResolver().acquireContentProviderClient(com.androzic.provider.PreferencesContract.PREFERENCES_URI);

        // Setup preference items we want to read (order is important - it
        // should correlate with the read order later in code)
        int[] fields = new int[]{com.androzic.provider.PreferencesContract.SPEED_FACTOR, com.androzic.provider.PreferencesContract.SPEED_ABBREVIATION, com.androzic.provider.PreferencesContract.DISTANCE_FACTOR, com.androzic.provider.PreferencesContract.DISTANCE_ABBREVIATION,
                com.androzic.provider.PreferencesContract.DISTANCE_SHORT_FACTOR, com.androzic.provider.PreferencesContract.DISTANCE_SHORT_ABBREVIATION};
        // Convert them to strings
        String[] args = new String[fields.length];
        for (int i = 0; i < fields.length; i++) {
            args[i] = String.valueOf(fields[i]);
        }
        try {
            // Request data from preferences content provider
            Cursor cursor = client.query(com.androzic.provider.PreferencesContract.PREFERENCES_URI, com.androzic.provider.PreferencesContract.DATA_COLUMNS, com.androzic.provider.PreferencesContract.DATA_SELECTION, args, null);
            cursor.moveToFirst();
            speedFactor = cursor.getDouble(com.androzic.provider.PreferencesContract.DATA_COLUMN);
            cursor.moveToNext();
            speedAbbr = cursor.getString(com.androzic.provider.PreferencesContract.DATA_COLUMN);
            cursor.moveToNext();
            StringFormatter.distanceFactor = cursor.getDouble(com.androzic.provider.PreferencesContract.DATA_COLUMN);
            cursor.moveToNext();
            StringFormatter.distanceAbbr = cursor.getString(com.androzic.provider.PreferencesContract.DATA_COLUMN);
            cursor.moveToNext();
            StringFormatter.distanceShortFactor = cursor.getDouble(com.androzic.provider.PreferencesContract.DATA_COLUMN);
            cursor.moveToNext();
            StringFormatter.distanceShortAbbr = cursor.getString(com.androzic.provider.PreferencesContract.DATA_COLUMN);
            cursor.close();
        } catch (RemoteException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        // Notify that the binding is not required anymore
        client.release();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        String oldsession = session;
        String olduser = user;

        if (getString(R.string.pref_sharing_session).equals(key)) {
            session = sharedPreferences.getString(key, "");
        } else if (getString(R.string.pref_sharing_user).equals(key)) {
            user = sharedPreferences.getString(key, "");
        } else if (getString(R.string.pref_sharing_updateinterval).equals(key)) {
            updateInterval = sharedPreferences.getInt(key, getResources().getInteger(R.integer.def_sharing_updateinterval)) * 1000;
            if (timer != null) {
                stopTimer();
                startTimer();
            }
        } else if (getString(R.string.pref_sharing_notifications).equals(key)) {
            notifyNewSituation = sharedPreferences.getBoolean(key, getResources().getBoolean(R.bool.def_notifications));
        } else if (getString(R.string.pref_sharing_tagcolor).equals(key)) {
            @ColorInt int color = getResources().getColor(R.color.usertag, getTheme());
            linePaint.setColor(sharedPreferences.getInt(key, color));
            textPaint.setColor(sharedPreferences.getInt(key, color));
        } else if (getString(R.string.pref_sharing_tagsize).equals(key)) {
            int width = sharedPreferences.getInt(key, getResources().getInteger(R.integer.def_sharing_tagsize));
            linePaint.setStrokeWidth(width * 2);
            pointWidth = width;
        } else if (getString(R.string.pref_sharing_timeout).equals(key)) {
            timeoutInterval = sharedPreferences.getInt(key, getResources().getInteger(R.integer.def_sharing_timeout)) * 60000;
        }

        if (!session.equals(oldsession) || !user.equals(olduser)) {
            clearSituations();
        }
        if ((session != null && session.trim().equals("")) || (user != null && user.trim().equals("")))
            stopSelf();
    }

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        SharingService getService() {
            return SharingService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private final ServiceConnection mLocationConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.e(TAG, "onServiceConnected(" + className.getPackageName() + ")");
            mapTrekLocationService = ILocationRemoteService.Stub.asInterface(service);
            try {
                mapTrekLocationService.registerCallback(mMapTrekLocationCallback);
                Log.d(TAG, "Location service connected");
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "Location service disconnected");
            mapTrekLocationService = null;
            isLocated = false;
        }
    };

    private final mobi.maptrek.location.ILocationCallback mMapTrekLocationCallback = new mobi.maptrek.location.ILocationCallback.Stub() {
        @Override
        public void onLocationChanged() throws RemoteException {
            Location location = mapTrekLocationService.getLocation();
            synchronized (currentLocation) {
                currentLocation.set(location);
                timeCorrection = System.currentTimeMillis() - currentLocation.getTime();
            }
        }

        @Override
        public void onGpsStatusChanged() throws RemoteException {
            //TODO Send lost location status
            isLocated = mapTrekLocationService.getStatus() == GPS_OK;
        }
    };

    class UpdateSituationsTask extends TimerTask {
        public void run() {
            updateSituations();
        }
    }
}
