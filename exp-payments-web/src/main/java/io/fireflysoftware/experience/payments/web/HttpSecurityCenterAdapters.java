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

import io.fireflysoftware.experience.payments.AccessDeniedException;
import io.fireflysoftware.experience.payments.AccessPort;
import io.fireflysoftware.experience.payments.IdentityPort;
import io.fireflysoftware.security.center.sdk.api.AccessControllerApi;
import io.fireflysoftware.security.center.sdk.api.IdentityControllerApi;
import io.fireflysoftware.security.center.sdk.model.IdentityRequest;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/**
 * Identity and access for the running journey. Both calls go through domain-core-security-center.
 */
final class HttpSecurityCenterAdapters {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private HttpSecurityCenterAdapters() {
    }

    static final class Identity implements IdentityPort {

        private final IdentityControllerApi api;

        Identity(IdentityControllerApi api) {
            this.api = api;
        }

        @Override
        public Subject resolve(String username) {
            String name = username == null || username.isBlank() ? SAMPLE_USERNAME : username;
            try {
                io.fireflysoftware.security.center.sdk.model.Subject subject = api
                        .resolveSubject(new IdentityRequest().username(name))
                        .block(TIMEOUT);
                if (subject == null || subject.getActorId() == null || subject.getActorId().isBlank()) {
                    throw new AccessDeniedException("ACTOR_UNKNOWN");
                }
                return new Subject(UUID.fromString(subject.getActorId()), name);
            } catch (RuntimeException ex) {
                if (status(ex) == 404) {
                    throw new AccessDeniedException("ACTOR_UNKNOWN");
                }
                throw ex;
            }
        }
    }

    static final class Access implements AccessPort {

        private final AccessControllerApi api;

        Access(AccessControllerApi api) {
            this.api = api;
        }

        @Override
        public Decision check(Request request) {
            io.fireflysoftware.security.center.sdk.model.AccessRequest body =
                    new io.fireflysoftware.security.center.sdk.model.AccessRequest()
                            .actorId(request.actorId().toString())
                            .action(io.fireflysoftware.security.center.sdk.model.AccessRequest.ActionEnum
                                    .fromValue(request.action().name()))
                            .resourceType(io.fireflysoftware.security.center.sdk.model.AccessRequest.ResourceTypeEnum
                                    .fromValue(request.resourceType().name()))
                            .resourceId(request.resourceId().toString());
            io.fireflysoftware.security.center.sdk.model.AccessDecision decision = api
                    .decideAccess(body, UUID.randomUUID().toString())
                    .block(TIMEOUT);
            if (decision == null || !Boolean.TRUE.equals(decision.getAllowed())) {
                String reason = decision == null || decision.getReasonCode() == null
                        ? "ACTOR_NOT_ENTITLED" : decision.getReasonCode();
                return Decision.denied(reason);
            }
            return new Decision(true, decision.getReasonCode() == null ? "SANDBOX_GRANT" : decision.getReasonCode());
        }
    }

    private static int status(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof WebClientResponseException http) {
                return http.getStatusCode().value();
            }
            current = current.getCause();
        }
        return 0;
    }
}
