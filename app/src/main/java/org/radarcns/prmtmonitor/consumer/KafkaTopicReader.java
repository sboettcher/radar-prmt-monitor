package org.radarcns.prmtmonitor.consumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.producer.AuthenticationException;
import org.radarcns.topic.AvroTopic;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

public interface KafkaTopicReader extends Closeable {
    /**
     * Create a consumer in given group with given instance id.
     *
     * @param group consumer group id
     * @param instance consumer instance id
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     */
    void consumer(String group, String instance) throws IOException;

    /**
     * Subscribe the consumer to a Kafka topic.
     *
     * @param topics set of avro topics to subscribe to
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     */
    void subscribe(Set<AvroTopic> topics) throws IOException;

    /**
     * Read messages from subscribed Kafka topic.
     *
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     */
    JSONArray read() throws IOException, JSONException;

    /**
     * Closes the consumer.
     *
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     */
    void close() throws IOException;
    void close(String group, String instance) throws IOException;
}
