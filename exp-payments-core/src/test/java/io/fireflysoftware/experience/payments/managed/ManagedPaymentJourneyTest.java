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

package io.fireflysoftware.experience.payments.managed;


import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

class ManagedPaymentJourneyTest {
    static final String ACTION = "10000000-0000-4000-8000-000000000001";
    static final String SOURCE = "10000000-0000-4000-8000-000000000002";
    static final String TARGET = "10000000-0000-4000-8000-000000000003";
    static final String CREATE =
            "{\"actionId\":\""
                    + ACTION
                    + "\",\"sourceAccountId\":\""
                    + SOURCE
                    + "\",\"destinationAccountId\":\""
                    + TARGET
                    + "\",\"amount\":12.30,\"currency\":\"USD\",\"ttlSeconds\":300}";
    static final String DECISION =
            "{\"actionId\":\"" + ACTION + "\",\"expectedVersion\":2,\"kind\":\"CONFIRM\"}";
    static final String RECOVERY = "{\"actionId\":\"" + ACTION + "\",\"expectedVersion\":2}";

    static Object decode(String method, String body) {
        try {
            var type =
                    Class.forName(
                            "io.fireflysoftware.experience.payments.managed.ManagedPaymentContracts");
            return type.getMethod(method, byte[].class)
                    .invoke(
                            null,
                            (Object) (body == null ? null : body.getBytes(StandardCharsets.UTF_8)));
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Managed raw request decoder is not implemented", error);
        }
    }

    static Object field(Object value, String name) throws Exception {
        return value.getClass().getMethod(name).invoke(value);
    }

    static void rejects(String method, String body) {
        assertThatThrownBy(() -> decode(method, body))
                .as(String.valueOf(body))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void preservesExactBusinessIdentityAndOptionalCustomerDeadline() throws Exception {
        var value = decode("decodeCreate", CREATE);
        assertThat(field(value, "actionId")).isEqualTo(UUID.fromString(ACTION));
        assertThat(field(value, "sourceAccountId")).isEqualTo(UUID.fromString(SOURCE));
        assertThat(field(value, "destinationAccountId")).isEqualTo(UUID.fromString(TARGET));
        assertThat((BigDecimal) field(value, "amount")).isEqualByComparingTo("12.30");
        assertThat(field(value, "currency")).isEqualTo("USD");
        assertThat(field(value, "ttlSeconds")).isEqualTo(300);
        assertThat(field(value, "customerNotAfterEpochMillis")).isNull();
        var bounded =
                decode(
                        "decodeCreate",
                        CREATE.replace("}", ",\"customerNotAfterEpochMillis\":1793000000000}"));
        assertThat(field(bounded, "customerNotAfterEpochMillis")).isEqualTo(1793000000000L);
        assertThat(field(decode("decodeCreate", CREATE.replace("USD", "usd")), "currency"))
                .isEqualTo("USD");
    }

    @Test
    void rejectsUnknownDuplicateMissingAndTrailingInput() {
        var invalid =
                new ArrayList<>(
                        List.of(
                                "[]",
                                "null",
                                "1",
                                "",
                                CREATE + " {}",
                                CREATE.replace("}", ",\"state\":\"COMPLETED\"}"),
                                CREATE.replace("}", ",\"ttlSeconds\":300}"),
                                CREATE.replace("}", ",\"actionId\":\"" + ACTION + "\"}"),
                                CREATE.replace("\"amount\":12.30,", ""),
                                CREATE.replace("\"ttlSeconds\":300", "\"unknown\":300"),
                                CREATE.replace("}", ",\"extra\":{\"x\":1,\"x\":2}}")));
        invalid.add(null);
        invalid.add(" ".repeat(8193));
        for (String raw : invalid) rejects("decodeCreate", raw);
    }

    @Test
    void rejectsUuidCoercionAliasesAndInvalidPair() {
        for (String token :
                List.of(
                        "1",
                        "true",
                        "null",
                        "{}",
                        "[]",
                        "\"1-1-1-1-1\"",
                        "\"00000000-0000-0000-0000-000000000000\"",
                        "\" " + ACTION + "\""))
            rejects("decodeCreate", CREATE.replace("\"" + ACTION + "\"", token));
        rejects("decodeCreate", CREATE.replace(TARGET, SOURCE));
    }

    @Test
    void rejectsIntegerAndMonetaryCoercionWithoutInventingCurrencyScale() {
        for (String token :
                List.of("\"300\"", "300.0", "3e2", "true", "null", "0", "901", "2147483648"))
            rejects(
                    "decodeCreate",
                    CREATE.replace("\"ttlSeconds\":300", "\"ttlSeconds\":" + token));
        for (String token : List.of("\"12.30\"", "true", "null", "{}", "0", "-1", "1e20", "1e-19"))
            rejects("decodeCreate", CREATE.replace("\"amount\":12.30", "\"amount\":" + token));
        for (String token :
                List.of(
                        "\"1793000000000\"",
                        "1793000000000.0",
                        "true",
                        "-1",
                        "9223372036854775808"))
            rejects(
                    "decodeCreate",
                    CREATE.replace("}", ",\"customerNotAfterEpochMillis\":" + token + "}"));
        rejects("decodeCreate", CREATE.replace("\"USD\"", "123"));
    }

    @Test
    void decisionRequiresExactCurrentVersionActionAndKnownKind() throws Exception {
        var value = decode("decodeDecision", DECISION);
        assertThat(field(value, "expectedVersion")).isEqualTo(2L);
        assertThat(field(value, "actionId")).isEqualTo(UUID.fromString(ACTION));
        assertThat(String.valueOf(field(value, "kind"))).isEqualTo("CONFIRM");
        for (String raw :
                List.of(
                        DECISION.replace("\"expectedVersion\":2,", ""),
                        DECISION.replace(":2", ":2.0"),
                        DECISION.replace(":2", ":\"2\""),
                        DECISION.replace("CONFIRM", "UNKNOWN_DECISION"),
                        DECISION.replace("}", ",\"expectedVersion\":2}"),
                        DECISION.replace("}", ",\"postingId\":\"" + SOURCE + "\"}")))
            rejects("decodeDecision", raw);
    }

    @Test
    void recoveryRequiresNativeVersionAndStableActionWithoutCallerReceipt() throws Exception {
        var value = decode("decodeRecovery", RECOVERY);
        assertThat(field(value, "expectedVersion")).isEqualTo(2L);
        assertThat(field(value, "actionId")).isEqualTo(UUID.fromString(ACTION));
        for (String raw :
                List.of(
                        RECOVERY.replace(":2", ":-1"),
                        RECOVERY.replace(":2", ":2.0"),
                        RECOVERY.replace(":2", ":9223372036854775808"),
                        RECOVERY.replace("}", ",\"expectedVersion\":2}"),
                        RECOVERY.replace("}", ",\"ledgerState\":\"COMMITTED\"}")))
            rejects("decodeRecovery", raw);
    }

    static final UUID PAYMENT = UUID.fromString("10000000-0000-4000-8000-000000000004");
    static final long DEADLINE = 1793000000000L;

    static ManagedPaymentContracts.View ownerView(
            String amount, String policy, long deadline, long version) {
        return new ManagedPaymentContracts.View(
                PAYMENT,
                UUID.fromString(ACTION),
                "owned",
                UUID.fromString(SOURCE),
                UUID.fromString(TARGET),
                amount,
                "USD",
                2,
                policy,
                300,
                null,
                deadline,
                "alice",
                ManagedPaymentContracts.State.RESERVED,
                version,
                UUID.fromString("10000000-0000-4000-8000-000000000005"),
                UUID.fromString("10000000-0000-4000-8000-000000000006"),
                null,
                null,
                1L,
                java.time.Instant.ofEpochMilli(DEADLINE - 300000),
                null,
                null,
                "HELD",
                java.time.Instant.ofEpochMilli(DEADLINE));
    }

    static final class Owner implements ManagedDomainPaymentsPort {
        ManagedPaymentContracts.View current = ownerView("12.30", "native-policy", DEADLINE, 2),
                after = current;
        int creates, reads, decisions;
        ManagedPaymentContracts.Decision decision;
        ManagedPaymentContracts.Recovery recovery;
        java.util.List<ManagedPaymentContracts.HistoryEvent> events = new ArrayList<>();

        public reactor.core.publisher.Mono<ManagedPaymentContracts.Acceptance> create(
                String t, ManagedPaymentContracts.Create in) {
            creates++;
            return reactor.core.publisher.Mono.just(
                    new ManagedPaymentContracts.Acceptance(current, true));
        }

        public reactor.core.publisher.Mono<ManagedPaymentContracts.View> read(String t, UUID id) {
            reads++;
            return reactor.core.publisher.Mono.just(current);
        }

        public reactor.core.publisher.Mono<ManagedPaymentContracts.HistoryPage> history(
                String t, ManagedPaymentContracts.View v, long after, int limit) {
            return reactor.core.publisher.Mono.just(
                    new ManagedPaymentContracts.HistoryPage(events));
        }

        public reactor.core.publisher.Mono<ManagedPaymentContracts.View> decide(
                String t, ManagedPaymentContracts.View v, ManagedPaymentContracts.Decision in) {
            decisions++;
            decision = in;
            return reactor.core.publisher.Mono.just(after);
        }

        public reactor.core.publisher.Mono<ManagedPaymentContracts.View> recover(
                String t, ManagedPaymentContracts.View v, ManagedPaymentContracts.Recovery in) {
            recovery = in;
            return reactor.core.publisher.Mono.just(after);
        }
    }

    static Object journey(
            Owner owner, org.fireflyframework.security.spi.PolicyDecisionPort policy) {
        try {
            return Class.forName(
                            "io.fireflysoftware.experience.payments.managed.impl.ManagedPaymentJourneyServiceImpl")
                    .getConstructor(
                            ManagedDomainPaymentsPort.class, ManagedPaymentAccessService.class)
                    .newInstance(owner, ManagedPaymentAccessTest.access(policy, true));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Managed journey is not implemented", error);
        }
    }

    @SuppressWarnings("unchecked")
    static reactor.core.publisher.Mono<Object> operation(
            Object journey, String method, Class<?>[] types, Object... arguments) {
        try {
            return (reactor.core.publisher.Mono<Object>)
                    journey.getClass().getMethod(method, types).invoke(journey, arguments);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    static Object actor() {
        return ManagedPaymentAccessTest.call(
                "owned",
                Set.of(
                        "payments:create",
                        "payments:read",
                        "payments:coordinate",
                        "payments:confirm",
                        "payments:cancel",
                        "payments:reverse",
                        "payments:recover",
                        "funds:read",
                        "funds:reserve",
                        "funds:commit",
                        "funds:release",
                        "funds:reverse",
                        "funds:reconcile",
                        "funds:expire"),
                List.of(SOURCE, TARGET),
                java.time.Instant.now().plusSeconds(30));
    }

    static org.fireflyframework.security.spi.PolicyDecisionPort permit() {
        return (a, b, c, d) ->
                reactor.core.publisher.Mono.just(
                        org.fireflyframework.security.api.domain.Decision.permit());
    }

    static reactor.core.publisher.Mono<Object> create(Object journey) {
        return operation(
                journey,
                "create",
                new Class<?>[] {String.class, ManagedPaymentContracts.Create.class},
                "owned",
                ManagedPaymentContracts.decodeCreate(CREATE.getBytes(StandardCharsets.UTF_8)));
    }

    static reactor.core.publisher.Mono<Object> read(Object journey) {
        return operation(
                journey, "read", new Class<?>[] {String.class, UUID.class}, "owned", PAYMENT);
    }

    @Test
    void currentPolicyDenialPreventsFinancialCommand() {
        Owner owner = new Owner();
        var journey =
                journey(
                        owner,
                        (a, b, c, d) ->
                                reactor.core.publisher.Mono.just(
                                        org.fireflyframework.security.api.domain.Decision.deny(
                                                "withdrawn")));
        ManagedPaymentAccessTest.denied(
                ManagedPaymentAccessTest.with(create(journey), actor()), 403);
        assertThat(owner.creates).isZero();
    }

    @Test
    void replaysRetainedOwnerIdentityAndNeverRecomputesFixedDeadline() {
        Owner owner = new Owner();
        var journey = journey(owner, permit());
        var result =
                (ManagedPaymentContracts.Acceptance)
                        ManagedPaymentAccessTest.with(create(journey), actor()).block();
        assertThat(result.replayed()).isTrue();
        assertThat(result.payment().paymentId()).isEqualTo(PAYMENT);
        assertThat(result.payment().notAfterEpochMillis()).isEqualTo(DEADLINE);
        assertThat(owner.creates).isEqualTo(1);
    }

    @Test
    void rejectsChangedOwnerIntentBeforeReturningAcceptance() {
        for (var altered :
                List.of(
                        ownerView("13.30", "native-policy", DEADLINE, 2),
                        ownerView("12.30", "", DEADLINE, 2))) {
            Owner owner = new Owner();
            owner.current = altered;
            var journey = journey(owner, permit());
            ManagedPaymentAccessTest.denied(
                    ManagedPaymentAccessTest.with(create(journey), actor()), 409);
        }
    }

    @Test
    void preservesOriginalDecisionActionAndVersionOnCurrentReplay() {
        Owner owner = new Owner();
        owner.current = ownerView("12.30", "native-policy", DEADLINE, 8);
        owner.after = owner.current;
        var journey = journey(owner, permit());
        var input =
                new ManagedPaymentContracts.Decision(
                        UUID.fromString(ACTION), 2, ManagedPaymentContracts.DecisionKind.CONFIRM);
        var work =
                operation(
                        journey,
                        "decide",
                        new Class<?>[] {
                            String.class, UUID.class, ManagedPaymentContracts.Decision.class
                        },
                        "owned",
                        PAYMENT,
                        input);
        assertThat(ManagedPaymentAccessTest.with(work, actor()).block()).isEqualTo(owner.current);
        assertThat(owner.decision.actionId()).isEqualTo(UUID.fromString(ACTION));
        assertThat(owner.decision.expectedVersion()).isEqualTo(2);
    }

    @Test
    void reverseDecisionKeepsOriginalActionVersionAndIndependentCurrentPermission() {
        Owner owner = new Owner();
        owner.current = ownerView("12.30", "native-policy", DEADLINE, 8);
        owner.after = owner.current;
        var actions = new ArrayList<String>();
        var journey = journey(owner, (principal, action, resource, context) -> {
            actions.add(action);
            return reactor.core.publisher.Mono.just(org.fireflyframework.security.api.domain.Decision.permit());
        });
        var input = (ManagedPaymentContracts.Decision) decode("decodeDecision", DECISION.replace("CONFIRM", "REVERSE"));
        var work = operation(journey, "decide", new Class<?>[] {
                String.class, UUID.class, ManagedPaymentContracts.Decision.class}, "owned", PAYMENT, input);
        assertThat(ManagedPaymentAccessTest.with(work, actor()).block()).isEqualTo(owner.current);
        assertThat(owner.decision.actionId()).isEqualTo(UUID.fromString(ACTION));
        assertThat(owner.decision.expectedVersion()).isEqualTo(2);
        assertThat(owner.decision.kind().name()).isEqualTo("REVERSE");
        assertThat(actions).contains("payments:reverse").doesNotContain("payments:cancel");
    }

    @Test
    void laterResponseCannotChangeRetainedPolicyOrDeadline() {
        for (var after :
                List.of(
                        ownerView("12.30", "other-policy", DEADLINE, 3),
                        ownerView("12.30", "native-policy", DEADLINE + 1, 3))) {
            Owner owner = new Owner();
            owner.after = after;
            var journey = journey(owner, permit());
            var work =
                    operation(
                            journey,
                            "recover",
                            new Class<?>[] {
                                String.class, UUID.class, ManagedPaymentContracts.Recovery.class
                            },
                            "owned",
                            PAYMENT,
                            new ManagedPaymentContracts.Recovery(UUID.fromString(ACTION), 2));
            ManagedPaymentAccessTest.denied(ManagedPaymentAccessTest.with(work, actor()), 409);
        }
    }

    @Test
    void currentReadAndHistoryCannotReturnAfterPermissionWithdrawal() {
        Owner owner = new Owner();
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var journey =
                journey(
                        owner,
                        (a, b, c, d) ->
                                reactor.core.publisher.Mono.just(
                                        allowed.get()
                                                ? org.fireflyframework.security.api.domain.Decision
                                                        .permit()
                                                : org.fireflyframework.security.api.domain.Decision
                                                        .deny("withdrawn")));
        var work = ManagedPaymentAccessTest.with(read(journey), actor());
        assertThat(work.block()).isEqualTo(owner.current);
        allowed.set(false);
        ManagedPaymentAccessTest.denied(work, 403);
    }

    @Test
    void historyIsBoundedOrderedAndImmutable() {
        Owner owner = new Owner();
        owner.events.add(
                new ManagedPaymentContracts.HistoryEvent(
                        1,
                        "CLAIM",
                        "alice",
                        java.time.Instant.ofEpochMilli(DEADLINE - 1),
                        UUID.fromString(ACTION),
                        null,
                        ManagedPaymentContracts.State.RESERVED,
                        null,
                        null));
        var journey = journey(owner, permit());
        var page =
                (ManagedPaymentContracts.HistoryPage)
                        ManagedPaymentAccessTest.with(
                                        operation(
                                                journey,
                                                "history",
                                                new Class<?>[] {
                                                    String.class, UUID.class, long.class, int.class
                                                },
                                                "owned",
                                                PAYMENT,
                                                -1L,
                                                25),
                                        actor())
                                .block();
        owner.events.clear();
        assertThat(page.items()).hasSize(1);
        assertThatThrownBy(() -> page.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        owner.events.add(
                new ManagedPaymentContracts.HistoryEvent(
                        0,
                        "OLD",
                        "alice",
                        java.time.Instant.ofEpochMilli(DEADLINE),
                        UUID.fromString(ACTION),
                        null,
                        ManagedPaymentContracts.State.RESERVED,
                        null,
                        null));
        var invalid =
                operation(
                        journey,
                        "history",
                        new Class<?>[] {String.class, UUID.class, long.class, int.class},
                        "owned",
                        PAYMENT,
                        0L,
                        25);
        ManagedPaymentAccessTest.denied(ManagedPaymentAccessTest.with(invalid, actor()), 409);
    }
}
