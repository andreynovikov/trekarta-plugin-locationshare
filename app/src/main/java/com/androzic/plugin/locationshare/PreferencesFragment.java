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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

public class PreferencesFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.preferences);
        setHasOptionsMenu(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        initSummaries(getPreferenceScreen());
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.help, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_help) {
            PreferencesHelpDialog dialog = new PreferencesHelpDialog();
            dialog.show(getParentFragmentManager(), "dialog");
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Preference pref = findPreference(key);
        setPrefSummary(pref);
    }

    private void setPrefSummary(Preference pref) {
        if (pref instanceof ListPreference) {
            CharSequence summary = ((ListPreference) pref).getEntry();
            if (summary != null) {
                pref.setSummary(summary);
            }
        } else if (pref instanceof EditTextPreference) {
            CharSequence summary = ((EditTextPreference) pref).getText();
            if (summary != null) {
                pref.setSummary(summary);
            }
        }
    }

    private void initSummaries(PreferenceScreen preference) {
        for (int i = preference.getPreferenceCount() - 1; i >= 0; i--) {
            Preference pref = preference.getPreference(i);
            setPrefSummary(pref);

            if (pref instanceof PreferenceScreen) {
                initSummaries((PreferenceScreen) pref);
            }
        }
    }
}
