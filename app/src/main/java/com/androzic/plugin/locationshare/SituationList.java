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

import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.IBinder;
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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.androzic.data.Situation;
import com.androzic.plugin.locationshare.databinding.ActUserlistBinding;
import com.androzic.util.Geo;
import com.androzic.util.StringFormatter;

import java.util.Timer;
import java.util.TimerTask;

public class SituationList extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener, OnItemClickListener, MenuItem.OnMenuItemClickListener, OnCheckedChangeListener, PopupMenu.OnMenuItemClickListener, PopupMenu.OnDismissListener {
    private static final String TAG = "SituationList";
    private static final int PERMISSIONS_REQUEST = 1001;

    private static final String[] MAPTREK_PERMISSIONS = {
            "mobi.maptrek.permission.RECEIVE_LOCATION",
            "mobi.maptrek.permission.WRITE_MAP_DATA"
    };

    private ActUserlistBinding binding;
    private SituationListAdapter adapter;
    public SharingService sharingService = null;

    private Timer timer;
    // private int timeoutInterval = 600; // 10 minutes (default)

    private SwitchCompat enableSwitch;

    private int selectedPosition = -1;
    private Drawable selectedBackground;
    private int lastSelectedPosition;
    private int accentColor;
    private String[] mPermissions;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActUserlistBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
            actionBar.setDisplayHomeAsUpEnabled(true);

        binding.list.setEmptyView(binding.empty);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        accentColor = getResources().getColor(R.color.seed, getTheme());

        adapter = new SituationListAdapter(this);
        binding.list.setAdapter(adapter);
        binding.list.setOnItemClickListener(this);

        mPermissions = MAPTREK_PERMISSIONS;
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
        menu.findItem(R.id.action_enable).setActionView(R.layout.switch_action);
        enableSwitch = (SwitchCompat) menu.findItem(R.id.action_enable).getActionView();
        enableSwitch.setOnCheckedChangeListener(this);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        onSharedPreferenceChanged(sharedPreferences, null);
        if (isServiceRunning()) {
            enableSwitch.setChecked(true);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, Preferences.class));
            return true;
        } else if (id == android.R.id.home) {
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
        popupMenu.show();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked && !isServiceRunning()) {
            boolean notGranted = false;
            for (String permission : mPermissions)
                notGranted |= checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED;
            if (notGranted) {
                requestPermission();
            } else {
                start();
            }
        } else if (!isChecked && isServiceRunning()) {
            disconnect();
            stopService(new Intent(this, SharingService.class));
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null)
                actionBar.setSubtitle("");
            binding.empty.setText(R.string.msg_needs_enable);
            adapter.notifyDataSetChanged();
        }
    }

    private void requestPermission() {
        boolean shouldShow = false;
        for (String permission : mPermissions)
            shouldShow |= shouldShowRequestPermissionRationale(permission);
        if (shouldShow) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.msgPermissionsRationale))
                    .setPositiveButton(R.string.ok, (dialog, which) -> requestPermissions(mPermissions, PERMISSIONS_REQUEST))
                    .setNegativeButton(R.string.cancel, (dialog, which) -> finish())
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
                    break;
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
        binding.empty.setText(R.string.msg_no_users);
        startService(new Intent(this, SharingService.class));
        connect();
    }

    private void connect() {
        bindService(new Intent(this, SharingService.class), sharingConnection, 0);
        timer = new Timer();
        TimerTask updateTask = new UpdateTask();
        timer.scheduleAtFixedRate(updateTask, 5000, 5000);
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

        int id = item.getItemId();
        if (id == R.id.action_view) {
            Intent i = new Intent("mobi.maptrek.action.CENTER_ON_COORDINATES");
            i.putExtra("lat", situation.latitude);
            i.putExtra("lon", situation.longitude);
            startActivity(i);
            finish();
            return true;
        } else if (id == R.id.action_navigate) {
            Intent intent = new Intent("mobi.maptrek.action.NAVIGATE_TO_OBJECT");
            intent.putExtra("id", situation.id);
            startActivity(intent);
            finish();
            return true;
        }
        return false;
    }

    @Override
    public void onDismiss(PopupMenu popupMenu) {
        selectedPosition = -1;
        View v = binding.list.findViewWithTag("selected");
        if (v != null) {
            v.setBackground(selectedBackground);
            v.setTag(null);
        }
    }

    private final ServiceConnection sharingConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            sharingService = ((SharingService.LocalBinder) service).getService();
            ContextCompat.registerReceiver(SituationList.this, sharingReceiver, new IntentFilter(SharingService.BROADCAST_SITUATION_CHANGED), ContextCompat.RECEIVER_EXPORTED);
            runOnUiThread(() -> {
                ActionBar actionBar = getSupportActionBar();
                if (actionBar != null)
                    actionBar.setSubtitle(sharingService.user + " ∈ " + sharingService.session);
                adapter.notifyDataSetChanged();
            });
            Log.d(TAG, "Sharing service connected");
        }

        public void onServiceDisconnected(ComponentName className) {
            sharingService = null;
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null)
                actionBar.setSubtitle("");
            adapter.notifyDataSetChanged();
            Log.d(TAG, "Sharing service disconnected");
        }
    };

    private final BroadcastReceiver sharingReceiver = new BroadcastReceiver() {

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
        private final LayoutInflater mInflater;
        private final int mItemLayout;

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
                text = (TextView) v.findViewById(R.id.delay);
                if (text != null) {
                    if (now - d > sharingService.updateInterval) {
                        String delay = (String) DateUtils.getRelativeTimeSpanString(d, now, DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
                        text.setText(delay);
                    } else {
                        text.setText("");
                    }
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
            binding.empty.setText(R.string.msg_needs_enable);
        } else {
            enableSwitch.setEnabled(false);
            binding.empty.setText(R.string.msg_needs_setup);
        }

        if (adapter != null)
            adapter.notifyDataSetChanged();
    }

    class UpdateTask extends TimerTask {
        public void run() {
            runOnUiThread(() -> {
                if (adapter != null && selectedPosition == -1)
                    adapter.notifyDataSetChanged();
            });
        }
    }
}
