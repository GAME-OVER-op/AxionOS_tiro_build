/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.server.kernel;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManagerInternal;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.kernel.AxKernelControl;
import com.android.internal.kernel.AxKernelMetrics;
import com.android.server.LocalServices;
import com.android.server.kernel.AxKernelConfigLoader;
import com.android.server.kernel.AxKernelMetricsReader;
import com.android.server.kernel.KernelControlNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class AxKernelManagerService {
    private static final String TAG = "AxKernelManager";
    private static final String THREAD_NAME = "AxKernelManagerService";
    private static final boolean CONTROLS_ENABLED =
            SystemProperties.getBoolean(
                    "ro.axion.kernel_manager.supported", true);
    private static final String SETTING_CPU_CLUSTER_COUNT = "ax_cpu_cluster_count";
    private static final String SETTING_CPU_CLUSTER_PREFIX = "ax_cpu_cluster_";
    private static final String SETTING_CPU_CLUSTER_SUFFIX = "_freqs";
    private static final String SETTING_CPU_SMALL_FREQS = "ax_cpu_small_freqs";
    private static final String SETTING_CPU_BIG_FREQS = "ax_cpu_big_freqs";
    private static final String SETTING_CPU_PRIME_FREQS = "ax_cpu_prime_freqs";
    private static final String SETTING_GPU_FREQS = "ax_gpu_freqs";
    private static final char DELIMITER_SPACE = ' ';
    private static final int INDEX_SMALL_CLUSTER = 0;
    private static final int INDEX_BIG_CLUSTER = 1;
    private static final String EMPTY_STRING = "";

    private static volatile AxKernelManagerService sInstance;

    private final Object mLock = new Object();
    private final ContentResolver mResolver;
    private final ArrayMap<String, KernelControlNode> mControls = new ArrayMap<>();
    private final ArrayMap<Uri, KernelControlNode> mUriToControl = new ArrayMap<>();
    private final AxKernelMetricsReader mMetricsReader = new AxKernelMetricsReader();
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final SettingsObserver mSettingsObserver;

    public static synchronized AxKernelManagerService getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new AxKernelManagerService(context);
        }
        return sInstance;
    }

    public static AxKernelManagerService getInstance() {
        return sInstance;
    }

    public AxKernelManagerService(Context context) {
        mResolver = context.getContentResolver();
        mHandlerThread = new HandlerThread(THREAD_NAME);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mSettingsObserver = new SettingsObserver(mHandler);
    }

    public void systemReady() {
        /*
         * Always load the metrics configuration so AxDiagnostics can continue
         * reading CPU/GPU statistics. On devices without writable kernel
         * controls, discard discovered controls before any persisted values
         * or observers can write to sysfs.
         */
        refreshControls();

        if (!CONTROLS_ENABLED) {
            synchronized (mLock) {
                mControls.clear();
                mUriToControl.clear();
            }
            Slog.i(TAG, "Kernel controls disabled; metrics remain available");
            return;
        }

        applyPersistedValues();
        synchronized (mLock) {
            for (KernelControlNode control : mControls.values()) {
                registerObserverForControl(control);
            }
        }
    }

    public List<AxKernelControl> getControls() {
        ArrayList<KernelControlNode> controls = getControlsList();
        ArrayList<AxKernelControl> snapshots = new ArrayList<>(controls.size());
        for (KernelControlNode control : controls) {
            AxKernelControl snapshot = control.snapshot(mResolver);
            if (snapshot != null) snapshots.add(snapshot);
        }
        return snapshots;
    }

    public boolean setControlValue(String id, int value) {
        if (TextUtils.isEmpty(id)) return false;
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                KernelControlNode control = mControls.get(id);
                if (control == null) return false;
                applyCoordinatedControlValueLocked(control, value);
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public AxKernelMetrics getMetrics(long previousCpuActiveTimeTicks, long previousCpuTimeTicks) {
        long token = Binder.clearCallingIdentity();
        try {
            return mMetricsReader.query(previousCpuActiveTimeTicks, previousCpuTimeTicks);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void registerObserverForControl(KernelControlNode control) {
        if (control == null || TextUtils.isEmpty(control.id)) return;
        Uri uri = Settings.Secure.getUriFor(control.id);
        if (uri == null) return;
        mResolver.registerContentObserver(uri, false, mSettingsObserver, UserHandle.USER_ALL);
    }

    private KernelControlNode findControlByUri(Uri uri) {
        if (uri == null) return null;
        synchronized (mLock) {
            return mUriToControl.get(uri);
        }
    }

    private void applyControlUpdate(KernelControlNode control, int value, boolean isOverride) {
        tryWrite(control, value);
        notifyPowerManager(control, value, isOverride);
    }

    private void applyCoordinatedControlValueLocked(KernelControlNode control, int value) {
        int resolvedValue = control.coerce(value);
        if (isMaxFreq(control.type)) {
            int companionType = control.type == AxKernelControl.TYPE_CPU_MAX_FREQ
                    ? AxKernelControl.TYPE_CPU_MIN_FREQ : AxKernelControl.TYPE_GPU_MIN_FREQ;
            KernelControlNode minNode = findCompanionNodeLocked(control, companionType);
            if (minNode != null) {
                int currentMin = minNode.getSavedValue(mResolver);
                if (resolvedValue < currentMin) {
                    int adjustedMin = minNode.coerce(resolvedValue);
                    tryWrite(minNode, adjustedMin);
                    Settings.Secure.putIntForUser(mResolver, minNode.id, adjustedMin, UserHandle.USER_CURRENT);
                    notifyPowerManager(minNode, adjustedMin, adjustedMin != minNode.defaultValue);
                }
            }
        } else if (isMinFreq(control.type)) {
            int companionType = control.type == AxKernelControl.TYPE_CPU_MIN_FREQ
                    ? AxKernelControl.TYPE_CPU_MAX_FREQ : AxKernelControl.TYPE_GPU_MAX_FREQ;
            KernelControlNode maxNode = findCompanionNodeLocked(control, companionType);
            if (maxNode != null) {
                int currentMax = maxNode.getSavedValue(mResolver);
                if (resolvedValue > currentMax) {
                    int adjustedMax = maxNode.coerce(resolvedValue);
                    tryWrite(maxNode, adjustedMax);
                    Settings.Secure.putIntForUser(mResolver, maxNode.id, adjustedMax, UserHandle.USER_CURRENT);
                    notifyPowerManager(maxNode, adjustedMax, adjustedMax != maxNode.defaultValue);
                }
            }
        }
        tryWrite(control, resolvedValue);
        Settings.Secure.putIntForUser(mResolver, control.id, resolvedValue, UserHandle.USER_CURRENT);
        notifyPowerManager(control, resolvedValue, resolvedValue != control.defaultValue);
    }

    private KernelControlNode findCompanionNodeLocked(KernelControlNode control, int targetType) {
        for (int i = 0; i < mControls.size(); i++) {
            KernelControlNode other = mControls.valueAt(i);
            if (other != null && other != control && other.type == targetType
                    && TextUtils.equals(other.group, control.group)) {
                return other;
            }
        }
        return null;
    }

    private void refreshControls() {
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                refreshControlsLocked();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void refreshControlsLocked() {
        ArrayList<KernelControlNode> controls = new ArrayList<>();
        AxKernelMetricsReader.Config metricsConfig = new AxKernelMetricsReader.Config();
        AxKernelConfigLoader.load(controls, metricsConfig);
        mControls.clear();
        mUriToControl.clear();
        for (KernelControlNode control : controls) {
            if (control == null || TextUtils.isEmpty(control.id)) continue;
            mControls.put(control.id, control);
            Uri uri = Settings.Secure.getUriFor(control.id);
            if (uri != null) {
                mUriToControl.put(uri, control);
            }
        }
        mMetricsReader.setConfig(metricsConfig);
        publishMetadata(controls);
    }

    private void applyPersistedValues() {
        ArrayList<KernelControlNode> controls;
        synchronized (mLock) {
            controls = new ArrayList<>(mControls.values());
        }
        for (KernelControlNode control : controls) {
            restoreControlValue(control);
        }
    }

    private void restoreControlValue(KernelControlNode control) {
        if (control == null || TextUtils.isEmpty(control.id)) return;
        int value = Settings.Secure.getIntForUser(mResolver, control.id, Integer.MIN_VALUE, UserHandle.USER_CURRENT);
        if (value == Integer.MIN_VALUE || !control.canUse()) return;
        synchronized (mLock) {
            applyCoordinatedControlValueLocked(control, value);
        }
    }

    private void notifyPowerManager(KernelControlNode control, int value, boolean isOverride) {
        if (control == null || TextUtils.isEmpty(control.path)) return;
        PowerManagerInternal pmi = LocalServices.getService(PowerManagerInternal.class);
        if (pmi == null) return;
        if (!isOverride) {
            pmi.clearNodeCeiling(control.path);
            return;
        }
        long rawValue = parseRawValue(control, value);
        long maxCeiling = isMaxFreq(control.type) ? rawValue : 0L;
        long minFloor = isMinFreq(control.type) ? rawValue : 0L;
        pmi.setNodeCeiling(control.path, maxCeiling, minFloor);
    }

    private static long parseRawValue(KernelControlNode control, int value) {
        String fileVal = control.toFileValue(value);
        try {
            return Long.parseLong(fileVal);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static boolean isMaxFreq(int type) {
        return type == AxKernelControl.TYPE_CPU_MAX_FREQ || type == AxKernelControl.TYPE_GPU_MAX_FREQ;
    }

    private static boolean isMinFreq(int type) {
        return type == AxKernelControl.TYPE_CPU_MIN_FREQ || type == AxKernelControl.TYPE_GPU_MIN_FREQ;
    }

    private static void tryWrite(KernelControlNode control, int value) {
        try {
            control.writeValue(value);
        } catch (IOException | IllegalArgumentException e) {
            Slog.w(TAG, "Failed to write " + control.id, e);
        }
    }

    private KernelControlNode findControl(String id) {
        synchronized (mLock) {
            return mControls.get(id);
        }
    }

    private ArrayList<KernelControlNode> getControlsList() {
        long token = Binder.clearCallingIdentity();
        try {
            return fetchControlsList();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private ArrayList<KernelControlNode> fetchControlsList() {
        synchronized (mLock) {
            return new ArrayList<>(mControls.values());
        }
    }

    private void publishMetadata(ArrayList<KernelControlNode> controls) {
        ArrayMap<String, int[]> cpuFreqs = new ArrayMap<>();
        int[] gpuFreqs = null;
        for (KernelControlNode control : controls) {
            if (control.type == AxKernelControl.TYPE_CPU_MIN_FREQ) {
                cpuFreqs.put(control.group, control.availableValues);
            } else if (control.type == AxKernelControl.TYPE_GPU_MIN_FREQ) {
                gpuFreqs = control.availableValues;
            }
        }
        Settings.Secure.putIntForUser(mResolver, SETTING_CPU_CLUSTER_COUNT, cpuFreqs.size(), UserHandle.USER_CURRENT);
        for (int i = 0; i < cpuFreqs.size(); i++) {
            int[] freqs = cpuFreqs.valueAt(i);
            String joined = join(freqs);
            Settings.Secure.putStringForUser(mResolver, SETTING_CPU_CLUSTER_PREFIX + i + SETTING_CPU_CLUSTER_SUFFIX, joined, UserHandle.USER_CURRENT);
            saveClusterFreqs(i, cpuFreqs.size(), joined);
        }
        if (gpuFreqs != null) {
            Settings.Secure.putStringForUser(mResolver, SETTING_GPU_FREQS, join(gpuFreqs), UserHandle.USER_CURRENT);
        }
    }

    private void saveClusterFreqs(int index, int totalClusters, String joined) {
        if (index == INDEX_SMALL_CLUSTER) {
            Settings.Secure.putStringForUser(mResolver, SETTING_CPU_SMALL_FREQS, joined, UserHandle.USER_CURRENT);
        }
        if (index == INDEX_BIG_CLUSTER) {
            Settings.Secure.putStringForUser(mResolver, SETTING_CPU_BIG_FREQS, joined, UserHandle.USER_CURRENT);
        }
        if (index == totalClusters - 1) {
            Settings.Secure.putStringForUser(mResolver, SETTING_CPU_PRIME_FREQS, joined, UserHandle.USER_CURRENT);
        }
    }

    private static String join(int[] values) {
        if (values == null || values.length == 0) return EMPTY_STRING;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(DELIMITER_SPACE);
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri == null) return;
            synchronized (mLock) {
                KernelControlNode target = mUriToControl.get(uri);
                if (target == null || TextUtils.isEmpty(target.id)) return;
                int value = Settings.Secure.getIntForUser(mResolver, target.id, Integer.MIN_VALUE, UserHandle.USER_CURRENT);
                if (value == Integer.MIN_VALUE) {
                    applyControlUpdate(target, target.defaultValue, false);
                    return;
                }
                applyCoordinatedControlValueLocked(target, value);
            }
        }
    }
}
