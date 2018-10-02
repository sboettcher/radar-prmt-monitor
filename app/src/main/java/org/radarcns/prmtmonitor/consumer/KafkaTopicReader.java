package org.radarcns.prmtmonitor.consumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.radarcns.producer.AuthenticationException;
import org.radarcns.topic.AvroTopic;

import java.io.Closeable;
import java.io.IOException;
import java.util.Set;

public interface KafkaTopicReader extends Closeable {
    /**
     * Get a full list of available Kafka topics.
     *
     * @return JSONArray of topics available on the Kafka server
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     * @throws JSONException if the read response could not be decoded to a JSONArray
     */
    JSONArray topics() throws IOException, JSONException;


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
     * Assign specific partitions to the consumer.
     *
     * @param topics set of avro topics to assign partitions from
     * @param partitions set of partitions to assign
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     */
    void assignPartitions(Set<AvroTopic> topics, Set<Integer> partitions) throws IOException;


    /**
     * Seek to the end of the given topic/partition.
     *
     * @param topics set of avro topics
     * @param partitions set of partitions
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     */
    void seekEnd(Set<AvroTopic> topics, Set<Integer> partitions) throws IOException;


    /**
     * Consume messages from the subscribed Kafka topics.
     *
     * @return A JSONArray of the samples consumed
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     * @throws JSONException if the read response could not be decoded to a JSONArray
     */
    JSONArray read() throws IOException, JSONException;


    /**
     * Closes the current consumer.
     *
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     */
    void close() throws IOException;
    /**
     * Closes a specific consumer.
     *
     * @param group consumer group id
     * @param instance consumer instance id
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     */
    void close(String group, String instance) throws IOException;
}
