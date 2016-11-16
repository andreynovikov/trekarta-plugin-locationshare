package com.androzic.plugin.locationshare;

import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v7.view.menu.MenuPopupHelper;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.TextView;

import com.androzic.data.Situation;
import com.androzic.util.Geo;
import com.androzic.util.StringFormatter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class SituationList extends Activity implements OnSharedPreferenceChangeListener, OnItemClickListener, MenuItem.OnMenuItemClickListener, OnCheckedChangeListener, PopupMenu.OnMenuItemClickListener, PopupMenu.OnDismissListener {
    private static final String TAG = "SituationList";
    private static final int PERMISSIONS_REQUEST = 1001;

    private static String[] MAPTREK_PERMISSIONS = {"mobi.maptrek.permission.RECEIVE_LOCATION",
            "mobi.maptrek.permission.WRITE_MAP_DATA"};

    private static String[] ANDROZIC_PERMISSIONS = {"com.androzic.permission.RECEIVE_LOCATION",
            "com.androzic.permission.NAVIGATION", "com.androzic.permission.READ_PREFERENCES",
            "com.androzic.permission.READ_MAP_DATA", "com.androzic.permission.WRITE_MAP_DATA"};

    private ListView listView;
    private TextView emptyView;
    private SituationListAdapter adapter;
    public SharingService sharingService = null;

    private Timer timer;
    // private int timeoutInterval = 600; // 10 minutes (default)

    private Switch enableSwitch;

    private int selectedPosition = -1;
    private Drawable selectedBackground;
    private int lastSelectedPosition;
    private int accentColor;
    private boolean mMapTrek;
    private String[] mPermissions;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.act_userlist);

        ActionBar actionBar = getActionBar();
        if (actionBar != null)
            actionBar.setDisplayHomeAsUpEnabled(true);

        listView = (ListView) findViewById(android.R.id.list);
        emptyView = (TextView) findViewById(android.R.id.empty);
        listView.setEmptyView(emptyView);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        accentColor = getResources().getColor(R.color.theme_accent_color);

        adapter = new SituationListAdapter(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(this);

        mMapTrek = sharedPreferences.getBoolean("maptrek", false);
        mPermissions = mMapTrek ? MAPTREK_PERMISSIONS : ANDROZIC_PERMISSIONS;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isServiceRunning()) {
            connect();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        disconnect();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.preferences, menu);

        // Get widget's instance
        menu.findItem(R.id.action_enable).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        enableSwitch = (Switch) menu.findItem(R.id.action_enable).getActionView();
        enableSwitch.setOnCheckedChangeListener(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            menu.findItem(R.id.action_settings).getIcon().setTint(getResources().getColor(android.R.color.primary_text_dark));
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        onSharedPreferenceChanged(sharedPreferences, null);
        if (isServiceRunning()) {
            enableSwitch.setChecked(true);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                startActivity(new Intent(this, Preferences.class));
                return true;
            case android.R.id.home:
                finish();
                return true;
        }
        return false;
    }

    @Override
    public void onItemClick(AdapterView<?> l, View v, int position, long id) {
        v.setTag("selected");
        selectedPosition = position;
        selectedBackground = v.getBackground();
        lastSelectedPosition = position;
        v.setBackgroundColor(accentColor);
        PopupMenu popupMenu = new PopupMenu(this, v.findViewById(R.id.name));
        popupMenu.inflate(R.menu.situation_popup);
        popupMenu.setOnMenuItemClickListener(this);
        popupMenu.setOnDismissListener(this);
        try {
            Field mFieldPopup = popupMenu.getClass().getDeclaredField("mPopup");
            mFieldPopup.setAccessible(true);
            MenuPopupHelper mPopup = (MenuPopupHelper) mFieldPopup.get(popupMenu);
            mPopup.setForceShowIcon(true);
        } catch (Exception ignore) {
        }
        popupMenu.show();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked && !isServiceRunning()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean notGranted = false;
                for (String permission : mPermissions)
                    notGranted |= checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED;
                if (notGranted) {
                    requestPermission();
                } else {
                    start();
                }
            } else {
                start();
            }
        } else if (!isChecked && isServiceRunning()) {
            disconnect();
            stopService(new Intent(this, SharingService.class));
            getActionBar().setSubtitle("");
            emptyView.setText(R.string.msg_needs_enable);
            adapter.notifyDataSetChanged();
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void requestPermission() {
        boolean shouldShow = false;
        for (String permission : mPermissions)
            shouldShow |= shouldShowRequestPermissionRationale(permission);
        if (shouldShow) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.msgPermissionsRationale))
                    .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            requestPermissions(mPermissions, PERMISSIONS_REQUEST);
                        }
                    })
                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .create()
                    .show();
        } else {
            requestPermissions(mPermissions, PERMISSIONS_REQUEST);
        }
    }

    /**
     * Callback received when a permissions request has been completed.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        boolean granted = true;
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.length < 1)
                granted = false;
            // Verify that each required permission has been granted, otherwise return false.
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                }
            }
            if (granted) {
                start();
            } else {
                enableSwitch.setChecked(false);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void start() {
        emptyView.setText(R.string.msg_no_users);
        startService(new Intent(this, SharingService.class));
        connect();
    }

    private void connect() {
        bindService(new Intent(this, SharingService.class), sharingConnection, 0);
        timer = new Timer();
        TimerTask updateTask = new UpdateTask();
        timer.scheduleAtFixedRate(updateTask, 1000, 1000);
    }

    private void disconnect() {
        if (sharingService != null) {
            unregisterReceiver(sharingReceiver);
            unbindService(sharingConnection);
            sharingService = null;
        }
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if ("com.androzic.plugin.locationshare.SharingService".equals(service.service.getClassName()) && service.pid > 0)
                return true;
        }
        return false;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        Situation situation = adapter.getItem(lastSelectedPosition);

        switch (item.getItemId()) {
            case R.id.action_view:
                Intent i = new Intent(mMapTrek ? "mobi.maptrek.action.CENTER_ON_COORDINATES" : "com.androzic.CENTER_ON_COORDINATES");
                i.putExtra("lat", situation.latitude);
                i.putExtra("lon", situation.longitude);
                if (mMapTrek) {
                    startActivity(i);
                } else {
                    sendBroadcast(i);
                }
                finish();
                return true;
            case R.id.action_navigate:
                Intent intent = new Intent(mMapTrek ? "mobi.maptrek.action.NAVIGATE_TO_OBJECT" : "com.androzic.navigateMapObjectWithId");
                intent.putExtra("id", situation.id);
                if (mMapTrek) {
                    startActivity(intent);
                } else {
                    intent = getExplicitIntent(intent);
                    if (intent != null)
                        startService(intent);
                }
                finish();
                return true;
        }
        return false;
    }

    @Override
    public void onDismiss(PopupMenu popupMenu) {
        selectedPosition = -1;
        if (listView != null) {
            View v = listView.findViewWithTag("selected");
            if (v != null) {
                v.setBackground(selectedBackground);
                v.setTag(null);
            }
        }
    }

    private ServiceConnection sharingConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            sharingService = ((SharingService.LocalBinder) service).getService();
            registerReceiver(sharingReceiver, new IntentFilter(SharingService.BROADCAST_SITUATION_CHANGED));
            runOnUiThread(new Runnable() {
                public void run() {
                    getActionBar().setSubtitle(sharingService.user + " \u2208 " + sharingService.session);
                    adapter.notifyDataSetChanged();
                }
            });
            Log.d(TAG, "Sharing service connected");
        }

        public void onServiceDisconnected(ComponentName className) {
            sharingService = null;
            getActionBar().setSubtitle("");
            adapter.notifyDataSetChanged();
            Log.d(TAG, "Sharing service disconnected");
        }
    };

    private BroadcastReceiver sharingReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(SharingService.BROADCAST_SITUATION_CHANGED)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }
    };

    public class SituationListAdapter extends BaseAdapter {
        private LayoutInflater mInflater;
        private int mItemLayout;

        SituationListAdapter(Context context) {
            mItemLayout = R.layout.situation_list_item;
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        public Situation getItem(int position) {
            if (sharingService != null) {
                synchronized (sharingService.situationList) {
                    return sharingService.situationList.get(position);
                }
            }
            return null;
        }

        @Override
        public long getItemId(int position) {
            if (sharingService != null) {
                synchronized (sharingService.situationList) {
                    return sharingService.situationList.get(position).id;
                }
            }
            return Integer.MIN_VALUE + position;
        }

        @Override
        public int getCount() {
            if (sharingService != null) {
                synchronized (sharingService.situationList) {
                    return sharingService.situationList.size();
                }
            }
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v;
            if (convertView == null) {
                v = mInflater.inflate(mItemLayout, parent, false);
            } else {
                // v = convertView;
                // TODO Have to utilize view
                v = mInflater.inflate(mItemLayout, parent, false);
            }
            if (position == selectedPosition)
                v.setBackgroundColor(accentColor);

            Situation stn = getItem(position);
            if (stn != null && sharingService != null) {
                TextView text = (TextView) v.findViewById(R.id.name);
                if (text != null) {
                    text.setText(stn.name);
                }
                String distance = "";
                synchronized (sharingService.currentLocation) {
                    if (!"fake".equals(sharingService.currentLocation.getProvider())) {
                        double dist = Geo.distance(stn.latitude, stn.longitude, sharingService.currentLocation.getLatitude(), sharingService.currentLocation.getLongitude());
                        distance = StringFormatter.distanceH(dist);
                    }
                }
                text = (TextView) v.findViewById(R.id.distance);
                if (text != null) {
                    text.setText(distance);
                }
                //FIXME Should initialize StringFormatter for angles
                String track = StringFormatter.angleH(stn.track);
                text = (TextView) v.findViewById(R.id.track);
                if (text != null) {
                    text.setText(track);
                }
                String speed = String.valueOf(Math.round(stn.speed * sharingService.speedFactor));
                text = (TextView) v.findViewById(R.id.speed);
                if (text != null) {
                    text.setText(speed + " " + sharingService.speedAbbr);
                }
                long now = System.currentTimeMillis();
                long d = stn.time - sharingService.timeCorrection;
                String delay = (String) DateUtils.getRelativeTimeSpanString(d, now, DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
                text = (TextView) v.findViewById(R.id.delay);
                if (text != null) {
                    text.setText(delay);
                }
                if (stn.silent) {
                    text = (TextView) v.findViewById(R.id.name);
                    text.setTextColor(text.getTextColors().withAlpha(128));
                    text = (TextView) v.findViewById(R.id.distance);
                    text.setTextColor(text.getTextColors().withAlpha(128));
                    text = (TextView) v.findViewById(R.id.track);
                    text.setTextColor(text.getTextColors().withAlpha(128));
                    text = (TextView) v.findViewById(R.id.speed);
                    text.setTextColor(text.getTextColors().withAlpha(128));
                    text = (TextView) v.findViewById(R.id.delay);
                    text.setTextColor(text.getTextColors().withAlpha(128));
                }
            }
            return v;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        String session = sharedPreferences.getString(getString(R.string.pref_sharing_session), "");
        String user = sharedPreferences.getString(getString(R.string.pref_sharing_user), "");
        if (!session.trim().equals("") && !user.trim().equals("")) {
            enableSwitch.setEnabled(true);
            emptyView.setText(R.string.msg_needs_enable);
        } else {
            enableSwitch.setEnabled(false);
            emptyView.setText(R.string.msg_needs_setup);
        }

        if (adapter != null)
            adapter.notifyDataSetChanged();
    }

    public Intent getExplicitIntent(Intent implicitIntent) {
        //Retrieve all services that can match the given intent
        PackageManager pm = getPackageManager();
        List<ResolveInfo> resolveInfo = pm.queryIntentServices(implicitIntent, 0);

        //Make sure only one match was found
        if (resolveInfo == null || resolveInfo.size() != 1) {
            return null;
        }

        //Get component info and create ComponentName
        ResolveInfo serviceInfo = resolveInfo.get(0);
        String packageName = serviceInfo.serviceInfo.packageName;
        String className = serviceInfo.serviceInfo.name;
        ComponentName component = new ComponentName(packageName, className);

        //Create a new intent. Use the old one for extras and such reuse
        Intent explicitIntent = new Intent(implicitIntent);

        //Set the component to be explicit
        explicitIntent.setComponent(component);

        return explicitIntent;
    }

    class UpdateTask extends TimerTask {
        public void run() {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (adapter != null && selectedPosition == -1)
                        adapter.notifyDataSetChanged();
                }
            });
        }
    }
}
