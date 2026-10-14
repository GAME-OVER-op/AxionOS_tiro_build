/*
 * SPDX-FileCopyrightText: 2015 The CyanogenMod Project
 * SPDX-FileCopyrightText: 2017-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.lineageparts.livedisplay;

import static lineageos.hardware.LiveDisplayManager.FEATURE_ANTI_FLICKER;
import static lineageos.hardware.LiveDisplayManager.FEATURE_CABC;
import static lineageos.hardware.LiveDisplayManager.FEATURE_COLOR_ADJUSTMENT;
import static lineageos.hardware.LiveDisplayManager.FEATURE_COLOR_ENHANCEMENT;
import static lineageos.hardware.LiveDisplayManager.FEATURE_PICTURE_ADJUSTMENT;
import static lineageos.hardware.LiveDisplayManager.FEATURE_READING_ENHANCEMENT;
import static lineageos.hardware.LiveDisplayManager.MODE_AUTO;
import static lineageos.hardware.LiveDisplayManager.MODE_DAY;
import static lineageos.hardware.LiveDisplayManager.MODE_NIGHT;
import static lineageos.hardware.LiveDisplayManager.MODE_OFF;
import static lineageos.hardware.LiveDisplayManager.MODE_OUTDOOR;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.ColorDisplayManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.os.SystemProperties;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.util.ArrayUtils;

import lineageos.hardware.LineageHardwareManager;
import lineageos.hardware.LiveDisplayConfig;
import lineageos.hardware.LiveDisplayManager;
import lineageos.preference.SettingsHelper;
import lineageos.providers.LineageSettings;

import org.lineageos.lineageparts.R;
import org.lineageos.lineageparts.SettingsPreferenceFragment;
import org.lineageos.lineageparts.search.BaseSearchIndexProvider;
import org.lineageos.lineageparts.search.SearchIndexableRaw;
import org.lineageos.lineageparts.search.Searchable;
import org.lineageos.lineageparts.utils.GenericUtils;
import org.lineageos.lineageparts.utils.ResourceUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class LiveDisplaySettings extends SettingsPreferenceFragment implements Searchable,
        Preference.OnPreferenceChangeListener, SettingsHelper.OnSettingsChangeListener {

    private static final String TAG = "LiveDisplay";

    private static final String KEY_SCREEN_LIVE_DISPLAY = "livedisplay";

    private static final String KEY_CATEGORY_ADVANCED = "advanced";

    private static final String KEY_LIVE_DISPLAY = "live_display";
    private static final String KEY_LIVE_DISPLAY_ANTI_FLICKER = "display_anti_flicker";
    private static final String KEY_LIVE_DISPLAY_AUTO_OUTDOOR_MODE =
            "display_auto_outdoor_mode";
    private static final String KEY_LIVE_DISPLAY_READING_ENHANCEMENT = "display_reading_mode";
    private static final String KEY_LIVE_DISPLAY_LOW_POWER = "display_low_power";
    private static final String KEY_LIVE_DISPLAY_COLOR_ENHANCE = "display_color_enhance";
    private static final String KEY_LIVE_DISPLAY_TEMPERATURE = "live_display_color_temperature";

    private static final String KEY_TIRO_HBM_MODE = "tiro_hbm_mode";
    private static final String KEY_TIRO_HBM_LUX_ON = "tiro_hbm_lux_on";
    private static final String KEY_TIRO_HBM_LUX_OFF = "tiro_hbm_lux_off";
    private static final String HBM_AUTO = "auto_hbm_enabled";
    private static final String HBM_FORCE = "force_hbm_enabled";
    private static final String HBM_LUX_ON = "auto_hbm_lux_on";
    private static final String HBM_LUX_OFF = "auto_hbm_lux_off";

    private ListPreference mTiroHbmMode;
    private SeekBarPreference mTiroLuxOn;
    private SeekBarPreference mTiroLuxOff;

    private static boolean supportsTiroHbm() {
        // Never expose the native Tiro panel node controls on other devices.
        return "tiro".equals(Build.DEVICE) && !SystemProperties.get(
                "persist.sys.ax_hbm_file",
                SystemProperties.get("persist.sys.hbmservice_file", "")).isEmpty();
    }

    private static final String KEY_DISPLAY_COLOR = "color_calibration";
    private static final String KEY_PICTURE_ADJUSTMENT = "picture_adjustment";

    private final Uri DISPLAY_TEMPERATURE_DAY_URI =
            LineageSettings.System.getUriFor(LineageSettings.System.DISPLAY_TEMPERATURE_DAY);
    private final Uri DISPLAY_TEMPERATURE_NIGHT_URI =
            LineageSettings.System.getUriFor(LineageSettings.System.DISPLAY_TEMPERATURE_NIGHT);
    private final Uri DISPLAY_TEMPERATURE_MODE_URI =
            LineageSettings.System.getUriFor(LineageSettings.System.DISPLAY_TEMPERATURE_MODE);

    private ListPreference mLiveDisplay;

    private SwitchPreferenceCompat mOutdoorMode;
    private SwitchPreferenceCompat mReadingMode;

    private DisplayTemperature mDisplayTemperature;

    private String[] mModeValues;
    private String[] mModeSummaries;

    private LiveDisplayManager mLiveDisplayManager;

    private LineageHardwareManager mHardware;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Resources res = getResources();
        final boolean isNightDisplayAvailable =
                ColorDisplayManager.isNightDisplayAvailable(requireContext());

        mHardware = LineageHardwareManager.getInstance(getActivity());
        mLiveDisplayManager = LiveDisplayManager.getInstance(getActivity());
        LiveDisplayConfig config = mLiveDisplayManager.getConfig();

        addPreferencesFromResource(R.xml.livedisplay);

        PreferenceScreen liveDisplayPrefs = findPreference(KEY_SCREEN_LIVE_DISPLAY);

        PreferenceCategory advancedPrefs = findPreference(KEY_CATEGORY_ADVANCED);
        initTiroHbm(liveDisplayPrefs);

        int adaptiveMode = mLiveDisplayManager.getMode();

        mLiveDisplay = findPreference(KEY_LIVE_DISPLAY);
        mLiveDisplay.setValue(String.valueOf(adaptiveMode));

        String[] modeEntries = res.getStringArray(
                org.lineageos.platform.internal.R.array.live_display_entries);
        mModeValues = res.getStringArray(
                org.lineageos.platform.internal.R.array.live_display_values);
        mModeSummaries = res.getStringArray(
                org.lineageos.platform.internal.R.array.live_display_summaries);

        int[] removeIdx = null;
        // Remove outdoor mode from lists if there is no support
        if (!config.hasFeature(MODE_OUTDOOR)) {
            removeIdx = ArrayUtils.appendInt(removeIdx,
                    ArrayUtils.indexOf(mModeValues, String.valueOf(MODE_OUTDOOR)));
        } else if (isNightDisplayAvailable) {
            final int autoIdx = ArrayUtils.indexOf(mModeValues, String.valueOf(MODE_AUTO));
            mModeSummaries[autoIdx] = res.getString(R.string.live_display_outdoor_mode_summary);
        }

        // Remove night display on HWC2
        if (isNightDisplayAvailable) {
            removeIdx = ArrayUtils.appendInt(removeIdx,
                    ArrayUtils.indexOf(mModeValues, String.valueOf(MODE_DAY)));
            removeIdx = ArrayUtils.appendInt(removeIdx,
                    ArrayUtils.indexOf(mModeValues, String.valueOf(MODE_NIGHT)));
        }

        if (removeIdx != null) {
            String[] entriesTemp = new String[modeEntries.length - removeIdx.length];
            String[] valuesTemp = new String[mModeValues.length - removeIdx.length];
            String[] summariesTemp = new String[mModeSummaries.length - removeIdx.length];
            int j = 0;
            for (int i = 0; i < modeEntries.length; i++) {
                if (ArrayUtils.contains(removeIdx, i)) {
                    continue;
                }
                entriesTemp[j] = modeEntries[i];
                valuesTemp[j] = mModeValues[i];
                summariesTemp[j] = mModeSummaries[i];
                j++;
            }
            modeEntries = entriesTemp;
            mModeValues = valuesTemp;
            mModeSummaries = summariesTemp;
        }

        mLiveDisplay.setEntries(modeEntries);
        mLiveDisplay.setEntryValues(mModeValues);
        mLiveDisplay.setOnPreferenceChangeListener(this);

        mDisplayTemperature = findPreference(KEY_LIVE_DISPLAY_TEMPERATURE);
        if (isNightDisplayAvailable) {
            if (!config.hasFeature(MODE_OUTDOOR)) {
                liveDisplayPrefs.removePreference(mLiveDisplay);
            }
            liveDisplayPrefs.removePreference(mDisplayTemperature);
        }

        mOutdoorMode = findPreference(KEY_LIVE_DISPLAY_AUTO_OUTDOOR_MODE);
        if (liveDisplayPrefs != null && mOutdoorMode != null
                // MODE_AUTO implies automatic outdoor mode on HWC2
                && (isNightDisplayAvailable || !config.hasFeature(MODE_OUTDOOR))) {
            liveDisplayPrefs.removePreference(mOutdoorMode);
            mOutdoorMode = null;
        }

        mReadingMode = findPreference(KEY_LIVE_DISPLAY_READING_ENHANCEMENT);
        if (liveDisplayPrefs != null && mReadingMode != null &&
                !mHardware.isSupported(LineageHardwareManager.FEATURE_READING_ENHANCEMENT)) {
            liveDisplayPrefs.removePreference(mReadingMode);
            mReadingMode = null;
        } else {
            mReadingMode.setOnPreferenceChangeListener(this);
        }

        SwitchPreferenceCompat lowPower = findPreference(KEY_LIVE_DISPLAY_LOW_POWER);
        if (advancedPrefs != null && lowPower != null
                && !config.hasFeature(FEATURE_CABC)) {
            advancedPrefs.removePreference(lowPower);
        }

        SwitchPreferenceCompat colorEnhancement = findPreference(KEY_LIVE_DISPLAY_COLOR_ENHANCE);
        if (advancedPrefs != null && colorEnhancement != null
                && !config.hasFeature(FEATURE_COLOR_ENHANCEMENT)) {
            advancedPrefs.removePreference(colorEnhancement);
        }

        PictureAdjustment pictureAdjustment = findPreference(KEY_PICTURE_ADJUSTMENT);
        if (advancedPrefs != null && pictureAdjustment != null &&
                    !config.hasFeature(FEATURE_PICTURE_ADJUSTMENT)) {
            advancedPrefs.removePreference(pictureAdjustment);
        }

        DisplayColor misplayColor = findPreference(KEY_DISPLAY_COLOR);
        if (advancedPrefs != null && misplayColor != null &&
                !config.hasFeature(FEATURE_COLOR_ADJUSTMENT)) {
            advancedPrefs.removePreference(misplayColor);
        }

        SwitchPreferenceCompat antiFlicker = findPreference(KEY_LIVE_DISPLAY_ANTI_FLICKER);
        if (liveDisplayPrefs != null && antiFlicker != null &&
                !mHardware.isSupported(LineageHardwareManager.FEATURE_ANTI_FLICKER)) {
            liveDisplayPrefs.removePreference(antiFlicker);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshTiroHbm();
        updateModeSummary();
        updateTemperatureSummary();
        updateReadingModeStatus();
        SettingsHelper.get(getActivity()).startWatching(this, DISPLAY_TEMPERATURE_DAY_URI,
                DISPLAY_TEMPERATURE_MODE_URI, DISPLAY_TEMPERATURE_NIGHT_URI);
    }

    @Override
    public void onPause() {
        super.onPause();
        SettingsHelper.get(getActivity()).stopWatching(this);
    }

    private void initTiroHbm(PreferenceScreen screen) {
        mTiroHbmMode = findPreference(KEY_TIRO_HBM_MODE);
        mTiroLuxOn = findPreference(KEY_TIRO_HBM_LUX_ON);
        mTiroLuxOff = findPreference(KEY_TIRO_HBM_LUX_OFF);
        if (!supportsTiroHbm()) {
            if (screen != null) {
                if (mTiroHbmMode != null) screen.removePreference(mTiroHbmMode);
                if (mTiroLuxOn != null) screen.removePreference(mTiroLuxOn);
                if (mTiroLuxOff != null) screen.removePreference(mTiroLuxOff);
            }
            mTiroHbmMode = null;
            mTiroLuxOn = null;
            mTiroLuxOff = null;
            return;
        }
        if (mTiroHbmMode != null) mTiroHbmMode.setOnPreferenceChangeListener(this);
        if (mTiroLuxOn != null) {
            mTiroLuxOn.setMin(5500);
            mTiroLuxOn.setMax(120000);
            mTiroLuxOn.setSeekBarIncrement(500);
            mTiroLuxOn.setShowSeekBarValue(true);
            mTiroLuxOn.setOnPreferenceChangeListener(this);
        }
        if (mTiroLuxOff != null) {
            mTiroLuxOff.setMin(5000);
            mTiroLuxOff.setMax(119500);
            mTiroLuxOff.setSeekBarIncrement(500);
            mTiroLuxOff.setShowSeekBarValue(true);
            mTiroLuxOff.setOnPreferenceChangeListener(this);
        }
        refreshTiroHbm();
    }

    private void refreshTiroHbm() {
        if (mTiroHbmMode == null) return;
        final boolean forced = Settings.Secure.getInt(
                requireContext().getContentResolver(), HBM_FORCE, 0) == 1;
        final boolean auto = Settings.Secure.getInt(
                requireContext().getContentResolver(), HBM_AUTO, 0) == 1;
        mTiroHbmMode.setValue(forced ? "forced" : auto ? "auto" : "off");
        mTiroHbmMode.setSummary(mTiroHbmMode.getEntry());
        if (mTiroLuxOn != null) {
            mTiroLuxOn.setVisible(auto && !forced);
            mTiroLuxOn.setValue(Settings.Secure.getInt(
                    requireContext().getContentResolver(), HBM_LUX_ON, 110000));
        }
        if (mTiroLuxOff != null) {
            mTiroLuxOff.setVisible(auto && !forced);
            mTiroLuxOff.setValue(Settings.Secure.getInt(
                    requireContext().getContentResolver(), HBM_LUX_OFF, 20000));
        }
    }

    private void updateModeSummary() {
        int mode = mLiveDisplayManager.getMode();

        int index = ArrayUtils.indexOf(mModeValues, String.valueOf(mode));
        if (index < 0) {
            index = ArrayUtils.indexOf(mModeValues, String.valueOf(MODE_OFF));
        }

        mLiveDisplay.setSummary(mModeSummaries[index]);
        mLiveDisplay.setValue(String.valueOf(mode));

        if (mDisplayTemperature != null) {
            mDisplayTemperature.setEnabled(mode != MODE_OFF);
        }
        if (mOutdoorMode != null) {
            mOutdoorMode.setEnabled(mode != MODE_OFF);
        }
    }

    private void updateTemperatureSummary() {
        int day = mLiveDisplayManager.getDayColorTemperature();
        int night = mLiveDisplayManager.getNightColorTemperature();

        mDisplayTemperature.setSummary(getResources().getString(
                R.string.live_display_color_temperature_summary,
                mDisplayTemperature.roundUp(day),
                mDisplayTemperature.roundUp(night)));
    }

    private void updateReadingModeStatus() {
        if (mReadingMode != null) {
            mReadingMode.setChecked(
                    mHardware.get(LineageHardwareManager.FEATURE_READING_ENHANCEMENT));
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mTiroHbmMode) {
            final String choice = (String) objValue;
            Settings.Secure.putInt(requireContext().getContentResolver(), HBM_FORCE,
                    "forced".equals(choice) ? 1 : 0);
            Settings.Secure.putInt(requireContext().getContentResolver(), HBM_AUTO,
                    "auto".equals(choice) ? 1 : 0);
            refreshTiroHbm();
            return true;
        } else if (preference == mTiroLuxOn || preference == mTiroLuxOff) {
            final int value = (Integer) objValue;
            if (preference == mTiroLuxOn) {
                final int luxOn = Math.max(5500, Math.min(value, 120000));
                Settings.Secure.putInt(requireContext().getContentResolver(), HBM_LUX_ON, luxOn);
                final int luxOff = Settings.Secure.getInt(
                        requireContext().getContentResolver(), HBM_LUX_OFF, 20000);
                if (luxOff >= luxOn) {
                    Settings.Secure.putInt(requireContext().getContentResolver(), HBM_LUX_OFF,
                            Math.max(5000, luxOn - 500));
                }
            } else {
                final int on = Settings.Secure.getInt(
                        requireContext().getContentResolver(), HBM_LUX_ON, 110000);
                Settings.Secure.putInt(requireContext().getContentResolver(), HBM_LUX_OFF,
                        Math.max(5000, Math.min(value, on - 500)));
            }
            refreshTiroHbm();
            return true;
        } else if (preference == mLiveDisplay) {
            mLiveDisplayManager.setMode(Integer.parseInt((String)objValue));
        } else if (preference == mReadingMode) {
            mHardware.set(LineageHardwareManager.FEATURE_READING_ENHANCEMENT, (Boolean) objValue);
        }
        return true;
    }

    @Override
    public void onSettingsChanged(Uri uri) {
        updateModeSummary();
        updateTemperatureSummary();
        updateReadingModeStatus();
    }

    public static void restoreLiveDisplay(Context context) {
        GenericUtils.setComponentEnabled(context, LiveDisplaySettings.class.getName(),
                context.getResources().getBoolean(
                        org.lineageos.platform.internal.R.bool.config_enableLiveDisplay));
    }

    public static final Searchable.SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider() {

        @Override
        public Set<String> getNonIndexableKeys(Context context) {
            final LiveDisplayConfig config = LiveDisplayManager.getInstance(context).getConfig();
            final Set<String> result = new ArraySet<>();

            if (!config.hasFeature(MODE_OUTDOOR)) {
                result.add(KEY_LIVE_DISPLAY_AUTO_OUTDOOR_MODE);
            }
            if (!config.hasFeature(FEATURE_COLOR_ENHANCEMENT)) {
                result.add(KEY_LIVE_DISPLAY_COLOR_ENHANCE);
            }
            if (!config.hasFeature(FEATURE_CABC)) {
                result.add(KEY_LIVE_DISPLAY_LOW_POWER);
            }
            if (!config.hasFeature(FEATURE_COLOR_ADJUSTMENT)) {
                result.add(KEY_DISPLAY_COLOR);
            }
            if (!config.hasFeature(FEATURE_PICTURE_ADJUSTMENT)) {
                result.add(KEY_PICTURE_ADJUSTMENT);
            }
            if (!config.hasFeature(FEATURE_READING_ENHANCEMENT)) {
                result.add(KEY_LIVE_DISPLAY_READING_ENHANCEMENT);
            }
            if (ColorDisplayManager.isNightDisplayAvailable(context)) {
                if (!config.hasFeature(MODE_OUTDOOR)) {
                    result.add(KEY_LIVE_DISPLAY);
                }
                result.add(KEY_LIVE_DISPLAY_TEMPERATURE);
            }
            if (!context.getResources().getBoolean(
                    org.lineageos.platform.internal.R.bool.config_enableLiveDisplay)) {
                result.add(KEY_LIVE_DISPLAY_TEMPERATURE);
                result.add(KEY_LIVE_DISPLAY);
            }
            if (!config.hasFeature(FEATURE_ANTI_FLICKER)) {
                result.add(KEY_LIVE_DISPLAY_ANTI_FLICKER);
            }

            if (!supportsTiroHbm()) {
                result.add(KEY_TIRO_HBM_MODE);
                result.add(KEY_TIRO_HBM_LUX_ON);
                result.add(KEY_TIRO_HBM_LUX_OFF);
            }
            return result;
        }
    };
}
