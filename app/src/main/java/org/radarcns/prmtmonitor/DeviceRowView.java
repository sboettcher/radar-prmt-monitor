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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TableRow;
import android.widget.TextView;

import org.apache.avro.JsonProperties;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.android.device.DeviceStatusListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.radarcns.prmtmonitor.RadarService.deviceLabels;

/**
 * Displays a single device row.
 */
public class DeviceRowView {
    private static final Logger logger = LoggerFactory.getLogger(DeviceRowView.class);
    private static final int MAX_UI_DEVICE_NAME_LENGTH = 25;

    private final static Map<DeviceStatusListener.Status, Integer> deviceStatusIconMap;
    private final static int deviceStatusIconDefault = R.drawable.status_searching;

    static {
        deviceStatusIconMap = new EnumMap<>(DeviceStatusListener.Status.class);
        deviceStatusIconMap.put(DeviceStatusListener.Status.CONNECTED, R.drawable.status_connected);
        deviceStatusIconMap.put(DeviceStatusListener.Status.DISCONNECTED, R.drawable.status_disconnected);
        deviceStatusIconMap.put(DeviceStatusListener.Status.READY, R.drawable.status_searching);
        deviceStatusIconMap.put(DeviceStatusListener.Status.CONNECTING, R.drawable.status_searching);
    }

    private final MainActivity mainActivity;

    private final String connection;
    private String previousName;
    private final TextView mConnectionNameLabel;

    private ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>> mTabData;
    private ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>> mTabBat;
    private ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>> mE4Data;
    private ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>> mE4Bat;
    private ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>> mBiovData;
    private ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>> mBiovBat;

    private final View mTabStatusIcon;
    private final ImageView mTabBatteryLabel;
    private final TextView mTabBatteryValue;
    private final TextView mTabLastStatus;

    private final View mE4StatusIcon;
    private final ImageView mE4BatteryLabel;
    private final TextView mE4BatteryValue;
    private final TextView mE4LastStatus;

    private final View mBiovStatusIcon;
    private final ImageView mBiovBatteryLabel;
    private final TextView mBiovBatteryValue;
    private final TextView mBiovLastStatus;

    private DeviceStatusListener.Status prevTabStatus = null;
    private float prevTabBatteryLevel = Float.NaN;
    private DeviceStatusListener.Status prevE4Status = null;
    private float prevE4BatteryLevel = Float.NaN;
    private DeviceStatusListener.Status prevBiovStatus = null;
    private float prevBiovBatteryLevel = Float.NaN;

    private double lastTabStatus = 0;
    private double lastE4Status = 0;
    private double lastBiovStatus = 0;
    private String lastE4Label = "";
    private String lastBiovLabel = "";

    DeviceRowView(MainActivity mainActivity, String connection, ViewGroup root) {
        this.mainActivity = mainActivity;
        this.connection = connection;
        logger.info("Creating row for connection {}", connection);
        LayoutInflater inflater = (LayoutInflater) this.mainActivity.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.activity_overview_device_row_ext, root);
        TableRow row = (TableRow) root.getChildAt(root.getChildCount() - 1);
        mConnectionNameLabel = row.findViewById(R.id.connectionName_label);

        mTabData = new ArrayList<>();
        mTabBat = new ArrayList<>();
        mE4Data = new ArrayList<>();
        mE4Bat = new ArrayList<>();
        mBiovData = new ArrayList<>();
        mBiovBat = new ArrayList<>();

        mTabStatusIcon = row.findViewById(R.id.tab_status_icon);
        mTabBatteryLabel = row.findViewById(R.id.tab_battery_label);
        mTabBatteryValue = row.findViewById(R.id.tab_battery_value);
        mTabLastStatus = row.findViewById(R.id.tab_secondary);

        mE4StatusIcon = row.findViewById(R.id.e4_status_icon);
        mE4BatteryLabel = row.findViewById(R.id.e4_battery_label);
        mE4BatteryValue = row.findViewById(R.id.e4_battery_value);
        mE4LastStatus = row.findViewById(R.id.e4_secondary);

        mBiovStatusIcon = row.findViewById(R.id.biov_status_icon);
        mBiovBatteryLabel = row.findViewById(R.id.biov_battery_label);
        mBiovBatteryValue = row.findViewById(R.id.biov_battery_value);
        mBiovLastStatus = row.findViewById(R.id.biov_secondary);

        mConnectionNameLabel.setText(connection);
    }

    void update(HashMap<String, ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>>> topicData) {
        mTabData = topicData.get("android_phone_acceleration") == null ? null : new ArrayList<>(topicData.get("android_phone_acceleration"));
        mTabBat = topicData.get("android_phone_battery_level") == null ? null : new ArrayList<>(topicData.get("android_phone_battery_level"));
        mE4Data = topicData.get("android_empatica_e4_acceleration") == null ? null : new ArrayList<>(topicData.get("android_empatica_e4_acceleration"));
        mE4Bat = topicData.get("android_empatica_e4_battery_level") == null ? null : new ArrayList<>(topicData.get("android_empatica_e4_battery_level"));
        mBiovData = topicData.get("android_biovotion_vsm1_acceleration") == null ? null : new ArrayList<>(topicData.get("android_biovotion_vsm1_acceleration"));
        mBiovBat = topicData.get("android_biovotion_vsm1_battery_level") == null ? null : new ArrayList<>(topicData.get("android_biovotion_vsm1_battery_level"));
    }

    void display() {
        updateConnectionName();

        try {
            updateTab();
            updateE4();
            updateBiov();
        } catch (JSONException ex) {
            logger.warn("JSON error trying to update device status:", ex);
        } catch (NullPointerException ex) {
            logger.warn("NullPointer error trying to update device status:", ex);
        }

        updateLast();
    }

    private void updateTab() throws JSONException, NullPointerException {
        // update status
        prevTabStatus = updateStatus(mTabData, prevTabStatus, mTabStatusIcon);
        if (mTabData != null && !mTabData.isEmpty())
            lastTabStatus = getLastTimeReceived(mTabData);

        // update battery
        prevTabBatteryLevel = updateBattery(mTabBat, prevTabBatteryLevel, mTabBatteryValue, mTabBatteryLabel);
    }

    private void updateE4() throws JSONException, NullPointerException {
        // update status
        prevE4Status = updateStatus(mE4Data, prevE4Status, mE4StatusIcon);
        if (mE4Data != null && !mE4Data.isEmpty()) {
            lastE4Status = getLastTimeReceived(mE4Data);
            lastE4Label = getLastSourceId(mE4Data);
        }

        // update battery
        prevE4BatteryLevel = updateBattery(mE4Bat, prevE4BatteryLevel, mE4BatteryValue, mE4BatteryLabel);
    }

    private void updateBiov() throws JSONException, NullPointerException {
        // update status
        prevBiovStatus = updateStatus(mBiovData, prevBiovStatus, mBiovStatusIcon);
        if (mBiovData != null && !mBiovData.isEmpty()) {
            lastBiovStatus = getLastTimeReceived(mBiovData);
            lastBiovLabel = getLastSourceId(mBiovData);
        }

        // update battery
        prevBiovBatteryLevel = updateBattery(mBiovBat, prevBiovBatteryLevel, mBiovBatteryValue, mBiovBatteryLabel);
    }

    private DeviceStatusListener.Status updateStatus(ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>> statusData, DeviceStatusListener.Status prevStatus, View statusIconView) {
        // Connection status. Change icon used.
        DeviceStatusListener.Status newStatus;
        if (statusData == null || statusData.isEmpty()) {
            newStatus = DeviceStatusListener.Status.DISCONNECTED;
        } else {
            newStatus = DeviceStatusListener.Status.CONNECTED;
        }
        if (!Objects.equals(newStatus, prevStatus)) {
            logger.info("Status is {}", newStatus);
            Integer statusIcon = deviceStatusIconMap.get(newStatus);
            int resource = statusIcon != null ? statusIcon : deviceStatusIconDefault;
            statusIconView.setBackgroundResource(resource);
        }
        return newStatus;
    }

    private float updateBattery(ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>> batteryData, float prevValue, TextView batValueView, ImageView batLabelView) {
        float batteryLevel = prevValue;
        if (batteryData != null && !batteryData.isEmpty()) {
            try {
                batteryLevel = (float) batteryData.get(batteryData.size()-1).getValue().getDouble("batteryLevel");
            } catch (JSONException ex) {
                logger.error("Error trying to parse battery level", ex);
            } catch (NullPointerException ex) {
                logger.error("Something went wrong during a battery update step!", ex);
            }
        }

        if (Objects.equals((int)(prevValue*100), (int)(batteryLevel*100))) {
            return prevValue;
        }

        String batText = Float.isNaN(batteryLevel) ? "\u2014" : Integer.toString((int)(batteryLevel*100)) + "%";
        batValueView.setText(batText);
        if (Float.isNaN(batteryLevel)) {
            batLabelView.setImageResource(R.drawable.ic_battery_unknown);
        } else if (batteryLevel < 0.1) {
            batLabelView.setImageResource(R.drawable.ic_battery_empty);
        } else if (batteryLevel < 0.3) {
            batLabelView.setImageResource(R.drawable.ic_battery_low);
        } else if (batteryLevel < 0.6) {
            batLabelView.setImageResource(R.drawable.ic_battery_50);
        } else if (batteryLevel < 0.85) {
            batLabelView.setImageResource(R.drawable.ic_battery_80);
        } else {
            batLabelView.setImageResource(R.drawable.ic_battery_full);
        }

        return batteryLevel;
    }

    private void updateConnectionName() {
        if (Objects.equals(connection, previousName)) {
            return;
        }
        previousName = connection;
        // Restrict length of name that is shown.
        String newName = connection;
        if (newName != null && newName.length() > MAX_UI_DEVICE_NAME_LENGTH - 3) {
            newName = newName.substring(0, MAX_UI_DEVICE_NAME_LENGTH) + "...";
        }

        // \u2014 == —
        mConnectionNameLabel.setText(newName == null ? "\u2014" : newName);
    }

    private double getLastTimeReceived(ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>> data) throws JSONException, NullPointerException {
        return data.get(data.size() - 1).getValue().getDouble("timeReceived");
    }
    private String getLastSourceId(ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>> data) throws JSONException, NullPointerException {
        return data.get(data.size() - 1).getKey().getString("sourceId");
    }



    private String getLastText(double stamp, String label) {
        long millis = System.currentTimeMillis() - (long) (stamp*1000);
        String sinceLast = String.format(Locale.UK,"%02d:%02d:%02d", TimeUnit.MILLISECONDS.toHours(millis),
                TimeUnit.MILLISECONDS.toMinutes(millis) % TimeUnit.HOURS.toMinutes(1),
                TimeUnit.MILLISECONDS.toSeconds(millis) % TimeUnit.MINUTES.toSeconds(1));

        if (label != null && !label.isEmpty()) {
            String lastLabel = deviceLabels.containsKey(label) ? deviceLabels.get(label) : "N/A";
            return lastLabel + " | " + sinceLast;
        } else {
            return "N/A | " + sinceLast;
        }
    }
    private void updateLast() {
        if (lastTabStatus != 0)
            mTabLastStatus.setText(getLastText(lastTabStatus, null));

        if (lastE4Status != 0)
            mE4LastStatus.setText(getLastText(lastE4Status, lastE4Label));

        if (lastBiovStatus != 0)
            mBiovLastStatus.setText(getLastText(lastBiovStatus, lastBiovLabel));
    }


    public ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>> getTabData() {
        return mTabData;
    }
    public ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>> getTabBat() {
        return mTabBat;
    }
    public ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>> getE4Data() {
        return mE4Data;
    }
    public ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>> getE4Bat() {
        return mE4Bat;
    }
    public ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>> getBiovData() {
        return mBiovData;
    }
    public ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>> getBiovBat() {
        return mBiovBat;
    }

    public ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>> getDataForTopic(String topic) {
        switch (topic) {
            case "android_phone_acceleration": return getTabData();
            case "android_phone_battery_level": return getTabBat();
            case "android_empatica_e4_acceleration": return getE4Data();
            case "android_empatica_e4_battery_level": return getE4Bat();
            case "android_biovotion_vsm1_acceleration": return getBiovData();
            case "android_biovotion_vsm1_battery_level": return getBiovBat();
            default: return null;
        }
    }
}
