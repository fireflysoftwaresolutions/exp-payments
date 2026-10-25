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
import static org.mockito.Mockito.*;

import io.fireflysoftware.experience.payments.managed.*;
import io.fireflysoftware.experience.payments.managed.ManagedPaymentContracts.*;
import io.fireflysoftware.experience.payments.managed.impl.ManagedPaymentAccessServiceImpl;
import io.fireflysoftware.experience.payments.managed.impl.ManagedPaymentJourneyServiceImpl;

import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

class SdkManagedDomainPaymentsAdapterTest {
    static final UUID SOURCE = UUID.fromString("10000000-0000-4000-8000-000000000002"),
            TARGET = UUID.fromString("10000000-0000-4000-8000-000000000003"),
            ACTION = UUID.fromString("10000000-0000-4000-8000-000000000001"),
            PAYMENT = UUID.fromString("10000000-0000-4000-8000-000000000004");
    static final Create INPUT =
            new Create(ACTION, SOURCE, TARGET, new BigDecimal("12.30"), "USD", 300, null);
    static final View VIEW =
            new View(
                    PAYMENT,
                    ACTION,
                    "owned",
                    SOURCE,
                    TARGET,
                    "12.30",
                    "USD",
                    2,
                    "policy",
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

    static AnnotationConfigApplicationContext context(
            ManagedDomainPaymentsPort owner, PolicyDecisionPort policy, boolean strict) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "owned",
                                Map.of("firefly.security.resource-server.strict-policy", strict)));
        if (owner != null) context.registerBean(ManagedDomainPaymentsPort.class, () -> owner);
        if (policy != null) context.registerBean(PolicyDecisionPort.class, () -> policy);
        try {
            context.register(
                    Class.forName(
                            "io.fireflysoftware.experience.payments.web.managed.ManagedPaymentsExperienceWiring"));
        } catch (ClassNotFoundException error) {
            context.close();
            throw new AssertionError("Production managed service wiring is absent", error);
        }
        context.refresh();
        return context;
    }

    static <T> Mono<T> actor(Mono<T> work) {
        var principal =
                SecurityPrincipal.builder()
                        .issuer("https://owned.invalid")
                        .subject("alice")
                        .tenantId("owned")
                        .scopes(
                                Set.of(
                                        "payments:create",
                                        "payments:read",
                                        "payments:coordinate",
                                        "funds:read",
                                        "funds:reserve",
                                        "funds:reconcile"))
                        .claims(
                                Map.of(
                                        "exp",
                                        Instant.now().plusSeconds(30),
                                        "funds_accounts",
                                        List.of(SOURCE.toString(), TARGET.toString())))
                        .build();
        return work.contextWrite(
                c ->
                        ManagedPaymentContext.with(
                                c,
                                new ManagedPaymentContext.Call(
                                        principal, "owned-never-log", Set.of("domain"))));
    }

    @Test
    void productionBeansPublishOnlyBusinessInterfacesAndExecuteJourney() {
        var owner = mock(ManagedDomainPaymentsPort.class);
        when(owner.create("owned", INPUT)).thenReturn(Mono.just(new Acceptance(VIEW, false)));
        try (var context =
                context(
                        owner,
                        (a, b, c, d) ->
                                Mono.just(
                                        org.fireflyframework.security.api.domain.Decision.permit()),
                        true)) {
            assertThat(context.getBeansOfType(ManagedPaymentJourneyService.class)).hasSize(1);
            assertThat(context.getBeansOfType(ManagedPaymentAccessService.class)).hasSize(1);
            var journey = context.getBean(ManagedPaymentJourneyService.class);
            assertThat(journey).isInstanceOf(ManagedPaymentJourneyServiceImpl.class);
            assertThat(context.getBean(ManagedPaymentAccessService.class))
                    .isInstanceOf(ManagedPaymentAccessServiceImpl.class);
            assertThat(actor(journey.create("owned", INPUT)).block())
                    .isEqualTo(new Acceptance(VIEW, false));
            verify(owner).create("owned", INPUT);
        }
    }

    @Test
    void productionWiringDoesNotGrantWithoutStrictNativePolicy() {
        for (boolean strict : List.of(false, true)) {
            var owner = mock(ManagedDomainPaymentsPort.class);
            try (var context = context(owner, null, strict)) {
                assertThatThrownBy(
                                () ->
                                        actor(
                                                        context.getBean(
                                                                        ManagedPaymentJourneyService
                                                                                .class)
                                                                .create("owned", INPUT))
                                                .block())
                        .isInstanceOfSatisfying(
                                org.springframework.web.server.ResponseStatusException.class,
                                e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
                verifyNoInteractions(owner);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Mono<String> delegated(
            String mode, String audience, String tenant, Set<UUID> accounts, Set<String> scopes) {
        try {
            var type =
                    Class.forName(
                            "io.fireflysoftware.experience.payments.web.managed.ManagedExperienceDelegationAdapter");
            return (Mono<String>)
                    type.getMethod("token", String.class, String.class, Set.class, Set.class)
                            .invoke(
                                    type.getConstructor(String.class).newInstance(mode),
                                    audience,
                                    tenant,
                                    accounts,
                                    scopes);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Managed delegation adapter is absent", error);
        }
    }

    static <T> Mono<T> signed(Mono<T> work, String tenant, String subject, String bearer) {
        var principal =
                SecurityPrincipal.builder()
                        .issuer("https://owned.invalid")
                        .subject(subject)
                        .tenantId(tenant)
                        .scopes(Set.of("payments:read"))
                        .claims(
                                Map.of(
                                        "exp",
                                        Instant.now().plusSeconds(30),
                                        "funds_accounts",
                                        List.of(SOURCE.toString(), TARGET.toString())))
                        .build();
        return work.contextWrite(
                c ->
                        ManagedPaymentContext.with(
                                c,
                                new ManagedPaymentContext.Call(
                                        principal, bearer, Set.of("domain"))));
    }

    @Test
    void delegationRequiresConfiguredValidatedAudienceAndRequestLocalActor() {
        assertThatThrownBy(
                        () ->
                                signed(
                                                delegated(
                                                        "unconfigured",
                                                        "domain",
                                                        "owned",
                                                        Set.of(SOURCE, TARGET),
                                                        Set.of("payments:read")),
                                                "owned",
                                                "alice",
                                                "alice-credential")
                                        .block())
                .isInstanceOfSatisfying(
                        org.springframework.web.server.ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        assertThatThrownBy(
                        () ->
                                signed(
                                                delegated(
                                                        "validated-multi-audience",
                                                        "other",
                                                        "owned",
                                                        Set.of(SOURCE, TARGET),
                                                        Set.of("payments:read")),
                                                "owned",
                                                "alice",
                                                "alice-credential")
                                        .block())
                .isInstanceOfSatisfying(
                        org.springframework.web.server.ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void delegationRequiresExactTenantResourcesAndScope() {
        for (var request :
                List.of(
                        delegated(
                                "validated-multi-audience",
                                "domain",
                                "foreign",
                                Set.of(SOURCE, TARGET),
                                Set.of("payments:read")),
                        delegated(
                                "validated-multi-audience",
                                "domain",
                                "owned",
                                Set.of(PAYMENT),
                                Set.of("payments:read")),
                        delegated(
                                "validated-multi-audience",
                                "domain",
                                "owned",
                                Set.of(SOURCE, TARGET),
                                Set.of("payments:confirm")))) {
            assertThatThrownBy(() -> signed(request, "owned", "alice", "alice-credential").block())
                    .isInstanceOfSatisfying(
                            org.springframework.web.server.ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        }
    }

    @Test
    void concurrentDelegationNeverSharesActorTenantOrCredential() {
        var operation =
                delegated(
                        "validated-multi-audience",
                        "domain",
                        "owned",
                        Set.of(SOURCE, TARGET),
                        Set.of("payments:read"));
        var first =
                signed(
                        operation.delaySubscription(java.time.Duration.ofMillis(30)),
                        "owned",
                        "alice",
                        "alice-credential");
        var second = signed(operation, "owned", "bob", "bob-credential");
        var pair = Mono.zip(first, second).block();
        assertThat(pair.getT1()).isEqualTo("alice-credential");
        assertThat(pair.getT2()).isEqualTo("bob-credential");
        assertThatThrownBy(
                        () -> signed(operation, "foreign", "alice", "foreign-credential").block())
                .isInstanceOfSatisfying(
                        org.springframework.web.server.ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    static ManagedDomainPaymentsPort sdk(
            ManagedPaymentDelegationPort delegation, String base, boolean loopback) {
        try {
            return (ManagedDomainPaymentsPort)
                    Class.forName(
                                    "io.fireflysoftware.experience.payments.web.managed.SdkManagedDomainPaymentsAdapter")
                            .getConstructor(
                                    ManagedPaymentDelegationPort.class,
                                    String.class,
                                    String.class,
                                    boolean.class)
                            .newInstance(delegation, base, "domain", loopback);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Genuine domain SDK adapter is absent", error);
        }
    }

    @Test
    void unconfiguredOrUnsafePeerNeverRequestsCredential() {
        var delegated = new java.util.concurrent.atomic.AtomicInteger();
        ManagedPaymentDelegationPort delegation =
                (a, b, c, d) -> {
                    delegated.incrementAndGet();
                    return Mono.just("never-use");
                };
        for (String base :
                List.of(
                        "",
                        "http://example.org",
                        "https://user:password@example.org",
                        "https://example.org?token=private",
                        "https://example.org#fragment")) {
            assertThatThrownBy(() -> sdk(delegation, base, false).create("owned", INPUT).block())
                    .isInstanceOfSatisfying(
                            org.springframework.web.server.ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        }
        assertThat(delegated).hasValue(0);
    }

    @Test
    void genuineSdkMappingPreservesEveryFieldAndTrueAbsenceWithoutLedgerVersion() throws Exception {
        var instant = java.time.LocalDateTime.of(2026, 10, 23, 12, 0);
        var value =
                new io.fireflysoftware.domain.payments.sdk.model.View()
                        .paymentId(PAYMENT)
                        .createActionId(ACTION)
                        .tenant("owned")
                        .sourceAccountId(SOURCE)
                        .destinationAccountId(TARGET)
                        .amount("12.30")
                        .currency("USD")
                        .currencyScale(2)
                        .currencyPolicy("native-policy")
                        .ttlSeconds(300)
                        .customerNotAfterEpochMillis(1793000000001L)
                        .notAfterEpochMillis(1793000000000L)
                        .initiator("alice")
                        .state(
                                io.fireflysoftware.domain.payments.sdk.model.View.StateEnum
                                        .CANCELLED)
                        .version(9L)
                        .absenceReceiptId(ACTION)
                        .observedAt(instant)
                        .recoveryCode("ABSENT_CLOSED")
                        .ledgerState("ABSENT_CLOSED");
        var type =
                Class.forName(
                        "io.fireflysoftware.experience.payments.web.managed.SdkManagedDomainPaymentsAdapter");
        var method = type.getDeclaredMethod("view", value.getClass());
        method.setAccessible(true);
        var mapped = (View) method.invoke(null, value);
        assertThat(mapped)
                .isEqualTo(
                        new View(
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
                                1793000000001L,
                                1793000000000L,
                                "alice",
                                State.CANCELLED,
                                9,
                                null,
                                null,
                                null,
                                ACTION,
                                null,
                                instant.toInstant(java.time.ZoneOffset.UTC),
                                null,
                                "ABSENT_CLOSED",
                                "ABSENT_CLOSED",
                                null));
        assertThat(mapped.ledgerVersion()).isNull();
        value.reservationId(SOURCE)
                .postingId(TARGET)
                .reversalId(PAYMENT)
                .ledgerVersion(11L)
                .pendingEffectId(ACTION)
                .reservationExpiresAt(instant.plusSeconds(300));
        mapped = (View) method.invoke(null, value);
        assertThat(mapped.reservationId()).isEqualTo(SOURCE);
        assertThat(mapped.postingId()).isEqualTo(TARGET);
        assertThat(mapped.reversalId()).isEqualTo(PAYMENT);
        assertThat(mapped.ledgerVersion()).isEqualTo(11L);
        assertThat(mapped.pendingEffectId()).isEqualTo(ACTION);
        assertThat(mapped.reservationExpiresAt())
                .isEqualTo(instant.plusSeconds(300).toInstant(java.time.ZoneOffset.UTC));
    }

    @Test
    void productionDefaultsUseOnlyGenuineSdkPortAndUnconfiguredDelegationDenies() {
        try (var context =
                context(
                        null,
                        (a, b, c, d) ->
                                Mono.just(
                                        org.fireflyframework.security.api.domain.Decision.permit()),
                        true)) {
            assertThat(context.getBean(ManagedDomainPaymentsPort.class).getClass().getSimpleName())
                    .isEqualTo("SdkManagedDomainPaymentsAdapter");
            assertThat(
                            context.getBean(ManagedPaymentDelegationPort.class)
                                    .getClass()
                                    .getSimpleName())
                    .isEqualTo("ManagedExperienceDelegationAdapter");
            assertThatThrownBy(
                            () ->
                                    actor(
                                                    context.getBean(
                                                                    ManagedPaymentJourneyService
                                                                            .class)
                                                            .create("owned", INPUT))
                                            .block())
                    .isInstanceOfSatisfying(
                            org.springframework.web.server.ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        }
    }

    @Test
    void managedBusinessImplementationsLiveInImplSubpackage() throws Exception {
        assertThat(
                        ManagedPaymentJourneyService.class.isAssignableFrom(
                                Class.forName(
                                        "io.fireflysoftware.experience.payments.managed.impl.ManagedPaymentJourneyServiceImpl")))
                .isTrue();
        assertThat(
                        ManagedPaymentAccessService.class.isAssignableFrom(
                                Class.forName(
                                        "io.fireflysoftware.experience.payments.managed.impl.ManagedPaymentAccessServiceImpl")))
                .isTrue();
    }
}
