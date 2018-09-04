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

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.radarcns.data.TimedInt;
import org.radarcns.prmtmonitor.kafka.ServerStatusListener;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class MonitorMainActivityView implements Runnable, MainActivityView {
    private static final DateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final static Map<ServerStatusListener.Status, Integer> serverStatusIconMap;
    private final static int serverStatusIconDefault = R.drawable.status_searching;
    static {
        serverStatusIconMap = new EnumMap<>(ServerStatusListener.Status.class);
        serverStatusIconMap.put(ServerStatusListener.Status.CONNECTED, R.drawable.status_connected);
        serverStatusIconMap.put(ServerStatusListener.Status.DISCONNECTED, R.drawable.status_disconnected);
        serverStatusIconMap.put(ServerStatusListener.Status.READY, R.drawable.status_searching);
        serverStatusIconMap.put(ServerStatusListener.Status.CONNECTING, R.drawable.status_searching);
    }

    private final MonitorMainActivity mainActivity;
    private final Map<String,DeviceRowView> rows = new HashMap<>();
    private HashSet<String> savedConnections;

    private long previousTimestamp;
    private volatile String newServerStatus;
    private volatile ServerStatusListener.Status newConnectionStatus;

    // View elements
    private TextView mServerMessage;
    private View mServerStatus;

    private TextView mUserId;
    private String userId;
    private String previousUserId;

    private TextView mProjectId;
    private String projectId;
    private String previousProjectId;

    private TextView mServerUrl;
    private String serverUrl;
    private String previousServerUrl;

    MonitorMainActivityView(MonitorMainActivity activity) {
        this.mainActivity = activity;
        this.previousUserId = "";
        this.savedConnections = new HashSet<>();

        initializeViews();

        createRows();
    }

    private void createRows() {
        createRows(false);
    }
    private void createRows(boolean force) {
        final Set tmp = mainActivity.getRadarService().getDataReader().getConnections();
        final HashSet<String> newConnections = new HashSet<String>(tmp);
        //newConnections.add("TEST");
        if (force || (mainActivity.getRadarService() != null && !this.savedConnections.equals(newConnections))) {
            // clear table
            ViewGroup root = mainActivity.findViewById(R.id.deviceTable);
            root.removeAllViews();
            rows.clear();

            // sort connections
            final List sortedConnections = new ArrayList(newConnections);
            Collections.sort(sortedConnections);

            // add connections
            for (Object connection : sortedConnections) {
                rows.put((String) connection, new DeviceRowView(mainActivity, (String) connection, root));
            }
            this.savedConnections = newConnections;
        }
    }

    public void update() {
        //createRows();

        userId = mainActivity.getUserId();
        projectId = mainActivity.getProjectId();
        serverUrl = mainActivity.getServerUrl();
        for (Map.Entry<String,DeviceRowView> row : rows.entrySet()) {
            HashMap topicData = mainActivity.getRadarService().getDataReader().getTopicData(row.getKey());
            if (!topicData.isEmpty())
                row.getValue().update(topicData);
        }
        if (mainActivity.getRadarService() != null) {
            newServerStatus = getServerStatusMessage();
            newConnectionStatus = getConnectionStatus();
        }
        mainActivity.runOnUiThread(this);
    }

    private String getServerStatusMessage() {
        TimedInt numberOfRecords = mainActivity.getRadarService().getLatestNumberOfRecordsRead();

        String message = null;
        if (numberOfRecords != null && numberOfRecords.getTime() >= 0 && previousTimestamp != numberOfRecords.getTime()) {
            previousTimestamp = numberOfRecords.getTime();

            String messageTimeStamp = timeFormat.format(numberOfRecords.getTime());

            if (numberOfRecords.getValue() < 0) {
                message = String.format(Locale.US, "last download failed at %1$s", messageTimeStamp);
            } else {
                message = String.format(Locale.US, "last download at %1$s", messageTimeStamp);
            }
        }
        return message;
    }

    private ServerStatusListener.Status getConnectionStatus() {
        return mainActivity.getRadarService().getServerStatus();
    }

    private void initializeViews() {
        mainActivity.setContentView(R.layout.compact_overview);

        mServerMessage = mainActivity.findViewById(R.id.statusServerMessage);
        mServerStatus = mainActivity.findViewById(R.id.conn_status_icon);

        mUserId = mainActivity.findViewById(R.id.inputUserId);
        mProjectId = mainActivity.findViewById(R.id.inputProjectId);
        mServerUrl = mainActivity.findViewById(R.id.inputServerUrl);
    }

    @Override
    public void run() {
        createRows();
        ViewGroup root = mainActivity.findViewById(R.id.deviceTable);
        if (rows.size() != savedConnections.size() || root.getChildCount() != savedConnections.size())
            createRows(true);
        for (DeviceRowView row : rows.values()) {
            row.display();
        }
        updateServerStatus();
        setUserId();
    }

    private void updateServerStatus() {
        String message = newServerStatus;
        if (message != null) {
            mServerMessage.setText(message);
        }

        ServerStatusListener.Status status = newConnectionStatus;
        if (status != null) {
            Integer statusIcon = serverStatusIconMap.get(status);
            int resource = statusIcon != null ? statusIcon : serverStatusIconDefault;
            mServerStatus.setBackgroundResource(resource);
        }
    }

    private void setUserId() {
        if (!Objects.equals(userId, previousUserId)) {
            if (userId == null) {
                mUserId.setVisibility(View.GONE);
            } else {
                if (previousUserId == null) {
                    mUserId.setVisibility(View.VISIBLE);
                }
                mUserId.setText(mainActivity.getString(R.string.user_id_message, userId));
            }
            previousUserId = userId;
        }

        if (!Objects.equals(projectId, previousProjectId)) {
            if (projectId == null) {
                mProjectId.setVisibility(View.GONE);
            } else {
                if (previousProjectId == null) {
                    mProjectId.setVisibility(View.VISIBLE);
                }
                mProjectId.setText(mainActivity.getString(R.string.study_id_message, projectId));
            }
            previousProjectId = projectId;
        }

        if (!Objects.equals(serverUrl, previousServerUrl)) {
            if (serverUrl == null) {
                mServerUrl.setVisibility(View.GONE);
            } else {
                if (previousServerUrl == null) {
                    mServerUrl.setVisibility(View.VISIBLE);
                }
                mServerUrl.setText(mainActivity.getString(R.string.server_url_message, serverUrl));
            }
            previousServerUrl = serverUrl;
        }
    }
}
