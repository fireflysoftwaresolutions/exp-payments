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

package io.fireflysoftware.experience.payments.managed.impl;

import static io.fireflysoftware.experience.payments.managed.ManagedPaymentContext.*;

import io.fireflysoftware.experience.payments.managed.*;

import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.springframework.beans.factory.ObjectProvider;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

public final class ManagedPaymentAccessServiceImpl implements ManagedPaymentAccessService {
    private final ObjectProvider<PolicyDecisionPort> policies;
    private final boolean strict;

    public ManagedPaymentAccessServiceImpl(
            ObjectProvider<PolicyDecisionPort> policies, boolean strict) {
        this.policies = Objects.requireNonNull(policies);
        this.strict = strict;
    }

    private Mono<PolicyDecisionPort> policy() {
        return Mono.defer(
                        () -> {
                            if (!strict)
                                return Mono.error(fail(403, "CURRENT_PAYMENT_POLICY_REQUIRED"));
                            return Mono.justOrEmpty(policies.getIfAvailable())
                                    .switchIfEmpty(
                                            Mono.error(
                                                    fail(403, "CURRENT_PAYMENT_POLICY_REQUIRED")));
                        })
                .onErrorMap(error -> fail(403, "CURRENT_PAYMENT_POLICY_REQUIRED"));
    }

    public Mono<Void> configured() {
        return policy().then();
    }

    public Mono<Call> authorize(
            String tenant, String action, UUID payment, UUID source, UUID destination) {
        return current()
                .flatMap(
                        call -> {
                            if (source == null
                                    || destination == null
                                    || source.equals(destination)
                                    || !Set.of(
                                                    "payments:create",
                                                    "payments:read",
                                                    "payments:confirm",
                                                    "payments:cancel",
                                                    "payments:recover")
                                            .contains(action))
                                return Mono.error(fail(400, "EXACT_PAYMENT_POLICY_SCOPE_REQUIRED"));
                            require(call, tenant, action, source, destination);
                            var fields = new HashMap<String, Object>();
                            fields.put("tenantId", tenant);
                            fields.put("sourceAccountId", source.toString());
                            fields.put("destinationAccountId", destination.toString());
                            fields.put(
                                    "scope", payment == null ? "TENANT_ACCOUNT" : "TENANT_PAYMENT");
                            if (payment != null) fields.put("paymentId", payment.toString());
                            var context = Map.copyOf(fields);
                            return policy().flatMap(
                                            port -> {
                                                Mono<Void> checks =
                                                        check(
                                                                        port,
                                                                        call,
                                                                        tenant,
                                                                        action,
                                                                        "ACCOUNT:" + source,
                                                                        context,
                                                                        source,
                                                                        destination)
                                                                .then(
                                                                        check(
                                                                                port,
                                                                                call,
                                                                                tenant,
                                                                                action,
                                                                                "ACCOUNT:"
                                                                                        + destination,
                                                                                context,
                                                                                source,
                                                                                destination));
                                                if (payment != null)
                                                    checks =
                                                            checks.then(
                                                                    check(
                                                                            port,
                                                                            call,
                                                                            tenant,
                                                                            action,
                                                                            "PAYMENT:" + payment,
                                                                            context,
                                                                            source,
                                                                            destination));
                                                return checks.then(
                                                        Mono.fromCallable(
                                                                () -> {
                                                                    require(
                                                                            call,
                                                                            tenant,
                                                                            action,
                                                                            source,
                                                                            destination);
                                                                    return call;
                                                                }));
                                            });
                        });
    }

    private Mono<Void> check(
            PolicyDecisionPort policy,
            Call call,
            String tenant,
            String action,
            String resource,
            Map<String, Object> context,
            UUID... accounts) {
        return Mono.defer(
                () -> {
                    require(call, tenant, action, accounts);
                    return Mono.defer(
                                    () ->
                                            policy.authorize(
                                                    call.principal(), action, resource, context))
                            .timeout(Duration.ofSeconds(4))
                            .filter(
                                    decision ->
                                            decision.granted() && decision.obligations().isEmpty())
                            .switchIfEmpty(Mono.error(fail(403, "CURRENT_PAYMENT_POLICY_DENIED")))
                            .onErrorMap(error -> fail(403, "CURRENT_PAYMENT_POLICY_DENIED"))
                            .then(Mono.fromRunnable(() -> require(call, tenant, action, accounts)));
                });
    }
}
