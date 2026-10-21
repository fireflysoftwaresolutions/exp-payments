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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fireflysoftware.experience.payments.AccessDeniedException;
import io.fireflysoftware.experience.payments.AccessPort;
import io.fireflysoftware.experience.payments.IdentityPort;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Identity and access for the running journey. Both calls go to domain-core-security-center.
 */
final class HttpSecurityCenterAdapters {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpSecurityCenterAdapters() {
    }

    static final class Identity implements IdentityPort {

        private final WebClient webClient;

        Identity(WebClient webClient) {
            this.webClient = webClient;
        }

        @Override
        public Subject resolve(String username) {
            String name = username == null || username.isBlank() ? SAMPLE_USERNAME : username;
            Map<String, String> body = new LinkedHashMap<>();
            body.put("username", name);
            String raw = webClient.post()
                    .uri("/api/v1/identity/subjects")
                    .bodyValue(body)
                    .exchangeToMono(response -> {
                        if (response.statusCode().value() == 404) {
                            return Mono.error(new AccessDeniedException("ACTOR_UNKNOWN"));
                        }
                        if (response.statusCode().isError()) {
                            return response.createException().flatMap(Mono::error);
                        }
                        return response.bodyToMono(String.class);
                    })
                    .block(TIMEOUT);
            JsonNode node = read(raw);
            if (node == null || node.path("actorId").asText().isBlank()) {
                throw new AccessDeniedException("ACTOR_UNKNOWN");
            }
            return new Subject(UUID.fromString(node.path("actorId").asText()), name);
        }
    }

    private static JsonNode read(String raw) {
        try {
            return raw == null || raw.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
        } catch (Exception exception) {
            throw new IllegalStateException("SECURITY_CENTER_RESPONSE_UNREADABLE");
        }
    }

    static final class Access implements AccessPort {

        private final WebClient webClient;

        Access(WebClient webClient) {
            this.webClient = webClient;
        }

        @Override
        public Decision check(Request request) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("actorId", request.actorId().toString());
            body.put("action", request.action().name());
            body.put("resourceType", request.resourceType().name());
            body.put("resourceId", request.resourceId().toString());
            String raw = webClient.post()
                    .uri("/api/v1/access/checks")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);
            JsonNode node = read(raw);
            if (node == null) {
                return Decision.denied("ACTOR_NOT_ENTITLED");
            }
            boolean allowed = node.path("allowed").asBoolean(false);
            String reason = node.path("reasonCode").asText(allowed ? "SANDBOX_GRANT" : "ACTOR_NOT_ENTITLED");
            return allowed ? new Decision(true, reason) : Decision.denied(reason);
        }
    }
}
