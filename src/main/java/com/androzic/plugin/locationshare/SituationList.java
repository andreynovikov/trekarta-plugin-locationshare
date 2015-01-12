package com.androzic.plugin.locationshare;

import java.util.Timer;
import java.util.TimerTask;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.internal.view.SupportMenuInflater;
import android.support.v7.internal.view.menu.MenuBuilder;
import android.support.v7.internal.view.menu.MenuPopupHelper;
import android.support.v7.internal.view.menu.MenuPresenter;
import android.support.v7.widget.SwitchCompat;
import android.support.v7.widget.Toolbar;
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
import android.widget.TextView;

import com.androzic.data.Situation;
import com.androzic.navigation.BaseNavigationService;
import com.androzic.util.Geo;
import com.androzic.util.StringFormatter;

public class SituationList extends ActionBarActivity implements OnSharedPreferenceChangeListener, OnItemClickListener, MenuBuilder.Callback, MenuPresenter.Callback, OnCheckedChangeListener
{
	private static final String TAG = "SituationList";

	private ListView listView;
	private TextView emptyView;
	private SituationListAdapter adapter;
	public SharingService sharingService = null;

	private Timer timer;
	// private int timeoutInterval = 600; // 10 minutes (default)

	private SwitchCompat enableSwitch;

	private int selectedPosition = -1;
	private Drawable selectedBackground;
	private int accentColor;

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.act_userlist);

	    Toolbar toolbar = (Toolbar) findViewById(R.id.action_toolbar);
	    setSupportActionBar(toolbar);

	    listView = (ListView) findViewById(android.R.id.list);
		emptyView = (TextView) findViewById(android.R.id.empty);
		listView.setEmptyView(emptyView);

		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		sharedPreferences.registerOnSharedPreferenceChangeListener(this);
		
		accentColor = getResources().getColor(R.color.theme_accent_color);

		adapter = new SituationListAdapter(this);
		listView.setAdapter(adapter);
		listView.setOnItemClickListener(this);
	}

	@Override
	public void onResume()
	{
		super.onResume();
		if (isServiceRunning())
		{
			connect();
		}
	}

	@Override
	public void onPause()
	{
		super.onPause();
		disconnect();
	}

	@Override
	public void onDestroy()
	{
		super.onDestroy();
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu)
	{
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.preferences, menu);

		// Get widget's instance
		enableSwitch = (SwitchCompat)  MenuItemCompat.getActionView(menu.findItem(R.id.action_enable));
		enableSwitch.setOnCheckedChangeListener(this);

		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		onSharedPreferenceChanged(sharedPreferences, null);
		if (isServiceRunning())
		{
			enableSwitch.setChecked(true);
		}

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item)
	{
		switch (item.getItemId())
		{
			case R.id.action_settings:
				startActivity(new Intent(this, Preferences.class));
				return true;
		}
		return false;
	}

	@Override
	public void onItemClick(AdapterView<?> l, View v, int position, long id)
	{
		v.setTag("selected");
		selectedPosition = position;
		selectedBackground = v.getBackground();
		v.setBackgroundColor(accentColor);
		// https://gist.github.com/mediavrog/9345938#file-iconizedmenu-java-L55
		MenuBuilder menu = new MenuBuilder(this);
		menu.setCallback(this);
		MenuPopupHelper popup = new MenuPopupHelper(this, menu, v.findViewById(R.id.name));
		popup.setForceShowIcon(true);
		popup.setCallback(this);
		new SupportMenuInflater(this).inflate(R.menu.situation_popup, menu);
		popup.show();
	}

	@Override
	public void onCheckedChanged(CompoundButton buttonView, boolean isChecked)
	{
		if (isChecked && !isServiceRunning())
		{
			emptyView.setText(R.string.msg_no_users);
			startService(new Intent(this, SharingService.class));
			connect();
		}
		else if (!isChecked && isServiceRunning())
		{
			disconnect();
			stopService(new Intent(this, SharingService.class));
			getSupportActionBar().setSubtitle("");
			emptyView.setText(R.string.msg_needs_enable);
			adapter.notifyDataSetChanged();
		}
	}

	private void connect()
	{
		bindService(new Intent(this, SharingService.class), sharingConnection, 0);
		timer = new Timer();
		TimerTask updateTask = new UpdateTask();
		timer.scheduleAtFixedRate(updateTask, 1000, 1000);
	}

	private void disconnect()
	{
		if (sharingService != null)
		{
			unregisterReceiver(sharingReceiver);
			unbindService(sharingConnection);
			sharingService = null;
		}
		if (timer != null)
		{
			timer.cancel();
			timer = null;
		}
	}

	private boolean isServiceRunning()
	{
		ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
		for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE))
		{
			if ("com.androzic.plugin.locationshare.SharingService".equals(service.service.getClassName()) && service.pid > 0)
				return true;
		}
		return false;
	}

	@Override
	public boolean onMenuItemSelected(MenuBuilder builder, MenuItem item)
	{
		Situation situation = adapter.getItem(selectedPosition);

		switch (item.getItemId())
		{
			case R.id.action_view:
				Intent i = new Intent("com.androzic.CENTER_ON_COORDINATES");
				i.putExtra("lat", situation.latitude);
				i.putExtra("lon", situation.longitude);
				sendBroadcast(i);
				return true;
			case R.id.action_navigate:
				Intent intent = new Intent(BaseNavigationService.NAVIGATE_MAPOBJECT_WITH_ID);
				intent.putExtra(BaseNavigationService.EXTRA_ID, situation._id);
				startService(intent);
				finish();
				return true;
		}
		return false;
	}

	@Override
	public void onMenuModeChange(MenuBuilder builder)
	{
	}

	@SuppressWarnings("deprecation")
	@Override
	public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing)
	{
    	selectedPosition = -1;
		if (allMenusAreClosing && listView != null)
		{
			View v = listView.findViewWithTag("selected");
			if (v != null)
			{
				v.setBackgroundDrawable(selectedBackground);
				v.setTag(null);
			}
		}
	}

	@Override
	public boolean onOpenSubMenu(MenuBuilder menu)
	{
		return false;
	}

	private ServiceConnection sharingConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service)
		{
			sharingService = ((SharingService.LocalBinder) service).getService();
			registerReceiver(sharingReceiver, new IntentFilter(SharingService.BROADCAST_SITUATION_CHANGED));
			runOnUiThread(new Runnable() {
				public void run()
				{
					getSupportActionBar().setSubtitle(sharingService.user + " \u2208 " + sharingService.session);
					adapter.notifyDataSetChanged();
				}
			});
			Log.d(TAG, "Sharing service connected");
		}

		public void onServiceDisconnected(ComponentName className)
		{
			sharingService = null;
			getSupportActionBar().setSubtitle("");
			adapter.notifyDataSetChanged();
			Log.d(TAG, "Sharing service disconnected");
		}
	};

	private BroadcastReceiver sharingReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent)
		{
			if (intent.getAction().equals(SharingService.BROADCAST_SITUATION_CHANGED))
			{
				runOnUiThread(new Runnable() {
					public void run()
					{
						adapter.notifyDataSetChanged();
					}
				});
			}
		}
	};

	public class SituationListAdapter extends BaseAdapter
	{
		private LayoutInflater mInflater;
		private int mItemLayout;

		public SituationListAdapter(Context context)
		{
			mItemLayout = R.layout.situation_list_item;
			mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		}

		public Situation getItem(int position)
		{
			if (sharingService != null)
			{
				synchronized (sharingService.situationList)
				{
					return sharingService.situationList.get(position);
				}
			}
			return null;
		}

		@Override
		public long getItemId(int position)
		{
			if (sharingService != null)
			{
				synchronized (sharingService.situationList)
				{
					return sharingService.situationList.get(position)._id;
				}
			}
			return Integer.MIN_VALUE + position;
		}

		@Override
		public int getCount()
		{
			if (sharingService != null)
			{
				synchronized (sharingService.situationList)
				{
					return sharingService.situationList.size();
				}
			}
			return 0;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent)
		{
			View v;
			if (convertView == null)
			{
				v = mInflater.inflate(mItemLayout, parent, false);
			}
			else
			{
				// v = convertView;
				// TODO Have to utilize view
				v = mInflater.inflate(mItemLayout, parent, false);
			}
			if (position == selectedPosition)
				v.setBackgroundColor(accentColor);

			Situation stn = getItem(position);
			if (stn != null && sharingService != null)
			{
				TextView text = (TextView) v.findViewById(R.id.name);
				if (text != null)
				{
					text.setText(stn.name);
				}
				String distance = "";
				synchronized (sharingService.currentLocation)
				{
					if (!"fake".equals(sharingService.currentLocation.getProvider()))
					{
						double dist = Geo.distance(stn.latitude, stn.longitude, sharingService.currentLocation.getLatitude(), sharingService.currentLocation.getLongitude());
						distance = StringFormatter.distanceH(dist);
					}
				}
				text = (TextView) v.findViewById(R.id.distance);
				if (text != null)
				{
					text.setText(distance);
				}
				//FIXME Should initialize StringFormatter for angles
				String track = StringFormatter.angleH(stn.track);
				text = (TextView) v.findViewById(R.id.track);
				if (text != null)
				{
					text.setText(track);
				}
				String speed = String.valueOf(Math.round(stn.speed * sharingService.speedFactor));
				text = (TextView) v.findViewById(R.id.speed);
				if (text != null)
				{
					text.setText(speed + " " + sharingService.speedAbbr);
				}
				long now = System.currentTimeMillis();
				long d = stn.time - sharingService.timeCorrection;
				String delay = (String) DateUtils.getRelativeTimeSpanString(d, now, DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
				text = (TextView) v.findViewById(R.id.delay);
				if (text != null)
				{
					text.setText(delay);
				}
				if (stn.silent)
				{
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
		public boolean hasStableIds()
		{
			return true;
		}
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
	{
		String session = sharedPreferences.getString(getString(R.string.pref_sharing_session), "");
		String user = sharedPreferences.getString(getString(R.string.pref_sharing_user), "");
		if (!session.trim().equals("") && !user.trim().equals(""))
		{
			enableSwitch.setEnabled(true);
			emptyView.setText(R.string.msg_needs_enable);
		}
		else
		{
			enableSwitch.setEnabled(false);
			emptyView.setText(R.string.msg_needs_setup);
		}

		if (adapter != null)
			adapter.notifyDataSetChanged();
	}

	class UpdateTask extends TimerTask
	{
		public void run()
		{
			runOnUiThread(new Runnable() {
				public void run()
				{
					if (adapter != null && selectedPosition == -1)
						adapter.notifyDataSetChanged();
				}
			});
		}
	}
}
