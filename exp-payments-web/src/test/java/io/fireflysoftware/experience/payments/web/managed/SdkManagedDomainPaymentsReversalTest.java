/*
 * Copyright 2026 Firefly Software Foundation
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
package io.fireflysoftware.experience.payments.web.managed;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.fireflysoftware.experience.payments.managed.*;
import io.fireflysoftware.experience.payments.managed.ManagedPaymentContracts.*;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

class SdkManagedDomainPaymentsReversalTest {
    @Test
    void reverseDelegationKeepsOriginalIdentityAndUsesReversePermissions() throws Exception {
        UUID payment = UUID.randomUUID(), action = UUID.randomUUID();
        UUID source = UUID.randomUUID(), destination = UUID.randomUUID();
        long originalDeadline = System.currentTimeMillis() - 1000;
        var before = new View(payment, UUID.randomUUID(), "owned", source, destination,
                "1.23", "USD", 2, "owned-policy", 10, null, originalDeadline,
                "original-initiator", State.COMPLETED, 8, UUID.randomUUID(), UUID.randomUUID(),
                null, null, 1L, Instant.now(), null, null, "COMMITTED",
                Instant.ofEpochMilli(originalDeadline));
        Set<String> scopes = Set.of("payments:reverse", "payments:read", "payments:coordinate",
                "funds:read", "funds:reconcile", "funds:reverse");
        var principal = SecurityPrincipal.builder().issuer("owned-test").subject("current-reader")
                .tenantId("owned").scopes(scopes).claims(Map.of("exp", Instant.now().plusSeconds(60),
                        "funds_accounts", List.of(source.toString(), destination.toString()))).build();
        var caller = new ManagedPaymentContext.Call(principal, "synthetic-transient", Set.of("domain"));
        var delegated = new AtomicReference<Set<String>>();
        ManagedPaymentDelegationPort tokens = (audience, tenant, accounts, requested) -> {
            delegated.set(requested);
            return Mono.just("synthetic-current");
        };
        var bodies = new CopyOnWriteArrayList<ObjectNode>();
        var json = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add((ObjectNode) json.readTree(exchange.getRequestBody()));
            ObjectNode response = json.valueToTree(before);
            response.put("state", "REVERSING");
            response.put("version", 9);
            response.put("pendingEffectId", UUID.randomUUID().toString());
            byte[] bytes = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var adapter = new SdkManagedDomainPaymentsAdapter(tokens,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "domain", true);
            var result = adapter.decide("owned", before, new Decision(action, 8, DecisionKind.REVERSE))
                    .contextWrite(context -> ManagedPaymentContext.with(context, caller))
                    .block(Duration.ofSeconds(5));
            assertThat(result.state()).isEqualTo(State.REVERSING);
            assertThat(result.notAfterEpochMillis()).isEqualTo(originalDeadline);
            assertThat(delegated.get()).isEqualTo(scopes);
            assertThat(bodies).hasSize(1);
            assertThat(bodies.getFirst().get("kind").asText()).isEqualTo("REVERSE");
            assertThat(bodies.getFirst().get("actionId").asText()).isEqualTo(action.toString());
            assertThat(bodies.getFirst().get("expectedVersion").asLong()).isEqualTo(8);
        } finally {
            server.stop(0);
        }
    }
}
