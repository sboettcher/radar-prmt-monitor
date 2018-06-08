/*
 * Copyright 2017 The Hyve and King's College London
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

package org.radarcns.prmtmonitor.consumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.producer.AuthenticationException;
import org.radarcns.producer.rest.ConnectionState;
import org.radarcns.producer.rest.RestClient;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

import static org.radarcns.prmtmonitor.consumer.RestReader.KAFKA_REST_ACCEPT_ENCODING;
import static org.radarcns.prmtmonitor.consumer.TopicRequestBody.topicRequestContent;
import static org.radarcns.producer.rest.RestClient.responseBody;

class RestTopicReader implements KafkaTopicReader {
    private static final Logger logger = LoggerFactory.getLogger(RestTopicReader.class);
    private static final int LOG_CONTENT_LENGTH = 1024;

    private HashSet<AvroTopic> topics;
    private final RestReader reader;
    private final ConnectionState state;
    private String consumer_group;
    private String consumer_instance;

    private boolean has_consumer;

    RestTopicReader(RestReader reader, ConnectionState state) {
        this.topics = new HashSet<>();
        this.reader = reader;
        this.state = state;
        this.has_consumer = false;
    }

    @Override
    public void consumer(String group, String instance) throws IOException {
        this.consumer_group = group;
        this.consumer_instance = instance;

        logger.info("Creating consumer {}.{}", consumer_group, consumer_instance);

        RestClient restClient;
        RestReader.RequestProperties requestProperties;
        synchronized (reader) {
            restClient = reader.getRestClient();
            requestProperties = reader.getRequestProperties();
        }

        TopicRequestData data = new TopicRequestData();
        try {
            data.addData("name", consumer_instance);
            data.addData("format", "avro");
            data.addData("auto.offset.reset", "latest");
        } catch (JSONException ex) {
            throw new IOException("Error trying to add consumer info to JSON data: ", ex);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", requestProperties.contentType.toString());

        Request request = buildRequest( "POST", restClient.getRelativeUrl(
                "consumers/" + consumer_group), requestProperties, data, headers);
        handleRequest(restClient, request);

        if (state.getState() == ConnectionState.State.UNAUTHORIZED) {
            throw new AuthenticationException("Request unauthorized");
        }

        this.has_consumer = true;
    }

    @Override
    public void subscribe(Set<AvroTopic> topics) throws IOException {
        this.topics.addAll(topics);

        if (!has_consumer) {
            throw new IOException("Consumer has not been created for this reader!");
        }

        if (this.topics.isEmpty()) {
            logger.warn("No topics given to subscribe to.");
            return;
        }

        logger.info("Subscribing to topics {}", this.topics);

        RestClient restClient;
        RestReader.RequestProperties requestProperties;
        synchronized (reader) {
            restClient = reader.getRestClient();
            requestProperties = reader.getRequestProperties();
        }

        final ArrayList<String> stringTopics = new ArrayList<>();
        for (AvroTopic t : this.topics) {
            stringTopics.add(t.getName());
        }

        TopicRequestData data = new TopicRequestData();
        try {
            data.addData("topics", new JSONArray(stringTopics));
        } catch (JSONException ex) {
            throw new IOException("Error trying to add topics to JSON data: ", ex);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", requestProperties.contentType.toString());

        Request request = buildRequest("POST", restClient.getRelativeUrl(
                "consumers/" + consumer_group
                        + "/instances/" + consumer_instance
                        + "/subscription"), requestProperties, data, headers);
        handleRequest(restClient, request);

        if (state.getState() == ConnectionState.State.UNAUTHORIZED) {
            throw new AuthenticationException("Request unauthorized");
        }
    }

    @Override
    public JSONArray read() throws IOException, JSONException {
        logger.info("Reading");

        RestClient restClient;
        RestReader.RequestProperties requestProperties;
        synchronized (reader) {
            restClient = reader.getRestClient();
            requestProperties = reader.getRequestProperties();
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", requestProperties.contentType.toString());

        Request request = buildRequest( "GET", restClient.getRelativeUrl(
                "consumers/" + consumer_group
                        + "/instances/" + consumer_instance
                        + "/records"), requestProperties, null, headers);
        String response = handleRequest(restClient, request);

        if (state.getState() == ConnectionState.State.UNAUTHORIZED) {
            throw new AuthenticationException("Request unauthorized");
        }

        return new JSONArray(response);
    }

    @Override
    public void close(String group, String instance) throws IOException {
        this.consumer_group = group;
        this.consumer_instance = instance;
        close();
    }
    @Override
    public void close() throws IOException {
        if (consumer_group == null || consumer_instance == null){
            throw new IOException("Missing group or instance name for consumer, cannot close.");
        }

        logger.info("Closing consumer {}.{}", consumer_group, consumer_instance);

        RestClient restClient;
        RestReader.RequestProperties requestProperties;
        synchronized (reader) {
            restClient = reader.getRestClient();
            requestProperties = reader.getRequestProperties();
        }

        Request request = buildRequest( "DELETE", restClient.getRelativeUrl(
                "consumers/" + consumer_group
                        + "/instances/" + consumer_instance), requestProperties, null);
        handleRequest(restClient, request);

        if (state.getState() == ConnectionState.State.UNAUTHORIZED) {
            throw new AuthenticationException("Request unauthorized");
        }

        this.has_consumer = false;
    }



    private Request buildRequest(String method, HttpUrl sendToUrl, RestReader.RequestProperties properties, TopicRequestData requestData)
            throws IOException {
        return buildRequest(method, sendToUrl, properties, requestData, Collections.<String, String>emptyMap());
    }
    private Request buildRequest(String method, HttpUrl sendToUrl, RestReader.RequestProperties properties, TopicRequestData requestData, Map<String, String> addHeaders)
            throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
                .url(sendToUrl)
                .headers(properties.headers)
                .header("Accept", properties.acceptType);

        TopicRequestBody requestBody = null;
        if (requestData != null) {
            requestBody = new TopicRequestBody(requestData, properties.contentType);
        }

        for (Map.Entry<String,String> h : addHeaders.entrySet()) {
            requestBuilder.header(h.getKey(), h.getValue());
        }

        return requestBuilder.method(method, requestBody).build();
    }


    private String handleRequest(RestClient restClient, Request request) throws IOException {
        try (Response response = restClient.request(request)) {
            String stringRes = null;

            if (response.isSuccessful()) {
                state.didConnect();
            } else if (response.code() == 401 || response.code() == 403) {
                state.wasUnauthorized();
            } else if (response.code() == 415
                    && Objects.equals(request.header("Accept"), KAFKA_REST_ACCEPT_ENCODING)) {
                state.didConnect();
                logger.error("Latest Avro encoding is not supported.");
            } else if (response.code() == 409) {
                stringRes = responseBody(response);
                if (stringRes != null && stringRes.contains("40902")) {
                    state.didConnect();
                    logger.warn("REST Status: Consumer already exists. Response: {}", stringRes);
                }
            } else if (response.code() == 404) {
                stringRes = responseBody(response);
                if (stringRes != null && stringRes.contains("40403")) {
                    state.didConnect();
                    logger.warn("REST Status: Consumer does not exist. Response: {}", stringRes);
                }
            } else {
                logFailure(request, response, null);
            }

            if (stringRes == null) stringRes = responseBody(response);
            logger.trace("REST response: {}", (stringRes != null && stringRes.length() > 200) ? stringRes.substring(0, 200)+"..." : stringRes);
            return stringRes;
        } catch (IOException ex) {
            logFailure(request, null, ex);
        }
        return null;
    }


    @SuppressWarnings("ConstantConditions")
    private void logFailure(Request request, Response response, Exception ex)
            throws IOException {
        state.didDisconnect();
        String content = response == null ? null : responseBody(response);
        int code = response == null ? -1 : response.code();
        String requestContent = topicRequestContent(request);
        if (requestContent != null) {
            requestContent = requestContent.substring(0,
                    Math.min(requestContent.length(), LOG_CONTENT_LENGTH));
        }
        logger.error("FAILED to transmit message: {} -> {}...",
                content, requestContent);
        throw new IOException("Failed to submit (HTTP status code " + code
                + "): " + content, ex);
    }

    public boolean hasConsumer() {
        return has_consumer;
    }
}
