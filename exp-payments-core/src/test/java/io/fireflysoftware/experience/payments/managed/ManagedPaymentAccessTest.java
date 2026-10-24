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

import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

class ManagedPaymentAccessTest {
    static final UUID SOURCE = UUID.fromString("10000000-0000-4000-8000-000000000002");
    static final UUID TARGET = UUID.fromString("10000000-0000-4000-8000-000000000003");
    static final UUID PAYMENT = UUID.fromString("10000000-0000-4000-8000-000000000004");

    static Object access(PolicyDecisionPort policy, boolean strict) {
        var beans = new StaticListableBeanFactory();
        if (policy != null) beans.addBean("policy", policy);
        try {
            return Class.forName(
                            "io.fireflysoftware.experience.payments.managed.impl.ManagedPaymentAccessServiceImpl")
                    .getConstructor(ObjectProvider.class, boolean.class)
                    .newInstance(beans.getBeanProvider(PolicyDecisionPort.class), strict);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Current policy boundary is not implemented", error);
        }
    }

    static Object call(String tenant, Set<String> scopes, List<String> accounts, Instant expiry) {
        var principal =
                SecurityPrincipal.builder()
                        .issuer("https://owned-journey.invalid")
                        .subject("alice")
                        .tenantId(tenant)
                        .scopes(scopes)
                        .claims(Map.of("exp", expiry, "funds_accounts", accounts))
                        .build();
        try {
            return Class.forName(
                            "io.fireflysoftware.experience.payments.managed.ManagedPaymentContext$Call")
                    .getConstructor(SecurityPrincipal.class, String.class, Set.class)
                    .newInstance(
                            principal, "owned-test-bearer-never-log", Set.of("domain-managed"));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Current signed call boundary is not implemented", error);
        }
    }

    static Object valid() {
        return call(
                "owned",
                Set.of("payments:read"),
                List.of(SOURCE.toString(), TARGET.toString()),
                Instant.now().plusSeconds(30));
    }

    @SuppressWarnings("unchecked")
    static Mono<Object> check(Object access) {
        try {
            return (Mono<Object>)
                    access.getClass()
                            .getMethod(
                                    "authorize",
                                    String.class,
                                    String.class,
                                    UUID.class,
                                    UUID.class,
                                    UUID.class)
                            .invoke(access, "owned", "payments:read", PAYMENT, SOURCE, TARGET);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    static <T> Mono<T> with(Mono<T> work, Object call) {
        return work.contextWrite(
                context -> {
                    try {
                        return (Context)
                                Class.forName(
                                                "io.fireflysoftware.experience.payments.managed.ManagedPaymentContext")
                                        .getMethod("with", Context.class, call.getClass())
                                        .invoke(null, context, call);
                    } catch (ReflectiveOperationException error) {
                        throw new AssertionError(error);
                    }
                });
    }

    static void denied(Mono<?> work, int status) {
        assertThatThrownBy(() -> work.block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void requiresCurrentRequestAuthorityEvenWithPermittingPolicy() {
        denied(check(access((a, b, c, d) -> Mono.just(Decision.permit()), true)), 401);
    }

    @Test
    void unconfiguredOrNonstrictPolicyCannotGrant() {
        denied(with(check(access(null, true)), valid()), 403);
        denied(
                with(check(access((a, b, c, d) -> Mono.just(Decision.permit()), false)), valid()),
                403);
    }

    @Test
    void deniesMissingErrorDeniedOrUnhandledPolicyOutcomes() {
        for (PolicyDecisionPort p :
                List.<PolicyDecisionPort>of(
                        (a, b, c, d) -> Mono.empty(),
                        (a, b, c, d) -> Mono.error(new IllegalStateException("owned unavailable")),
                        (a, b, c, d) -> Mono.just(Decision.deny("withdrawn")),
                        (a, b, c, d) ->
                                Mono.just(
                                        Decision.permit(
                                                List.of(Obligation.of("step-up", Map.of()))))))
            denied(with(check(access(p, true)), valid()), 403);
    }

    @Test
    void bothEndpointAndPaymentPoliciesMustGrantExactReturnedScope() {
        for (String deniedResource :
                List.of("ACCOUNT:" + SOURCE, "ACCOUNT:" + TARGET, "PAYMENT:" + PAYMENT)) {
            PolicyDecisionPort policy =
                    (actor, action, resource, context) ->
                            Mono.just(
                                    resource.equals(deniedResource)
                                            ? Decision.deny("not owned")
                                            : Decision.permit());
            denied(with(check(access(policy, true)), valid()), 403);
        }
        Object current = valid();
        PolicyDecisionPort exact =
                (actor, action, resource, context) ->
                        Mono.just(
                                actor.subject().equals("alice")
                                                && action.equals("payments:read")
                                                && context.get("tenantId").equals("owned")
                                                && context.get("sourceAccountId")
                                                        .equals(SOURCE.toString())
                                                && context.get("destinationAccountId")
                                                        .equals(TARGET.toString())
                                                && context.get("paymentId")
                                                        .equals(PAYMENT.toString())
                                                && Set.of(
                                                                "ACCOUNT:" + SOURCE,
                                                                "ACCOUNT:" + TARGET,
                                                                "PAYMENT:" + PAYMENT)
                                                        .contains(resource)
                                        ? Decision.permit()
                                        : Decision.deny("wrong exact context"));
        assertThat(with(check(access(exact, true)), current).block()).isSameAs(current);
    }

    @Test
    void signedTenantScopeResourceAndExpiryAreMandatory() {
        var boundary = access((a, b, c, d) -> Mono.just(Decision.permit()), true);
        denied(
                with(
                        check(boundary),
                        call(
                                "other",
                                Set.of("payments:read"),
                                List.of(SOURCE.toString(), TARGET.toString()),
                                Instant.now().plusSeconds(30))),
                403);
        denied(
                with(
                        check(boundary),
                        call(
                                "owned",
                                Set.of("payments:create"),
                                List.of(SOURCE.toString(), TARGET.toString()),
                                Instant.now().plusSeconds(30))),
                403);
        denied(
                with(
                        check(boundary),
                        call(
                                "owned",
                                Set.of("payments:read"),
                                List.of(SOURCE.toString()),
                                Instant.now().plusSeconds(30))),
                403);
        denied(
                with(
                        check(boundary),
                        call(
                                "owned",
                                Set.of("payments:read"),
                                List.of(SOURCE.toString(), TARGET.toString()),
                                Instant.now().minusSeconds(1))),
                401);
    }

    @Test
    void rechecksPolicyOnEverySubscriptionAndExpiryAfterAsyncDecision() {
        var allowed = new AtomicBoolean(true);
        var boundary =
                access(
                        (a, b, c, d) ->
                                Mono.just(
                                        allowed.get()
                                                ? Decision.permit()
                                                : Decision.deny("withdrawn")),
                        true);
        var work = with(check(boundary), valid());
        assertThat(work.block()).isNotNull();
        allowed.set(false);
        denied(work, 403);
        var enteredPolicy = new AtomicBoolean(false);
        var expiry =
                call(
                        "owned",
                        Set.of("payments:read"),
                        List.of(SOURCE.toString(), TARGET.toString()),
                        Instant.now().plusMillis(500));
        denied(
                with(
                        check(
                                access(
                                        (a, b, c, d) -> {
                                            enteredPolicy.set(true);
                                            return Mono.delay(Duration.ofMillis(700))
                                                    .thenReturn(Decision.permit());
                                        },
                                        true)),
                        expiry),
                401);
        assertThat(enteredPolicy).isTrue();
    }

    @Test
    void callRepresentationCannotLeakCredential() {
        assertThat(String.valueOf(valid())).doesNotContain("owned-test-bearer-never-log");
    }
}
