/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.prmtmonitor;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;

import org.radarcns.android.RadarConfiguration;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.util.BundleSerialization;
import org.radarcns.config.ServerConfig;
import org.radarcns.data.TimedInt;
import org.radarcns.passive.empatica.EmpaticaE4Acceleration;
import org.radarcns.passive.phone.PhoneAcceleration;
import org.radarcns.prmtmonitor.consumer.KafkaReader;
import org.radarcns.prmtmonitor.consumer.RestReader;
import org.radarcns.prmtmonitor.kafka.KafkaDataReader;
import org.radarcns.prmtmonitor.kafka.ServerStatusListener;
import org.radarcns.producer.rest.SchemaRetriever;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.Manifest.permission.INTERNET;
import static android.Manifest.permission.PACKAGE_USAGE_STATS;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static org.radarcns.android.RadarConfiguration.KAFKA_REST_PROXY_URL_KEY;
import static org.radarcns.android.RadarConfiguration.RADAR_CONFIGURATION_CHANGED;
import static org.radarcns.android.RadarConfiguration.SCHEMA_REGISTRY_URL_KEY;
import static org.radarcns.android.RadarConfiguration.UNSAFE_KAFKA_CONNECTION;
import static org.radarcns.android.auth.portal.GetSubjectParser.getHumanReadableUserId;
import static org.radarcns.android.auth.portal.ManagementPortalClient.MP_REFRESH_TOKEN_PROPERTY;
import static org.radarcns.android.device.DeviceService.SERVER_STATUS_CHANGED;

@SuppressWarnings("unused")
public class RadarService extends Service implements ServerStatusListener {
    private static final Logger logger = LoggerFactory.getLogger(RadarService.class);

    public static String RADAR_PACKAGE = RadarService.class.getPackage().getName();

    public static String EXTRA_MAIN_ACTIVITY = RADAR_PACKAGE + ".EXTRA_MAIN_ACTIVITY";
    public static String EXTRA_LOGIN_ACTIVITY = RADAR_PACKAGE + ".EXTRA_LOGIN_ACTIVITY";

    public static String ACTION_CHECK_PERMISSIONS = RADAR_PACKAGE + ".ACTION_CHECK_PERMISSIONS";
    public static String EXTRA_PERMISSIONS = RADAR_PACKAGE + ".EXTRA_PERMISSIONS";

    public static String ACTION_PERMISSIONS_GRANTED = RADAR_PACKAGE + ".ACTION_PERMISSIONS_GRANTED";
    public static String EXTRA_GRANT_RESULTS = RADAR_PACKAGE + ".EXTRA_GRANT_RESULTS";

    private final BroadcastReceiver permissionsBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onPermissionsGranted(intent.getStringArrayExtra(EXTRA_PERMISSIONS), intent.getIntArrayExtra(EXTRA_GRANT_RESULTS));
        }
    };

    private IBinder binder;

    private KafkaDataReader dataReader;
    private String mainActivityClass;
    private HandlerThread mHandlerThread;
    private Handler mHandler;


    private final BroadcastReceiver serverStatusReceiver = new BroadcastReceiver() {
        AtomicBoolean isMakingRequest = new AtomicBoolean(false);
        @Override
        public void onReceive(Context context, Intent intent) {
            serverStatus = Status.values()[intent.getIntExtra(SERVER_STATUS_CHANGED, -1)];
            if (serverStatus == Status.UNAUTHORIZED) {
                logger.info("Status unauthorized");
                if (!isMakingRequest.compareAndSet(false, true)) {
                    return;
                }
                final String refreshToken = (String) authState.getProperty(MP_REFRESH_TOKEN_PROPERTY);
                synchronized (RadarService.this) {
                    // login already started, or was finished up to 3 seconds ago (give time to propagate new auth state.)
                    if (authState.isInvalidated() || authState.timeSinceLastUpdate() < 3_000L) {
                        return;
                    }
                    authState.invalidate(RadarService.this);
                }
            }
        }
    };

    private final BroadcastReceiver configChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            configure();
        }
    };

    /** An overview of how many records have been read throughout the application. */
    private final TimedInt latestNumberOfRecordsRead = new TimedInt();

    /** Current server status. */
    private Status serverStatus;
    private AppAuthState authState;

    private final LinkedHashSet<String> needsPermissions = new LinkedHashSet<>();


    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        binder = createBinder();

        mHandlerThread = new HandlerThread("kafka-service-handler", THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        registerReceiver(permissionsBroadcastReceiver,
                new IntentFilter(ACTION_PERMISSIONS_GRANTED));
        registerReceiver(serverStatusReceiver, new IntentFilter(SERVER_STATUS_CHANGED));
        registerReceiver(configChangedReceiver, new IntentFilter(RADAR_CONFIGURATION_CHANGED));
    }

    protected IBinder createBinder() {
        return new RadarBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Bundle extras = BundleSerialization.getPersistentExtras(intent, this);
        extras.setClassLoader(RadarService.class.getClassLoader());
        mainActivityClass = extras.getString(EXTRA_MAIN_ACTIVITY);

        if (intent == null) {
            authState = AppAuthState.Builder.from(this).build();
        } else {
            authState = AppAuthState.Builder.from(extras).build();
        }
        logger.info("Auth state: {}", authState);
        updateAuthState(authState);

        configure();

        checkPermissions();

        startForeground(1,
                new Notification.Builder(this)
                        .setContentTitle("RADAR")
                        .setContentText("Open RADAR app")
                        .setContentIntent(PendingIntent.getActivity(this, 0, new Intent().setComponent(new ComponentName(this, mainActivityClass)), 0))
                        .build());

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    //dataReader.addTopics(Collections.<AvroTopic>emptySet());
                    Set<AvroTopic> topics = new HashSet<>();
                    topics.add(dataReader.createTopic("android_phone_acceleration", PhoneAcceleration.class));
                    topics.add(dataReader.createTopic("android_empatica_e4_acceleration", EmpaticaE4Acceleration.class));
                    dataReader.addTopics(topics);
                } catch (IOException ex) {
                    logger.error("KafkaDataReader failed!", ex);
                }
            }
        });

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        dataReader.close();

        mHandler = null;
        mHandlerThread.quitSafely();
        unregisterReceiver(permissionsBroadcastReceiver);
        unregisterReceiver(serverStatusReceiver);

        super.onDestroy();
    }

    protected void configure() {
        RadarConfiguration configuration = RadarConfiguration.getInstance();

        ServerConfig kafkaConfig = null;
        SchemaRetriever remoteSchemaRetriever = null;
        boolean unsafeConnection = configuration.getBoolean(UNSAFE_KAFKA_CONNECTION, false);

        if (configuration.has(KAFKA_REST_PROXY_URL_KEY)) {
            String urlString = configuration.getString(KAFKA_REST_PROXY_URL_KEY);
            if (!urlString.isEmpty()) {
                try {
                    ServerConfig schemaRegistry = new ServerConfig(configuration.getString(SCHEMA_REGISTRY_URL_KEY));
                    schemaRegistry.setUnsafe(unsafeConnection);
                    remoteSchemaRetriever = new SchemaRetriever(schemaRegistry, 30);
                    kafkaConfig = new ServerConfig(urlString);
                    kafkaConfig.setUnsafe(unsafeConnection);
                } catch (MalformedURLException ex) {
                    logger.error("Malformed Kafka server URL {}", urlString);
                    throw new IllegalArgumentException(ex);
                }
            }
        }

        //boolean sendOnlyWithWifi = configuration.getBoolean(SEND_ONLY_WITH_WIFI, true);
        //int maxBytes = configuration.getInt(MAX_CACHE_SIZE, Integer.MAX_VALUE);

        logger.error("DEBUG READER");

        KafkaReader reader = new RestReader.Builder()
                .server(kafkaConfig)
                .schemaRetriever(remoteSchemaRetriever)
                .connectionTimeout(10, TimeUnit.SECONDS)
                .useCompression(false)
                .headers(authState.getOkHttpHeaders())
                .build();
        dataReader = new KafkaDataReader(this, reader, 100, 10, "TABDEV");
    }




    public KafkaDataReader getDataReader() {
        return dataReader;
    }


    protected void requestPermissions(String[] permissions) {
        startActivity(new Intent()
                .setComponent(new ComponentName(this, mainActivityClass))
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setAction(ACTION_CHECK_PERMISSIONS)
                .putExtra(EXTRA_PERMISSIONS, permissions));
    }

    protected void onPermissionsGranted(String[] permissions, int[] grantResults) {
        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                logger.info("Granted permission {}", permissions[i]);
                needsPermissions.remove(permissions[i]);
            } else {
                logger.info("Denied permission {}", permissions[i]);
                return;
            }
        }
    }


    protected void updateAuthState(AppAuthState authState) {
        this.authState = authState;
        RadarConfiguration.getInstance().put(RadarConfiguration.PROJECT_ID_KEY, authState.getProjectId());
        RadarConfiguration.getInstance().put(RadarConfiguration.USER_ID_KEY, getHumanReadableUserId(authState));
        configure();
    }

    public void updateServerStatus(Status serverStatus) {
        if (serverStatus == this.serverStatus) {
            return;
        }
        this.serverStatus = serverStatus;

        Intent statusIntent = new Intent(SERVER_STATUS_CHANGED);
        statusIntent.putExtra(SERVER_STATUS_CHANGED, serverStatus.ordinal());
        sendBroadcast(statusIntent);
    }

    @Override
    public void updateRecordsRead(String topicName, int numberOfRecords) {
        this.latestNumberOfRecordsRead.set(numberOfRecords);
        //Intent recordsIntent = new Intent(SERVER_RECORDS_SENT_TOPIC);
        // Signal that a certain topic changed, the key of the map retrieved by getRecordsSent().
        //recordsIntent.putExtra(SERVER_RECORDS_SENT_TOPIC, topicName);
        //recordsIntent.putExtra(SERVER_RECORDS_SENT_NUMBER, numberOfRecords);
        //sendBroadcast(recordsIntent);
    }


    protected void checkPermissions() {
        Set<String> permissions = new HashSet<>(getServicePermissions());
        //for (DeviceServiceProvider<?> provider : mConnections) {
        //    permissions.addAll(provider.needsPermissions());
        //}

        needsPermissions.clear();

        for (String permission : permissions) {
            if (permission.equals(ACCESS_FINE_LOCATION) || permission.equals(ACCESS_COARSE_LOCATION)) {
                LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (locationManager != null) {
                    boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                    boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

                    //Start your Activity if location was enabled:
                    if (!isGpsEnabled && !isNetworkEnabled) {
                        //needsPermissions.add(LOCATION_SERVICE);
                        //needsPermissions.add(permission);
                    }
                }
            }

            if (permission.equals(PACKAGE_USAGE_STATS)) {
                AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
                if (appOps != null) {
                    int mode = appOps.checkOpNoThrow(
                            "android:get_usage_stats", android.os.Process.myUid(), getPackageName());

                    if (mode != AppOpsManager.MODE_ALLOWED) {
                        needsPermissions.add(permission);
                    }
                }
            } else if (ContextCompat.checkSelfPermission(this, permission) != PackageManager
                    .PERMISSION_GRANTED) {
                logger.info("Need to request permission for {}", permission);
                needsPermissions.add(permission);
            }
        }

        if (!needsPermissions.isEmpty()) {
            requestPermissions(needsPermissions.toArray(new String[needsPermissions.size()]));
        }
    }

    protected List<String> getServicePermissions() {
        return Arrays.asList(ACCESS_NETWORK_STATE, INTERNET);
    }


    /** Configure whether a boot listener should start this application at boot. */
    protected void configureRunAtBoot(@NonNull Class<?> bootReceiver) {
        ComponentName receiver = new ComponentName(
                getApplicationContext(), bootReceiver);
        PackageManager pm = getApplicationContext().getPackageManager();

        boolean startAtBoot = RadarConfiguration.getInstance().getBoolean(RadarConfiguration.START_AT_BOOT, false);
        boolean isStartedAtBoot = pm.getComponentEnabledSetting(receiver) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        if (startAtBoot && !isStartedAtBoot) {
            logger.info("From now on, this application will start at boot");
            pm.setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } else if (!startAtBoot && isStartedAtBoot) {
            logger.info("Not starting application at boot anymore");
            pm.setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }

    protected class RadarBinder extends Binder implements IRadarService {
        @Override
        public Status getServerStatus() {
             return serverStatus;
        }

        @Override
        public TimedInt getLatestNumberOfRecordsRead() {
            return latestNumberOfRecordsRead;
        }

        @Override
        public AppAuthState getAuthState() {
            return authState;
        }
    }

    /*
    private static class AsyncBindServices extends AsyncTask<DeviceServiceProvider, Void, Void> {
        private final boolean unbindFirst;

        AsyncBindServices(boolean unbindFirst) {
            this.unbindFirst = unbindFirst;
        }

        @Override
        protected Void doInBackground(DeviceServiceProvider... params) {
            for (DeviceServiceProvider provider : params) {
                if (provider == null) {
                    continue;
                }
                if (unbindFirst) {
                    logger.info("Rebinding {} after disconnect", provider);
                    if (provider.isBound()) {
                        provider.unbind();
                    }
                }
                if (!provider.isBound()) {
                    logger.info("Binding to service: {}", provider);
                    provider.bind();
                } else {
                    logger.info("Already bound: {}", provider);
                }
            }
            return null;
        }
    }
    */
}
