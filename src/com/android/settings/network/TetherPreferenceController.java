/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settings.network;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.annotation.VisibleForTesting;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;

import com.android.settings.R;
import com.android.settings.TetherSettings;
import com.android.settings.core.PreferenceController;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static android.os.UserManager.DISALLOW_CONFIG_TETHERING;
import static com.android.settingslib.RestrictedLockUtils.checkIfRestrictionEnforced;
import static com.android.settingslib.RestrictedLockUtils.hasBaseUserRestriction;

public class TetherPreferenceController extends PreferenceController {

    private static final String KEY_TETHER_SETTINGS = "tether_settings";

    private final boolean mAdminDisallowedTetherConfig;
    private final AtomicReference<BluetoothPan> mBluetoothPan;
    private final ConnectivityManager mConnectivityManager;
    private final BluetoothAdapter mBluetoothAdapter;
    private final UserManager mUserManager;

    private final BluetoothProfile.ServiceListener mBtProfileServiceListener =
            new android.bluetooth.BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    mBluetoothPan.set((BluetoothPan) proxy);
                    updateSummary();
                }

                public void onServiceDisconnected(int profile) {
                    mBluetoothPan.set(null);
                }
            };

    private Preference mPreference;

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    TetherPreferenceController() {
        super(null);
        mAdminDisallowedTetherConfig = false;
        mBluetoothPan = null;
        mConnectivityManager = null;
        mBluetoothAdapter = null;
        mUserManager = null;
    }

    public TetherPreferenceController(Context context) {
        super(context);
        mBluetoothPan = new AtomicReference<>();
        mAdminDisallowedTetherConfig = checkIfRestrictionEnforced(
                context, DISALLOW_CONFIG_TETHERING, UserHandle.myUserId()) != null;
        mConnectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.getProfileProxy(context, mBtProfileServiceListener,
                    BluetoothProfile.PAN);
        }
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mPreference = screen.findPreference(KEY_TETHER_SETTINGS);
        if (mPreference != null && !mAdminDisallowedTetherConfig) {
            mPreference.setTitle(
                    com.android.settingslib.Utils.getTetheringLabel(mConnectivityManager));

            // Grey out if provisioning is not available.
            mPreference.setEnabled(!TetherSettings.isProvisioningNeededButUnavailable(mContext));
        }
    }

    @Override
    public boolean isAvailable() {
        final boolean isBlocked =
                (!mConnectivityManager.isTetheringSupported() && !mAdminDisallowedTetherConfig)
                        || hasBaseUserRestriction(mContext, DISALLOW_CONFIG_TETHERING,
                        UserHandle.myUserId());
        return !isBlocked;
    }

    @Override
    public void updateState(Preference preference) {
        updateSummary();
    }

    @Override
    public void updateNonIndexableKeys(List<String> keys) {
        if (!mUserManager.isAdminUser() || !mConnectivityManager.isTetheringSupported()) {
            keys.add(KEY_TETHER_SETTINGS);
        }
    }

    @Override
    public String getPreferenceKey() {
        return KEY_TETHER_SETTINGS;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    void updateSummary() {
        if (mPreference == null) {
            // Preference is not ready yet.
            return;
        }
        String[] allTethered = mConnectivityManager.getTetheredIfaces();
        String[] wifiTetherRegex = mConnectivityManager.getTetherableWifiRegexs();
        String[] bluetoothRegex = mConnectivityManager.getTetherableBluetoothRegexs();

        boolean hotSpotOn = false;
        boolean tetherOn = false;
        if (allTethered != null) {
            if (wifiTetherRegex != null) {
                for (String tethered : allTethered) {
                    for (String regex : wifiTetherRegex) {
                        if (tethered.matches(regex)) {
                            hotSpotOn = true;
                            break;
                        }
                    }
                }
            }
            if (allTethered.length > 1) {
                // We have more than 1 tethered connection
                tetherOn = true;
            } else if (allTethered.length == 1) {
                // We have more than 1 tethered, it's either wifiTether (hotspot), or other type of
                // tether.
                tetherOn = !hotSpotOn;
            } else {
                // No tethered connection.
                tetherOn = false;
            }
        }
        if (!tetherOn
                && bluetoothRegex != null && bluetoothRegex.length > 0
                && mBluetoothAdapter != null
                && mBluetoothAdapter.getState() == BluetoothAdapter.STATE_ON) {
            // Check bluetooth state. It's not included in mConnectivityManager.getTetheredIfaces.
            final BluetoothPan pan = mBluetoothPan.get();
            tetherOn = pan != null && pan.isTetheringOn();
        }
        if (!hotSpotOn && !tetherOn) {
            // Both off
            mPreference.setSummary(R.string.switch_off_text);
        } else if (hotSpotOn && tetherOn) {
            // Both on
            mPreference.setSummary(R.string.tether_settings_summary_hotspot_on_tether_on);
        } else if (hotSpotOn) {
            mPreference.setSummary(R.string.tether_settings_summary_hotspot_on_tether_off);
        } else {
            mPreference.setSummary(R.string.tether_settings_summary_hotspot_off_tether_on);
        }
    }
}