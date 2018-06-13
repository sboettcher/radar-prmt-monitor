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

package org.radarcns.prmtmonitor.kafka;

import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;

import org.apache.avro.Schema;
import org.apache.avro.reflect.MapEntry;
import org.apache.avro.specific.SpecificRecord;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.prmtmonitor.consumer.KafkaReader;
import org.radarcns.prmtmonitor.consumer.KafkaTopicReader;
import org.radarcns.producer.AuthenticationException;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

/**
 * Separate thread to read from the database and send it to the Kafka server. It cleans the
 * database.
 *
 * It uses a set of timers to addMeasurement data and clean the databases.
 */
public class KafkaDataReader<V> implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(KafkaDataReader.class);

    private final ServerStatusListener listener;
    private final KafkaReader reader;
    private KafkaTopicReader topicReader;
    private HashSet<AvroTopic> subscribedTopics;
    private ArrayList<String> availableTopics;
    private final KafkaConnectionChecker connection;
    private final AtomicInteger getLimit;
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private HashMap<String, HashMap<String, ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>>>> connectionTopicData; // TODO: put this in a simple external class?

    private Runnable downloadFuture;
    private Runnable subscribeFuture;
    /** Upload rate in milliseconds. */
    private long downloadRate;

    private String consumerGroup;
    private String consumerInstance;

    private boolean persistentData;
    private int dataDecayMs;

    public static final String CONFIG_CONSUMER_GROUP = "consumer_group";
    public static final String CONFIG_CONSUMER_INSTANCE = "consumer_instance";
    public static final String CONFIG_CONSUMER_RATE = "consumer_download_rate";
    public static final String CONFIG_CONSUMER_PERSISTENT = "consumer_persistent_data";
    public static final String CONFIG_CONSUMER_DECAY = "consumer_data_decay_ms";

    public KafkaDataReader(@NonNull ServerStatusListener listener, @NonNull
            KafkaReader reader, String consumerGroup, String consumerInstance, int getLimit, long downloadRate, boolean persistentData, int dataDecayMs) {
        this.listener = listener;
        this.reader = reader;
        this.topicReader = null;
        this.subscribedTopics = new HashSet<>();
        this.availableTopics = new ArrayList<>();
        this.getLimit = new AtomicInteger(getLimit);

        this.consumerGroup = consumerGroup;
        this.consumerInstance = consumerInstance;

        this.persistentData = persistentData;
        this.dataDecayMs = dataDecayMs;

        mHandlerThread = new HandlerThread("data-reader", THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        logger.info("Started data read executor");

        connection = new KafkaConnectionChecker(reader, mHandler, listener, downloadRate * 5);

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (KafkaDataReader.this.reader.isConnected()) {
                        KafkaDataReader.this.listener.updateServerStatus(ServerStatusListener.Status.CONNECTED);
                        connection.didConnect();
                    } else {
                        KafkaDataReader.this.listener.updateServerStatus(ServerStatusListener.Status.DISCONNECTED);
                        connection.didDisconnect(null);
                    }
                } catch (AuthenticationException ex) {
                    connection.didDisconnect(ex);
                }
            }
        });

        synchronized (this) {
            downloadFuture = null;
            subscribeFuture = null;
            connectionTopicData = new HashMap<>();
            setDownloadRate(downloadRate);
        }
        logger.info("Remote Config: Upload rate is '{}' sec per upload", downloadRate);
    }

    /** Set download rate in seconds. */
    public final synchronized void setDownloadRate(long period) {
        long newDownloadRate = period * 1000L;
        if (this.downloadRate == newDownloadRate) {
            return;
        }
        this.downloadRate = newDownloadRate;
        if (downloadFuture != null) {
            mHandler.removeCallbacks(downloadFuture);
        }
        // Get upload frequency from system property
        downloadFuture = new Runnable() {
            @Override
            public void run() {
                if (connection.isConnected() && !subscribedTopics.isEmpty()) {
                    read();
                }
                mHandler.postDelayed(this, downloadRate);
            }
        };
        mHandler.postDelayed(downloadFuture, downloadRate);
    }

    /** Upload rate in seconds. */
    private synchronized long getDownloadRate() {
        return this.downloadRate / 1000L;
    }

    public void setGetLimit(int limit) {
        getLimit.set(limit);
    }

    /**
     * Close the submitter eventually. This does not flush any caches.
     */
    @Override
    public synchronized void close() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mHandler.removeCallbacks(downloadFuture);
                mHandler.removeCallbacks(subscribeFuture);

                try {
                    topicReader.close();
                } catch (IOException e) {
                    logger.warn("failed to close topicReader", e);
                }

                try {
                    reader.close();
                } catch (IOException e) {
                    logger.warn("failed to close reader", e);
                }

                subscribedTopics.clear();
            }
        });
        mHandlerThread.quitSafely();
    }

    public void addTopics(final Set<AvroTopic> newTopics) throws IOException {
        if (subscribeFuture != null) {
            mHandler.removeCallbacks(subscribeFuture);
        }
        subscribeFuture = new Runnable() {
            @Override
            public void run() {
                if (connection.isConnected()) {
                    try {
                        if (topicReader == null) {
                            topicReader = reader.reader();
                            topicReader.close(consumerGroup, consumerInstance);
                            topicReader.consumer(consumerGroup, consumerInstance);

                            availableTopics = filterTopics(topicReader.topics());
                            logger.info("{} topics available on server", availableTopics.size());
                        }
                        if (checkAvailableTopics(newTopics)) {
                            topicReader.subscribe(newTopics);
                            subscribedTopics.addAll(newTopics);
                        }
                    } catch (IOException ex) {
                        logger.error("Error trying ot subscribe to topics: ", ex);
                    } catch (JSONException ex) {
                        logger.error("Failed to convert a response to JSON!", ex);
                    }
                } else {
                    mHandler.postDelayed(this, downloadRate);
                }
            }
        };
        mHandler.postDelayed(subscribeFuture, downloadRate);
    }

    /**
     * Check the connection status eventually.
     */
    public void checkConnection() {
        connection.check();
    }


    private void read() {
        try {
            JSONArray jsonResponse = this.topicReader.read();

            // process the new data per topic
            HashMap<String, ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>>> topicData = new HashMap<>();
            for (int i = 0; i < jsonResponse.length(); i++) {
                JSONObject sample = jsonResponse.getJSONObject(i);
                String topic = sample.getString("topic");
                JSONObject key = sample.getJSONObject("key");
                JSONObject value = sample.getJSONObject("value");

                if (!topicData.containsKey(topic)) {
                    topicData.put(topic, new ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>>());
                }

                topicData.get(topic).add(new AbstractMap.SimpleEntry<>(key, value));
            }

            if (!persistentData)
                decayData();

            // status update; map by user ID --> connections
            for (Map.Entry<String, ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>>> data : topicData.entrySet()) {
                listener.updateRecordsRead(data.getKey(), data.getValue().size());
                logger.info("Number of values read from topic {}: {}", data.getKey(), data.getValue().size());

                for (AbstractMap.SimpleEntry<JSONObject, JSONObject> sample : data.getValue()) {
                    String userId = sample.getKey().getString("userId");

                    // check if user id exists
                    if (!connectionTopicData.containsKey(userId)) {
                        connectionTopicData.put(userId, new HashMap<String, ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>>>());
                    }
                    // check if topic for user id exists
                    if (!connectionTopicData.get(userId).containsKey(data.getKey())) {
                        connectionTopicData.get(userId).put(data.getKey(), new ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>>());
                    }

                    connectionTopicData.get(userId).get(data.getKey()).add(sample);
                }
            }

        } catch (IOException ex) {
            logger.error("Failed to read!", ex);
        } catch (JSONException ex) {
            logger.error("Failed to convert a response to JSON!", ex);
        }
    }

    public HashSet<String> getConnections() {
        return new HashSet<>(Collections.unmodifiableSet(connectionTopicData.keySet()));
    }
    public HashSet<String> getTopics(String userId) {
        if (connectionTopicData.containsKey(userId)) {
            return new HashSet<>(Collections.unmodifiableSet(connectionTopicData.get(userId).keySet()));
        } else {
            return new HashSet<>();
        }
    }
    public HashMap<String, ArrayList<AbstractMap.SimpleEntry<JSONObject,JSONObject>>> getTopicData(String userId) {
        if (connectionTopicData.containsKey(userId)) {
            return new HashMap<>(Collections.unmodifiableMap(connectionTopicData.get(userId)));
        } else {
            return new HashMap<>();
        }
    }
    public ArrayList<AbstractMap.SimpleEntry<JSONObject, JSONObject>> getData(String userId, String topic) {
        if (connectionTopicData.containsKey(userId) && connectionTopicData.get(userId).containsKey(topic)) {
            return connectionTopicData.get(userId).get(topic);
        } else {
            return new ArrayList<>();
        }
    }


    private void decayData() {
        for (String userId : connectionTopicData.keySet()) {
            for (String topic : connectionTopicData.get(userId).keySet()) {
                int dataLengthBefore = connectionTopicData.get(userId).get(topic).size();
                for (Iterator<AbstractMap.SimpleEntry<JSONObject, JSONObject>> sampleIterator = connectionTopicData.get(userId).get(topic).iterator(); sampleIterator.hasNext(); ) {
                    AbstractMap.SimpleEntry<JSONObject, JSONObject> sample = sampleIterator.next();
                    double received = Double.NaN;
                    double current = System.currentTimeMillis();

                    try {
                        received = sample.getValue().getDouble("timeReceived") * 1000;
                    } catch (JSONException ex) {
                        logger.error("Error trying to parse received timestamp!", ex);
                    }

                    if (!Double.isNaN(received) && received < current - dataDecayMs) {
                        sampleIterator.remove();
                    }
                }
                int dataLengthAfter = connectionTopicData.get(userId).get(topic).size();
                if (dataLengthAfter != dataLengthBefore)
                    logger.info("Decayed {} samples for {}.{}, new total: {}", dataLengthBefore-dataLengthAfter, userId, topic, dataLengthAfter);
            }
        }
    }


    /**
     * Upload a limited amount of data stored in the database which is not yet sent.
     */
    /*
    private void uploadCaches(Set<AvroTopic<ObservationKey, ? extends V>> toSend) {
        boolean uploadingNotified = false;
        int currentSendLimit = getLimit.get();
        try {
            for (Map.Entry<AvroTopic<ObservationKey, ? extends V>, ? extends DataCache<ObservationKey, ? extends V>> entry : listener.getCaches().entrySet()) {
                if (!toSend.contains(entry.getKey())) {
                    continue;
                }
                @SuppressWarnings("unchecked") // we can upload any record
                int sent = uploadCache((AvroTopic<ObservationKey, V>)entry.getKey(), (DataCache<ObservationKey, V>)entry.getValue(), currentSendLimit, uploadingNotified);
                if (sent < currentSendLimit) {
                    toSend.remove(entry.getKey());
                }
                if (!uploadingNotified && sent > 0) {
                    uploadingNotified = true;
                }
            }
            if (uploadingNotified) {
                listener.updateServerStatus(ServerStatusListener.Status.CONNECTED);
                connection.didConnect();
            }
        } catch (IOException ex) {
            connection.didDisconnect(ex);
        }
    }
    */

    /**
     * Upload some data from a single table.
     * @return number of records sent.
     */
    /*
    private int uploadCache(AvroTopic<ObservationKey, V> topic, DataCache<ObservationKey, V> cache, int limit,
                            boolean uploadingNotified) throws IOException {
        List<Record<ObservationKey, V>> unfilteredMeasurements = cache.unsentRecords(limit);

        List<Record<ObservationKey, V>> measurements = listPool.get(Collections
                .<Record<ObservationKey,V>>emptyList());
        for (Record<ObservationKey, V> record : unfilteredMeasurements) {
            if (record != null && record.key.getUserId().equals(userId)) {
                measurements.add(record);
            }
        }

        int numberOfRecords = measurements.size();
        int totalSize = unfilteredMeasurements.size();

        try {
            if (numberOfRecords > 0) {
                KafkaTopicReader cacheSender = reader(topic);

                if (!uploadingNotified) {
                    listener.updateServerStatus(ServerStatusListener.Status.UPLOADING);
                }

                try {
                    cacheSender.send(new AvroRecordData<>(topic, measurements));
                    cacheSender.flush();
                } catch (AuthenticationException ex) {
                    listener.updateRecordsSent(topic.getName(), -1);
                    throw ex;
                } catch (IOException ioe) {
                    listener.updateServerStatus(ServerStatusListener.Status.UPLOADING_FAILED);
                    listener.updateRecordsSent(topic.getName(), -1);
                    throw ioe;
                }

                listener.updateRecordsSent(topic.getName(), numberOfRecords);

                logger.debug("uploaded {} {} records", numberOfRecords, topic.getName());
            }
            cache.remove(totalSize);
        } finally {
            listPool.add(measurements);
            cache.returnList(unfilteredMeasurements);
        }

        return totalSize;
    }
    */


    /** Immediately read from given topics, without any error recovery. */
    private void doImmediateRead(Set<AvroTopic> topics) throws IOException {
        if (this.topicReader != null) {
            read();
        }
    }

    public <V extends SpecificRecord> AvroTopic<ObservationKey, V> createTopic(String name, Class<V> valueClass) {
        try {
            Method method = valueClass.getMethod("getClassSchema");
            Schema valueSchema = (Schema) method.invoke(null);
            return new AvroTopic<>(
                    name, ObservationKey.getClassSchema(), valueSchema, ObservationKey.class, valueClass);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            logger.error("Error creating topic " + name, e);
            throw new RuntimeException(e);
        }
    }


    /**
     * Topic Helpers
     */

    private ArrayList<String> filterTopics(JSONArray topics) throws JSONException {
        ArrayList<String> filteredTopics = new ArrayList<>();
        for (int i = 0; i < topics.length(); i++) {
            filteredTopics.add(topics.getString(i));
        }
        for (Iterator<String> iterator = filteredTopics.iterator(); iterator.hasNext(); ) {
            String topic = iterator.next();
            if (topic.contains("_1") || topic.contains("org.radarcns.stream") || topic.equals("_schemas"))
                iterator.remove();
        }
        return filteredTopics;
    }

    public ArrayList<String> getAvailableTopics() {
        return availableTopics;
    }

    public boolean checkAvailableTopics(String topic) {
        return availableTopics.contains(topic);
    }
    public boolean checkAvailableTopics(Set<AvroTopic> topics) {
        boolean isAvailable = true;
        for (AvroTopic t : topics) {
            if (!availableTopics.contains(t.getName()))
                isAvailable = false;
        }
        return isAvailable;
    }
}
