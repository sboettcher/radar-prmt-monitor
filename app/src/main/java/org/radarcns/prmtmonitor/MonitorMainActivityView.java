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

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.LegendRenderer;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import org.apache.avro.JsonProperties;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.data.TimedInt;
import org.radarcns.prmtmonitor.kafka.ServerStatusListener;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

public class MonitorMainActivityView implements Runnable, MainActivityView, AdapterView.OnItemSelectedListener {
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

    // graphing
    private GraphView mDataGraph;
    private HashMap<String, LineGraphSeries> mDataSeries;

    private Spinner mGraphSourceSpinner;
    private ArrayAdapter mGraphSourceAdapter;
    private String mGraphSourceSelection;
    private Spinner mGraphTopicSpinner;
    private ArrayAdapter mGraphTopicAdapter;
    private String mGraphTopicSelection;

    private int mLastDataSize;

    private int[] primaryColors = {0xFFD50000, 0xFF00C853, 0xFF2962FF};


    MonitorMainActivityView(MonitorMainActivity activity) {
        this.mainActivity = activity;
        this.previousUserId = "";
        this.savedConnections = new HashSet<>();

        mLastDataSize = 0;

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

            mGraphSourceAdapter.clear();
            mGraphSourceAdapter.add("[NONE]");
            mGraphSourceAdapter.addAll(sortedConnections);

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

        mDataGraph = mainActivity.findViewById(R.id.graph_view);
        mDataSeries = new HashMap<>();

        mGraphSourceSpinner = mainActivity.findViewById(R.id.graph_conn_spinner);
        mGraphTopicSpinner = mainActivity.findViewById(R.id.graph_topic_spinner);

        mGraphSourceAdapter = new ArrayAdapter(mainActivity, android.R.layout.simple_spinner_item);
        mGraphSourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mGraphSourceSpinner.setAdapter(mGraphSourceAdapter);

        // Create an ArrayAdapter using the string array and a default spinner layout
        mGraphTopicAdapter = ArrayAdapter.createFromResource(mainActivity, R.array.graph_topic_array, android.R.layout.simple_spinner_item);
        // Specify the layout to use when the list of choices appears
        mGraphTopicAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner
        mGraphTopicSpinner.setAdapter(mGraphTopicAdapter);

        mGraphSourceSpinner.setOnItemSelectedListener(this);
        mGraphTopicSpinner.setOnItemSelectedListener(this);
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
        updateGraph();
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

    private void updateGraph() {
        if (mGraphSourceSelection == null || mGraphTopicSelection == null) {
            return;
        }

        ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>> data = rows.get(mGraphSourceSelection).getDataForTopic(mGraphTopicSelection);

        if (data == null || data.isEmpty() || data.size() == mLastDataSize) return;

        mLastDataSize = data.size();

        if (mDataSeries.isEmpty())
            resetSeries();

        for (String line : mDataSeries.keySet()) {
            DataPoint[] lineData = new DataPoint[data.size()];

            try {
                for (int i = 0; i < data.size(); i++) {
                    if (data.get(i) != null)
                        lineData[i] = new DataPoint(i, data.get(i).getValue().getDouble(line));
                    else
                        lineData[i] = new DataPoint(i, 0);
                }
            } catch (JSONException ex) {
                continue;
            }

            mDataSeries.get(line).resetData(lineData);
            mDataGraph.getViewport().scrollToEnd();
        }

    }

    private void resetSeries() {
        if (mGraphSourceSelection == null || mGraphTopicSelection == null) {
            return;
        }

        mDataGraph.removeAllSeries();

        mDataGraph.getViewport().setScrollable(true);
        //mDataGraph.getViewport().setScalable(true);

        mDataGraph.getViewport().setXAxisBoundsManual(true);
        mDataGraph.getViewport().setMinX(0);
        mDataGraph.getViewport().setMaxX(2000);

        mDataSeries.clear();
        ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>> data = rows.get(mGraphSourceSelection).getDataForTopic(mGraphTopicSelection);
        if (data == null || data.isEmpty()) return;

        Iterator<String> iter = data.get(0).getValue().keys();
        int colorInd = 0;
        while (iter.hasNext()) {
            String key = iter.next();
            if (key.equals("time") || key.equals("timeReceived"))
                continue;
            mDataSeries.put(key, new LineGraphSeries());
            mDataSeries.get(key).setColor(colorInd < primaryColors.length ? primaryColors[colorInd] : getRandColor());
            mDataSeries.get(key).setTitle(key);
            mDataGraph.addSeries(mDataSeries.get(key));
            colorInd++;
        }

        mDataGraph.getLegendRenderer().setVisible(true);
        mDataGraph.getLegendRenderer().setAlign(LegendRenderer.LegendAlign.TOP);
        mDataGraph.getLegendRenderer().setBackgroundColor(Color.argb(100,100,100,100));
    }

    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        if (parent.equals(mGraphSourceSpinner)) {
            mGraphSourceSelection = (String) parent.getItemAtPosition(pos);
        } else if (parent.equals(mGraphTopicSpinner)) {
            mGraphTopicSelection = (String) parent.getItemAtPosition(pos);
        }

        if (mGraphSourceSelection != null && mGraphSourceSelection.equals("[NONE]")) mGraphSourceSelection = null;

        resetSeries();
    }

    public void onNothingSelected(AdapterView<?> parent) {
        mDataGraph.removeAllSeries();
    }


    private int getRandColor() {
        Random rand = new Random();
        float r = rand.nextFloat();
        float g = rand.nextFloat();
        float b = rand.nextFloat();
        return Color.argb(255,(int)(r*255),(int)(g*255),(int)(b*255));
    }

}
