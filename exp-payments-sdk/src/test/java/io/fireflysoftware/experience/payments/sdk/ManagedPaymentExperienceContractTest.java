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

package io.fireflysoftware.experience.payments.sdk;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class ManagedPaymentExperienceContractTest {
    @Test
    void genuineGeneratedClientHasFiveTypedOperationsWithoutAuthenticationImplementationParameters()
            throws Exception {
        var api =
                Class.forName(
                        "io.fireflysoftware.experience.payments.sdk.api.ManagedPaymentExperiencesApi");
        for (String name :
                List.of(
                        "createManagedPaymentExperience",
                        "getManagedPaymentExperience",
                        "getManagedPaymentExperienceHistory",
                        "decideManagedPaymentExperience",
                        "recoverManagedPaymentExperience")) {
            var method =
                    Arrays.stream(api.getDeclaredMethods())
                            .filter(m -> m.getName().equals(name))
                            .toList();
            assertThat(method).as(name).hasSize(1);
            assertThat(Arrays.stream(method.getFirst().getParameterTypes()).map(Class::getName))
                    .noneMatch(
                            type ->
                                    type.contains("Jwt")
                                            || type.contains("Authentication")
                                            || type.contains("ServerWebExchange"));
        }
        var create = Class.forName("io.fireflysoftware.experience.payments.sdk.model.Create");
        assertThat(create.getMethod("getActionId").getReturnType()).isEqualTo(java.util.UUID.class);
        assertThat(create.getMethod("getAmount").getReturnType())
                .isEqualTo(java.math.BigDecimal.class);
        assertThat(create.getMethod("getTtlSeconds").getReturnType()).isEqualTo(Integer.class);
    }

    @Test
    void producerContractRetainsNativeOptOutExactBodiesAndHiddenCredentials() throws Exception {
        try (var input = getClass().getResourceAsStream("/openapi.yml")) {
            assertThat(input).as("Genuine producer specification").isNotNull();
            var spec =
                    new com.fasterxml.jackson.databind.ObjectMapper(
                                    new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                            .readTree(input);
            var root = spec.path("paths").path("/api/v1/tenants/{tenant}/internal-payments");
            assertThat(root.path("post").path("operationId").asText())
                    .isEqualTo("createManagedPaymentExperience");
            assertThat(root.path("post").path("x-disable-idempotency").asBoolean()).isTrue();
            for (String suffix :
                    List.of(
                            "/{paymentId}",
                            "/{paymentId}/history",
                            "/{paymentId}/decisions",
                            "/{paymentId}/recovery")) {
                var path =
                        spec.path("paths")
                                .path("/api/v1/tenants/{tenant}/internal-payments" + suffix);
                var operation =
                        path.path(
                                suffix.endsWith("decisions") || suffix.endsWith("recovery")
                                        ? "post"
                                        : "get");
                assertThat(operation.path("x-disable-idempotency").asBoolean()).as(suffix).isTrue();
            }
            var schemas = spec.path("components").path("schemas");
            for (String name : List.of("Create", "Decision", "Recovery"))
                assertThat(schemas.path(name).path("additionalProperties").asBoolean(true))
                        .as(name)
                        .isFalse();
            var required = new HashSet<String>();
            schemas.path("Create").path("required").forEach(n -> required.add(n.asText()));
            assertThat(required)
                    .contains(
                            "actionId",
                            "sourceAccountId",
                            "destinationAccountId",
                            "amount",
                            "currency",
                            "ttlSeconds");
            assertThat(spec.toString())
                    .doesNotContain(
                            "tokenValue",
                            "JwtAuthenticationToken",
                            "ServerWebExchange",
                            "owned-never-log");
        }
    }
}
