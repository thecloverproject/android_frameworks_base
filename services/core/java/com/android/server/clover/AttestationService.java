/*
 * Copyright (C) 2024 The LeafOS Project
 * Copyright (C) 2024-2025 crDroid Android Project
 * Copyright (C) 2024-2026 The Clover Project
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.android.server.clover;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Environment;
import android.os.SELinux;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.util.AtomicFile;
import android.util.Log;

import com.android.server.SystemService;
import com.android.internal.util.clover.CloverUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONException;
import org.json.JSONObject;

public final class AttestationService extends SystemService {

    private static final String TAG = AttestationService.class.getSimpleName();

    private static final String API = "https://raw.githubusercontent.com/The-Clover-Project/vendor_certification/refs/heads/main/gms_certified_props.json";
    private static final String DATA_FILE = "gms_certified_props.json";
    private static final long INITIAL_DELAY = 0; // Start immediately on boot
    private static final long INTERVAL = 8; // Interval in hours
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final ScheduledExecutorService mScheduler;
    private final ConnectivityManager mConnectivityManager;
    private final FetchGmsCertifiedProps mFetchRunnable;

    private boolean mPendingUpdate;

    private final AtomicBoolean mFetchScheduledByNetwork = new AtomicBoolean(false);
    private volatile long mLastSuccessFetchMs = 0L;
    private static final long MIN_REFETCH_MS = TimeUnit.MINUTES.toMillis(5);

    public AttestationService(Context context) {
        super(context);
        mContext = context;
        mFetchRunnable = new FetchGmsCertifiedProps();
        mScheduler = Executors.newSingleThreadScheduledExecutor();
        mConnectivityManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        registerNetworkCallback();
    }

    @Override
    public void onStart() {}

    @Override
    public void onBootPhase(int phase) {
        if (CloverUtils.isPackageInstalled(mContext, "com.google.android.gms")
                && phase == PHASE_BOOT_COMPLETED) {
            Log.i(TAG, "Scheduling the service");
            mScheduler.scheduleAtFixedRate(
                    mFetchRunnable, INITIAL_DELAY, INTERVAL, TimeUnit.HOURS);
        }
    }

    private String fetchProps() {
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URI(API).toURL();
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(10000);
            urlConnection.setReadTimeout(10000);
            urlConnection.setRequestProperty("User-Agent", "AttestationService/1.0");

            int code = urlConnection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Bad HTTP status: " + code);
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(urlConnection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                return response.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error making an API request", e);
            return null;
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }

    private void dlog(String message) {
        if (DEBUG) Log.d(TAG, message);
    }

    private boolean isInternetConnected() {
        Network network = mConnectivityManager.getActiveNetwork();
        if (network != null) {
            NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        return false;
    }

    private void registerNetworkCallback() {
        mConnectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Internet is available, resuming update");
                if (!mPendingUpdate) return;
                long now = System.currentTimeMillis();
                if (now - mLastSuccessFetchMs < MIN_REFETCH_MS) return;
                if (mFetchScheduledByNetwork.compareAndSet(false, true)) {
                    mScheduler.schedule(() -> {
                        try {
                            mFetchRunnable.run();
                        } finally {
                            mFetchScheduledByNetwork.set(false);
                        }
                    }, 0, TimeUnit.SECONDS);
                }
            }
        });
    }

    private class FetchGmsCertifiedProps implements Runnable {
        @Override
        public void run() {
            if (Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.PI_ENABLE_SPOOF, 1) != 1) {
                mPendingUpdate = false;
                return;
            }

            try {
                dlog("FetchGmsCertifiedProps started");

                if (!isInternetConnected()) {
                    Log.e(TAG, "Internet is unavailable, deferring update");
                    mPendingUpdate = true;
                    return;
                }
                mPendingUpdate = false;

                String savedProps = Settings.Secure.getString(mContext.getContentResolver(), Settings.Secure.FETCHED_PIF);
                String props = fetchProps();
                if (props != null) {
                    try {
                        new JSONObject(props);
                    } catch (JSONException je) {
                        Log.e(TAG, "Fetched props are not valid JSON, keeping existing file", je);
                        return;
                    }
                }
                if (props != null && !savedProps.equals(props)) {
                    dlog("Found new props");
                    Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.FETCHED_PIF, props);
                    mLastSuccessFetchMs = System.currentTimeMillis();
                    dlog("FetchGmsCertifiedProps completed");
                } else {
                    dlog("No change in props");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in FetchGmsCertifiedProps", e);
            }
        }
    }
}
