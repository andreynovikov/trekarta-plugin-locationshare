package com.androzic.plugin.locationshare;

import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;

public class Preferences extends ActionBarActivity
{
	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.act_preferences);

		Toolbar toolbar = (Toolbar) findViewById(R.id.action_toolbar);
		setSupportActionBar(toolbar);
		
		toolbar.setSubtitle(R.string.pref_sharing_title);

		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		getSupportActionBar().setHomeButtonEnabled(true);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		// Handle action buttons
		switch (item.getItemId())
		{
			case android.R.id.home:
				finish();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}
}
