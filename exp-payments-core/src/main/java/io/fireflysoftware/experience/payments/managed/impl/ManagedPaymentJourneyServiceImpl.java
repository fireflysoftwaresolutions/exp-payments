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
import static io.fireflysoftware.experience.payments.managed.ManagedPaymentContracts.*;

import io.fireflysoftware.experience.payments.managed.*;

import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;

public final class ManagedPaymentJourneyServiceImpl implements ManagedPaymentJourneyService {
    private final ManagedDomainPaymentsPort owner;
    private final ManagedPaymentAccessService access;

    public ManagedPaymentJourneyServiceImpl(
            ManagedDomainPaymentsPort owner, ManagedPaymentAccessService access) {
        this.owner = Objects.requireNonNull(owner);
        this.access = Objects.requireNonNull(access);
    }

    @Override
    public Mono<Acceptance> create(String tenant, Create input) {
        return Mono.defer(
                () -> {
                    if (input == null
                            || !uuid(input.actionId())
                            || !uuid(input.sourceAccountId())
                            || !uuid(input.destinationAccountId())
                            || input.sourceAccountId().equals(input.destinationAccountId())
                            || input.amount() == null
                            || input.amount().signum() <= 0
                            || input.amount().compareTo(new BigDecimal("1e20")) >= 0
                            || input.amount().stripTrailingZeros().scale() > 18
                            || input.currency() == null
                            || !input.currency().matches("[A-Za-z]{3}")
                            || input.ttlSeconds() < 1
                            || input.ttlSeconds() > 900
                            || (input.customerNotAfterEpochMillis() != null
                                    && input.customerNotAfterEpochMillis() <= 0))
                        return Mono.error(fail(400, "EXACT_MANAGED_PAYMENT_CREATE_REQUIRED"));
                    return access.authorize(
                                    tenant,
                                    "payments:create",
                                    null,
                                    input.sourceAccountId(),
                                    input.destinationAccountId())
                            .flatMap(
                                    call -> {
                                        scopes(
                                                call,
                                                tenant,
                                                input.sourceAccountId(),
                                                input.destinationAccountId(),
                                                "payments:read",
                                                "payments:coordinate",
                                                "funds:read",
                                                "funds:reserve",
                                                "funds:reconcile");
                                        return safe(() -> owner.create(tenant, input))
                                                .flatMap(
                                                        result -> {
                                                            var value =
                                                                    valid(
                                                                            tenant,
                                                                            null,
                                                                            result.payment());
                                                            if (!value.createActionId()
                                                                            .equals(
                                                                                    input
                                                                                            .actionId())
                                                                    || !value.sourceAccountId()
                                                                            .equals(
                                                                                    input
                                                                                            .sourceAccountId())
                                                                    || !value.destinationAccountId()
                                                                            .equals(
                                                                                    input
                                                                                            .destinationAccountId())
                                                                    || new BigDecimal(
                                                                                            value
                                                                                                    .amount())
                                                                                    .compareTo(
                                                                                            input
                                                                                                    .amount())
                                                                            != 0
                                                                    || !value.currency()
                                                                            .equals(
                                                                                    input.currency()
                                                                                            .toUpperCase(
                                                                                                    Locale
                                                                                                            .ROOT))
                                                                    || value.ttlSeconds()
                                                                            != input.ttlSeconds()
                                                                    || !Objects.equals(
                                                                            value
                                                                                    .customerNotAfterEpochMillis(),
                                                                            input
                                                                                    .customerNotAfterEpochMillis()))
                                                                return Mono.error(inconsistent());
                                                            return access.authorize(
                                                                            tenant,
                                                                            "payments:create",
                                                                            value.paymentId(),
                                                                            value.sourceAccountId(),
                                                                            value
                                                                                    .destinationAccountId())
                                                                    .thenReturn(result);
                                                        });
                                    });
                });
    }

    @Override
    public Mono<View> read(String tenant, UUID payment) {
        return protectedView(tenant, payment, "payments:read");
    }

    @Override
    public Mono<View> decide(String tenant, UUID payment, Decision input) {
        return Mono.defer(
                () -> {
                    if (input == null
                            || !uuid(input.actionId())
                            || input.kind() == null
                            || input.expectedVersion() < 0)
                        return Mono.error(fail(400, "EXACT_MANAGED_PAYMENT_DECISION_REQUIRED"));
                    String action =
                            switch (input.kind()) {
                                case CONFIRM -> "payments:confirm";
                                case CANCEL -> "payments:cancel";
                                case REVERSE -> "payments:reverse";
                            };
                    return protectedView(tenant, payment, action)
                            .flatMap(
                                    before ->
                                            current()
                                                    .flatMap(
                                                            call -> {
                                                                scopes(
                                                                        call,
                                                                        tenant,
                                                                        before.sourceAccountId(),
                                                                        before
                                                                                .destinationAccountId(),
                                                                        "payments:coordinate",
                                                                        "funds:read",
                                                                        "funds:reconcile",
                                                                        switch (input.kind()) {
                                                                            case CONFIRM -> "funds:commit";
                                                                            case CANCEL -> "funds:release";
                                                                            case REVERSE -> "funds:reverse";
                                                                        });
                                                                // The retained owner action decides
                                                                // replay/CAS against the original
                                                                // expected version.
                                                                return safe(() ->
                                                                                owner.decide(
                                                                                        tenant,
                                                                                        before,
                                                                                        input))
                                                                        .flatMap(
                                                                                after ->
                                                                                        returned(
                                                                                                tenant,
                                                                                                action,
                                                                                                before,
                                                                                                after));
                                                            }));
                });
    }

    @Override
    public Mono<View> recover(String tenant, UUID payment, Recovery input) {
        return Mono.defer(
                () -> {
                    if (input == null || !uuid(input.actionId()) || input.expectedVersion() < 0)
                        return Mono.error(fail(400, "EXACT_MANAGED_PAYMENT_RECOVERY_REQUIRED"));
                    return protectedView(tenant, payment, "payments:recover")
                            .flatMap(
                                    before ->
                                            current()
                                                    .flatMap(
                                                            call -> {
                                                                scopes(
                                                                        call,
                                                                        tenant,
                                                                        before.sourceAccountId(),
                                                                        before
                                                                                .destinationAccountId(),
                                                                        "payments:coordinate",
                                                                        "funds:read",
                                                                        "funds:reconcile");
                                                                if (before.pendingEffectId() == null
                                                                        && "HELD"
                                                                                .equals(
                                                                                        before
                                                                                                .ledgerState()))
                                                                    require(
                                                                            call,
                                                                            tenant,
                                                                            "funds:expire",
                                                                            before
                                                                                    .sourceAccountId(),
                                                                            before
                                                                                    .destinationAccountId());
                                                                return safe(() ->
                                                                                owner.recover(
                                                                                        tenant,
                                                                                        before,
                                                                                        input))
                                                                        .flatMap(
                                                                                after ->
                                                                                        returned(
                                                                                                tenant,
                                                                                                "payments:recover",
                                                                                                before,
                                                                                                after));
                                                            }));
                });
    }

    @Override
    public Mono<HistoryPage> history(String tenant, UUID payment, long after, int limit) {
        return Mono.defer(
                () -> {
                    if (after < -1 || limit < 1 || limit > 100)
                        return Mono.error(fail(400, "BOUNDED_PAYMENT_HISTORY_REQUIRED"));
                    return protectedView(tenant, payment, "payments:read")
                            .flatMap(
                                    view ->
                                            safe(() -> owner.history(tenant, view, after, limit))
                                                    .flatMap(
                                                            page -> {
                                                                if (page.items() == null
                                                                        || page.items().size()
                                                                                > limit)
                                                                    return Mono.error(
                                                                            inconsistent());
                                                                long previous = after;
                                                                for (var event : page.items()) {
                                                                    if (event == null
                                                                            || event.version()
                                                                                    <= previous
                                                                            || event.version() < 0
                                                                            || event.type() == null
                                                                            || event.type()
                                                                                    .isBlank()
                                                                            || event.actor() == null
                                                                            || event.actor()
                                                                                    .isBlank()
                                                                            || event.recordedAt()
                                                                                    == null
                                                                            || event.state()
                                                                                    == null)
                                                                        return Mono.error(
                                                                                inconsistent());
                                                                    previous = event.version();
                                                                }
                                                                var retained =
                                                                        new HistoryPage(
                                                                                List.copyOf(
                                                                                        page
                                                                                                .items()));
                                                                return access.authorize(
                                                                                tenant,
                                                                                "payments:read",
                                                                                payment,
                                                                                view
                                                                                        .sourceAccountId(),
                                                                                view
                                                                                        .destinationAccountId())
                                                                        .thenReturn(retained);
                                                            }));
                });
    }

    private Mono<View> protectedView(String tenant, UUID payment, String action) {
        return Mono.defer(
                () -> {
                    if (!uuid(payment))
                        return Mono.error(fail(400, "EXACT_MANAGED_PAYMENT_REQUIRED"));
                    return current()
                            .flatMap(
                                    call -> {
                                        require(call, tenant, action);
                                        require(call, tenant, "payments:read");
                                        return access.configured()
                                                .then(safe(() -> owner.read(tenant, payment)))
                                                .map(value -> valid(tenant, payment, value))
                                                .flatMap(
                                                        value ->
                                                                access.authorize(
                                                                                tenant,
                                                                                "payments:read",
                                                                                payment,
                                                                                value
                                                                                        .sourceAccountId(),
                                                                                value
                                                                                        .destinationAccountId())
                                                                        .then(
                                                                                action.equals(
                                                                                                "payments:read")
                                                                                        ? Mono.just(
                                                                                                call)
                                                                                        : access
                                                                                                .authorize(
                                                                                                        tenant,
                                                                                                        action,
                                                                                                        payment,
                                                                                                        value
                                                                                                                .sourceAccountId(),
                                                                                                        value
                                                                                                                .destinationAccountId()))
                                                                        .thenReturn(value));
                                    });
                });
    }

    private Mono<View> returned(String tenant, String action, View before, View after) {
        valid(tenant, before.paymentId(), after);
        if (!sameIntent(before, after) || after.version() < before.version())
            return Mono.error(inconsistent());
        return access.authorize(
                        tenant,
                        action,
                        after.paymentId(),
                        after.sourceAccountId(),
                        after.destinationAccountId())
                .thenReturn(after);
    }

    private static boolean sameIntent(View a, View b) {
        return Objects.equals(a.createActionId(), b.createActionId())
                && Objects.equals(a.sourceAccountId(), b.sourceAccountId())
                && Objects.equals(a.destinationAccountId(), b.destinationAccountId())
                && Objects.equals(a.amount(), b.amount())
                && Objects.equals(a.currency(), b.currency())
                && a.currencyScale() == b.currencyScale()
                && Objects.equals(a.currencyPolicy(), b.currencyPolicy())
                && a.ttlSeconds() == b.ttlSeconds()
                && Objects.equals(a.customerNotAfterEpochMillis(), b.customerNotAfterEpochMillis())
                && a.notAfterEpochMillis() == b.notAfterEpochMillis()
                && Objects.equals(a.initiator(), b.initiator());
    }

    private static View valid(String tenant, UUID payment, View v) {
        if (v == null
                || !uuid(v.paymentId())
                || !uuid(v.createActionId())
                || !Objects.equals(tenant, v.tenant())
                || (payment != null && !payment.equals(v.paymentId()))
                || !uuid(v.sourceAccountId())
                || !uuid(v.destinationAccountId())
                || v.sourceAccountId().equals(v.destinationAccountId())
                || v.currency() == null
                || !v.currency().matches("[A-Z]{3}")
                || v.currencyScale() < 0
                || v.currencyScale() > 18
                || v.currencyPolicy() == null
                || v.currencyPolicy().isBlank()
                || v.currencyPolicy().length() > 255
                || v.ttlSeconds() < 1
                || v.ttlSeconds() > 900
                || v.notAfterEpochMillis() <= 0
                || (v.customerNotAfterEpochMillis() != null
                        && (v.customerNotAfterEpochMillis() <= 0
                                || v.notAfterEpochMillis() > v.customerNotAfterEpochMillis()))
                || v.initiator() == null
                || v.initiator().isBlank()
                || v.state() == null
                || v.version() < 0
                || v.amount() == null) throw inconsistent();
        try {
            var amount = new BigDecimal(v.amount());
            if (amount.signum() <= 0
                    || amount.compareTo(new BigDecimal("1e20")) >= 0
                    || amount.scale() != v.currencyScale()
                    || !amount.toPlainString().equals(v.amount())) throw inconsistent();
        } catch (NumberFormatException error) {
            throw inconsistent();
        }
        return v;
    }

    private static void scopes(
            Call call, String tenant, UUID source, UUID target, String... scopes) {
        for (String scope : scopes) require(call, tenant, scope, source, target);
    }

    private static boolean uuid(UUID value) {
        return value != null
                && (value.getMostSignificantBits() != 0 || value.getLeastSignificantBits() != 0);
    }

    private static ResponseStatusException inconsistent() {
        return fail(409, "INCONSISTENT_MANAGED_PAYMENT_RECEIPT");
    }

    private static <T> Mono<T> safe(Supplier<Mono<T>> operation) {
        return Mono.defer(operation)
                .switchIfEmpty(Mono.error(inconsistent()))
                .onErrorMap(
                        error -> {
                            if (error instanceof ResponseStatusException known
                                    && Set.of(400, 401, 403, 404, 409, 503)
                                            .contains(known.getStatusCode().value()))
                                return fail(
                                        known.getStatusCode().value(),
                                        "MANAGED_PAYMENT_OWNER_REJECTED");
                            return fail(503, "MANAGED_PAYMENT_OWNER_UNAVAILABLE");
                        });
    }
}
