/*
 * Copyright 2025 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.experience.payments.web;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.fireflysoftware.experience.payments.DomainPaymentsPort;
import io.fireflysoftware.experience.payments.JourneyContract;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/**
 * Calls the payments domain hub. A future BPM replaces this adapter.
 */
public final class HttpSagaAdapter implements DomainPaymentsPort {

    private final String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public HttpSagaAdapter(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public JourneyContract.Movement move(JourneyContract.Request request) {
        return exchange(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/payments/movements"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(request)))
                .build());
    }

    @Override
    public JourneyContract.Movement readStatus(UUID paymentId) {
        return exchange(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/payments/" + paymentId)).GET().build());
    }

    private JourneyContract.Movement exchange(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("The payments domain returned HTTP " + response.statusCode() + ".");
            }
            return json.readValue(response.body(), JourneyContract.Movement.class);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("The payments domain call failed.", ex);
        }
    }

    private String write(JourneyContract.Request request) {
        try {
            return json.writeValueAsString(request);
        } catch (Exception ex) {
            throw new IllegalStateException("The movement request could not be written.", ex);
        }
    }
}
