package com.androzic.plugin.locationshare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.preference.PreferenceManager;

public class Executor extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.e("Executor", action);
        if (action.equals("com.androzic.plugins.action.INITIALIZE") ||
                action.equals("mobi.maptrek.plugins.action.INITIALIZE")) {
            PreferenceManager.setDefaultValues(context, R.xml.preferences, true);
            PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("maptrek", action.equals("mobi.maptrek.plugins.action.INITIALIZE")).apply();
        } else if (action.equals("com.androzic.plugins.action.FINALIZE") ||
                action.equals("mobi.maptrek.plugins.action.FINALIZE")) {
            context.stopService(new Intent(context, SharingService.class));
        }
    }
}
