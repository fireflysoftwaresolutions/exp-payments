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

package io.fireflysoftware.experience.payments.web.managed;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;

import io.fireflysoftware.experience.payments.PaymentJourneyService;
import io.fireflysoftware.experience.payments.managed.*;
import io.fireflysoftware.experience.payments.managed.ManagedPaymentContracts.*;
import io.fireflysoftware.experience.payments.web.PaymentsJourneyController;

import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;

@org.springframework.test.context.ActiveProfiles("owned-payment-boundary-test")
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {
            ManagedPaymentsExperienceRuntimeTest.BoundaryApp.class,
            ManagedPaymentsExperienceRuntimeTest.Fixture.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagedPaymentsExperienceRuntimeTest {
    static final UUID ACTION = UUID.fromString("10000000-0000-4000-8000-000000000001"),
            SOURCE = UUID.fromString("10000000-0000-4000-8000-000000000002"),
            TARGET = UUID.fromString("10000000-0000-4000-8000-000000000003"),
            PAYMENT = UUID.fromString("10000000-0000-4000-8000-000000000004");
    static final String ROOT = "/api/v1/tenants/owned/internal-payments";
    static final String CREATE =
            "{\"actionId\":\""
                    + ACTION
                    + "\",\"sourceAccountId\":\""
                    + SOURCE
                    + "\",\"destinationAccountId\":\""
                    + TARGET
                    + "\",\"amount\":12.30,\"currency\":\"USD\",\"ttlSeconds\":300}";
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static final String ISSUER;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext(
                    "/jwks",
                    exchange -> {
                        byte[] body =
                                new JWKSet(KEY.toPublicJWK())
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body);
                        }
                    });
            JWKS.start();
            ISSUER = "http://127.0.0.1:" + JWKS.getAddress().getPort();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.application.name", () -> "exp-payments-boundary-owned");
        properties.add("managed-payments.security.issuer", () -> ISSUER);
        properties.add("managed-payments.security.audience", () -> "experience-managed");
        properties.add("managed-payments.security.jwk-set-uri", () -> ISSUER + "/jwks");
        properties.add("firefly.security.resource-server.strict-policy", () -> true);
        properties.add("firefly.cache.default-cache-type", () -> "CAFFEINE");
        properties.add("firefly.cache.redis.enabled", () -> false);
        properties.add("firefly.cache.postgres.enabled", () -> false);
    }

    @AfterAll
    static void cleanup() {
        JWKS.stop(0);
    }

    @LocalServerPort int port;
    @Autowired Owner owner;
    @Autowired PaymentJourneyService legacy;

    @BeforeEach
    void reset() throws java.io.IOException {
        owner.calls.set(0);
        owner.allowed.set(true);
        var file = System.getProperty("boundary.ports.file");
        if (file != null)
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(file),
                    "{\"app\":" + port + ",\"jwks\":" + JWKS.getAddress().getPort() + "}");
    }

    WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    static String token(String tenant, String audience, Instant expiry) {
        try {
            var claims =
                    new JWTClaimsSet.Builder()
                            .issuer(ISSUER)
                            .subject("alice")
                            .audience(audience)
                            .issueTime(Date.from(Instant.now().minusSeconds(1)))
                            .expirationTime(Date.from(expiry))
                            .claim("tenant_id", tenant)
                            .claim(
                                    "scope",
                                    "payments:create payments:read payments:confirm payments:cancel"
                                            + " payments:recover payments:coordinate funds:read"
                                            + " funds:reserve funds:commit funds:release"
                                            + " funds:reconcile funds:expire")
                            .claim("funds_accounts", List.of(SOURCE.toString(), TARGET.toString()))
                            .build();
            var jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .keyID(KEY.getKeyID())
                                    .type(JOSEObjectType.JWT)
                                    .build(),
                            claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception error) {
            throw new AssertionError("Owned JWT signing failed", error);
        }
    }

    static String valid() {
        return token("owned", "experience-managed", Instant.now().plusSeconds(60));
    }

    Reply post(String path, String body, String bearer) {
        var request =
                client().post()
                        .uri(path)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return reply(request.bodyValue(body).exchange());
    }

    @Test
    void actualHttpPreservesTypedOwnerIntentAndNoSession() {
        var response = post(ROOT, CREATE, valid()).expected(201);
        assertThat(response.headers.containsHeader("Set-Cookie")).isFalse();
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body);
            assertThat(json.path("paymentId").asText()).isEqualTo(PAYMENT.toString());
            assertThat(json.path("amount").asText()).isEqualTo("12.30");
        } catch (java.io.IOException error) {
            throw new AssertionError("Expected JSON financial receipt", error);
        }
        assertThat(owner.calls).hasValue(1);
        assertThat(owner.input.get().actionId()).isEqualTo(ACTION);
        assertThat(owner.input.get().amount()).isEqualByComparingTo(new BigDecimal("12.30"));
    }

    @Test
    void actualHttpRejectsUnknownDuplicateAndCoercedCreateBeforeOwner() {
        for (String body :
                List.of(
                        CREATE.replace("}", ",\"extra\":true}"),
                        CREATE.replace("}", ",\"ttlSeconds\":300}"),
                        CREATE.replace("}", ",\"actionId\":\"" + ACTION + "\"}"),
                        CREATE + " {}",
                        CREATE.replace("12.30", "\"12.30\""),
                        CREATE.replace("12.30", "true"),
                        CREATE.replace(":300", ":300.0"),
                        CREATE.replace(":300", ":3e2"),
                        CREATE.replace(":300", ":\"300\""),
                        CREATE.replace(ACTION.toString(), "1-1-1-1-1"),
                        CREATE.replace("\"" + ACTION + "\"", "123"),
                        CREATE.replace("}", ",\"extra\":{\"nested\":1,\"nested\":2}}"))) {
            post(ROOT, body, valid()).expected(400);
        }
        assertThat(owner.calls).hasValue(0);
    }

    @Test
    void actualHttpRejectsDecisionAndRecoveryCoercionBeforeOwner() {
        String decision =
                "{\"actionId\":\"" + ACTION + "\",\"expectedVersion\":2,\"kind\":\"CONFIRM\"}";
        String recovery = "{\"actionId\":\"" + ACTION + "\",\"expectedVersion\":2}";
        for (String body :
                List.of(
                        decision.replace(":2", ":2.0"),
                        decision.replace(":2", ":\"2\""),
                        decision.replace("}", ",\"kind\":\"CONFIRM\"}"),
                        decision.replace("CONFIRM", "REVERSE")))
            post(ROOT + "/" + PAYMENT + "/decisions", body, valid()).expected(400);
        for (String body :
                List.of(
                        recovery.replace(":2", ":true"),
                        recovery.replace(":2", ":2.0"),
                        recovery.replace("}", ",\"ledgerState\":\"COMMITTED\"}")))
            post(ROOT + "/" + PAYMENT + "/recovery", body, valid()).expected(400);
        assertThat(owner.calls).hasValue(0);
    }

    @Test
    void legacySpoofedUsernameCannotReachManagedFinancialResources() {
        for (String bearer : List.of("", valid())) {
            var read =
                    client().get()
                            .uri("/api/v1/payments/" + PAYMENT)
                            .header("X-Firefly-Username", "administrator");
            if (!bearer.isEmpty()) read.header("Authorization", "Bearer " + bearer);
            reply(read.exchange()).expected(403);
            post("/api/v1/payments/tries", CREATE, bearer.isEmpty() ? null : bearer).expected(403);
            post(
                            "/api/v1/payments/" + PAYMENT + "/confirmations",
                            "{}",
                            bearer.isEmpty() ? null : bearer)
                    .expected(403);
            post(
                            "/api/v1/payments/" + PAYMENT + "/cancellations",
                            "{}",
                            bearer.isEmpty() ? null : bearer)
                    .expected(403);
        }
        assertThat(owner.calls).hasValue(0);
        org.mockito.Mockito.verifyNoInteractions(legacy);
    }

    @Test
    void actualHttpRequiresSignedCurrentTenantAudienceAndPolicy() {
        post(ROOT, CREATE, null).expected(403);
        post(ROOT, CREATE, token("owned", "other", Instant.now().plusSeconds(60))).expected(401);
        post(ROOT, CREATE, token("owned", "experience-managed", Instant.now().minusSeconds(120)))
                .expected(401);
        post(ROOT, CREATE, token("foreign", "experience-managed", Instant.now().plusSeconds(60)))
                .expected(403);
        owner.allowed.set(false);
        post(ROOT, CREATE, valid()).expected(403);
        assertThat(owner.calls).hasValue(0);
    }

    @Test
    void malformedPathAndDiagnosticsCannotReachOwner() {
        reply(
                        client().get()
                                .uri(ROOT + "/1-1-1-1-1")
                                .header("Authorization", "Bearer " + valid())
                                .exchange())
                .expected(400);
        reply(
                        client().get()
                                .uri("/api/orchestration/sagas")
                                .header("Authorization", "Bearer " + valid())
                                .exchange())
                .expected(403);
        assertThat(owner.calls).hasValue(0);
    }

    @Test
    void currentReadAndHistoryReplayCannotBypassWithdrawnPolicyOrCreateSession() {
        for (String path : List.of(ROOT + "/" + PAYMENT, ROOT + "/" + PAYMENT + "/history")) {
            owner.allowed.set(true);
            var first =
                    reply(
                                    client().get()
                                            .uri(path)
                                            .header("Authorization", "Bearer " + valid())
                                            .header("X-Idempotency-Key", ACTION.toString())
                                            .exchange())
                            .expected(200);
            assertThat(first.headers.containsHeader("Set-Cookie")).isFalse();
            owner.allowed.set(false);
            reply(
                            client().get()
                                    .uri(path)
                                    .header("Authorization", "Bearer " + valid())
                                    .header("X-Idempotency-Key", ACTION.toString())
                                    .exchange())
                    .expected(403);
            reply(client().get().uri(path).exchange()).expected(401);
        }
    }

    @Test
    void everyCurrentAuthorityOperationHasNativeTransportOptOut() throws Exception {
        var type =
                Class.forName(
                        "io.fireflysoftware.experience.payments.web.managed.ManagedPaymentsExperienceController");
        var operations =
                Arrays.stream(type.getDeclaredMethods())
                        .filter(
                                m ->
                                        Set.of("create", "read", "history", "decide", "recover")
                                                .contains(m.getName()))
                        .toList();
        assertThat(operations).hasSize(5);
        assertThat(operations)
                .allSatisfy(
                        method ->
                                assertThat(
                                                method.isAnnotationPresent(
                                                        org.fireflyframework.web.idempotency
                                                                .annotation.DisableIdempotency
                                                                .class))
                                        .isTrue());
    }

    static final class Reply {
        final int status;
        final org.springframework.http.HttpHeaders headers;
        final byte[] body;

        Reply(WebTestClient.ResponseSpec response) {
            var result = response.returnResult(byte[].class);
            status = result.getStatus().value();
            headers = result.getResponseHeaders();
            var chunks =
                    result.getResponseBody().collectList().block(java.time.Duration.ofSeconds(5));
            var output = new java.io.ByteArrayOutputStream();
            for (byte[] chunk : chunks) output.writeBytes(chunk);
            body = output.toByteArray();
        }

        Reply expected(int expected) {
            assertThat(status).as("HTTP boundary status").isEqualTo(expected);
            return this;
        }
    }

    static Reply reply(WebTestClient.ResponseSpec response) {
        return new Reply(response);
    }

    @Profile("owned-payment-boundary-test")
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            excludeName = {
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration",
                "org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcRepositoriesAutoConfiguration",
                "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration",
                "org.fireflyframework.core.messaging.config.OrchestrationAutoConfiguration"
            })
    @ComponentScan(
            basePackageClasses = ManagedPaymentsExperienceWiring.class,
            excludeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.ANNOTATION,
                            classes = TestConfiguration.class))
    @Import(PaymentsJourneyController.class)
    static class BoundaryApp {
        @Bean
        PaymentJourneyService legacyBoundary() {
            return org.mockito.Mockito.mock(PaymentJourneyService.class);
        }
    }

    static final class Owner implements ManagedDomainPaymentsPort {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final AtomicReference<Create> input = new AtomicReference<>();

        View view() {
            return new View(
                    PAYMENT,
                    ACTION,
                    "owned",
                    SOURCE,
                    TARGET,
                    "12.30",
                    "USD",
                    2,
                    "native-policy",
                    300,
                    null,
                    1793000000000L,
                    "alice",
                    State.RECEIVED,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public Mono<Acceptance> create(String tenant, Create value) {
            return Mono.fromSupplier(
                    () -> {
                        calls.incrementAndGet();
                        input.set(value);
                        return new Acceptance(view(), false);
                    });
        }

        public Mono<View> read(String tenant, UUID payment) {
            return Mono.fromSupplier(
                    () -> {
                        calls.incrementAndGet();
                        return view();
                    });
        }

        public Mono<HistoryPage> history(String tenant, View v, long after, int limit) {
            calls.incrementAndGet();
            return Mono.just(new HistoryPage(List.of()));
        }

        public Mono<View> decide(String tenant, View v, Decision input) {
            calls.incrementAndGet();
            return Mono.just(view());
        }

        public Mono<View> recover(String tenant, View v, Recovery input) {
            calls.incrementAndGet();
            return Mono.just(view());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean
        @Primary
        Owner ownedBoundaryPort() {
            return new Owner();
        }

        @Bean
        @Primary
        PolicyDecisionPort ownedCurrentPolicy(Owner owner) {
            return (a, b, c, d) ->
                    Mono.just(
                            owner.allowed.get()
                                    ? org.fireflyframework.security.api.domain.Decision.permit()
                                    : org.fireflyframework.security.api.domain.Decision.deny(
                                            "withdrawn"));
        }
    }
}
