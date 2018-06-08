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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Request data to submit records to the Kafka REST proxy.
 */
class TopicRequestData {
    private final byte[] buffer;

    private JSONObject data;

    TopicRequestData() {
        buffer = new byte[1024];
        data = new JSONObject();
    }

    void writeToStream(OutputStream out) throws IOException {
        copyStream(new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.UTF_8)), out);
    }

    void addData(String key, Object value) throws JSONException {
        this.data.put(key, value);
    }

    void reset() {
        data = new JSONObject();
    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        int len = in.read(buffer);
        while (len != -1) {
            out.write(buffer, 0, len);
            len = in.read(buffer);
        }
    }
}


