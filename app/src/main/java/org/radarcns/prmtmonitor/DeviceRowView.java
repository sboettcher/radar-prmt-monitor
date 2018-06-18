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

import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.android.device.DeviceStatusListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
    private final View mE4StatusIcon;
    private final ImageView mE4BatteryLabel;
    private final TextView mE4BatteryValue;
    private final View mBiovStatusIcon;
    private final ImageView mBiovBatteryLabel;
    private final TextView mBiovBatteryValue;

    private DeviceStatusListener.Status prevTabStatus = null;
    private float prevTabBatteryLevel = Float.NaN;
    private DeviceStatusListener.Status prevE4Status = null;
    private float prevE4BatteryLevel = Float.NaN;
    private DeviceStatusListener.Status prevBiovStatus = null;
    private float prevBiovBatteryLevel = Float.NaN;

    private double lastTabStatus = 0;
    private double lastTabBattery = 0;
    private double lastE4Status = 0;
    private double lastE4Battery = 0;
    private double lastBiovStatus = 0;
    private double lastBiovBattery = 0;

    DeviceRowView(MainActivity mainActivity, String connection, ViewGroup root) {
        this.mainActivity = mainActivity;
        this.connection = connection;
        logger.info("Creating row for connection {}", connection);
        LayoutInflater inflater = (LayoutInflater) this.mainActivity.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.activity_overview_device_row, root);
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
        mE4StatusIcon = row.findViewById(R.id.e4_status_icon);
        mE4BatteryLabel = row.findViewById(R.id.e4_battery_label);
        mE4BatteryValue = row.findViewById(R.id.e4_battery_value);
        mBiovStatusIcon = row.findViewById(R.id.biov_status_icon);
        mBiovBatteryLabel = row.findViewById(R.id.biov_battery_label);
        mBiovBatteryValue = row.findViewById(R.id.biov_battery_value);

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

        updateTab();
        updateE4();
        updateBiov();

        updateLast();
    }

    private void updateTab(){
        // update status
        prevTabStatus = updateStatus(mTabData, prevTabStatus, mTabStatusIcon);
        if (mTabData != null && !mTabData.isEmpty())
            lastTabStatus = System.currentTimeMillis();

        // update battery
        prevTabBatteryLevel = updateBattery(mTabBat, prevTabBatteryLevel, mTabBatteryValue, mTabBatteryLabel);
        if (mTabBat != null && !mTabBat.isEmpty())
            lastTabStatus = System.currentTimeMillis();
    }

    private void updateE4() {
        prevE4Status = updateStatus(mE4Data, prevE4Status, mE4StatusIcon);
        if (mE4Data != null && !mE4Data.isEmpty())
            lastE4Status = System.currentTimeMillis();

        // update battery
        prevE4BatteryLevel = updateBattery(mE4Bat, prevE4BatteryLevel, mE4BatteryValue, mE4BatteryLabel);
        if (mE4Bat != null && !mE4Bat.isEmpty())
            lastE4Status = System.currentTimeMillis();
    }

    private void updateBiov() {
        prevBiovStatus = updateStatus(mBiovData, prevBiovStatus, mBiovStatusIcon);
        if (mBiovData != null && !mBiovData.isEmpty())
            lastBiovStatus = System.currentTimeMillis();

        // update battery
        prevBiovBatteryLevel = updateBattery(mBiovBat, prevBiovBatteryLevel, mBiovBatteryValue, mBiovBatteryLabel);
        if (mBiovBat != null && !mBiovBat.isEmpty())
            lastBiovStatus = System.currentTimeMillis();
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

    private void updateLast() {

    }
}
