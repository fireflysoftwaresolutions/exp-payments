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

import static io.fireflysoftware.experience.payments.managed.ManagedPaymentContext.*;
import static io.fireflysoftware.experience.payments.managed.ManagedPaymentContracts.*;

import io.fireflysoftware.domain.payments.sdk.api.ManagedPaymentJourneysApi;
import io.fireflysoftware.domain.payments.sdk.invoker.ApiClient;
import io.fireflysoftware.experience.payments.managed.*;

import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;

public final class SdkManagedDomainPaymentsAdapter implements ManagedDomainPaymentsPort {
    private final ManagedPaymentDelegationPort delegation;
    private final String base, audience;
    private final boolean loopback;

    public SdkManagedDomainPaymentsAdapter(
            ManagedPaymentDelegationPort delegation,
            String base,
            String audience,
            boolean loopback) {
        this.delegation = Objects.requireNonNull(delegation);
        this.base = base;
        this.audience = audience;
        this.loopback = loopback;
    }

    private Mono<ManagedPaymentJourneysApi> client(
            String tenant, Set<UUID> accounts, Set<String> scopes) {
        return Mono.defer(
                () -> {
                    if (!valid(base, loopback))
                        return Mono.error(fail(503, "CONFIGURED_PAYMENT_DOMAIN_REQUIRED"));
                    return delegation
                            .token(audience, tenant, accounts, scopes)
                            .timeout(Duration.ofSeconds(4))
                            .switchIfEmpty(
                                    Mono.error(fail(503, "CURRENT_PAYMENT_DELEGATION_REQUIRED")))
                            .flatMap(
                                    token ->
                                            current()
                                                    .map(
                                                            call -> {
                                                                for (String scope : scopes)
                                                                    require(
                                                                            call,
                                                                            tenant,
                                                                            scope,
                                                                            accounts.toArray(
                                                                                    UUID[]::new));
                                                                var web =
                                                                        ApiClient
                                                                                .buildWebClientBuilder()
                                                                                .codecs(
                                                                                        c ->
                                                                                                c.defaultCodecs()
                                                                                                        .maxInMemorySize(
                                                                                                                1024
                                                                                                                        * 1024))
                                                                                .build();
                                                                var api =
                                                                        new ApiClient(web)
                                                                                .setBasePath(base);
                                                                api.addDefaultHeader(
                                                                        "Authorization",
                                                                        "Bearer " + token);
                                                                return new ManagedPaymentJourneysApi(
                                                                        api);
                                                            }));
                });
    }

    private Mono<ManagedPaymentJourneysApi> existing(String tenant) {
        return current()
                .flatMap(
                        call -> {
                            require(call, tenant, "payments:read");
                            var grants =
                                    ((List<?>) call.principal().claims().get("funds_accounts"))
                                            .stream()
                                                    .map(Object::toString)
                                                    .map(UUID::fromString)
                                                    .collect(java.util.stream.Collectors.toSet());
                            return client(tenant, grants, Set.of("payments:read"));
                        });
    }

    @Override
    public Mono<Acceptance> create(String tenant, Create in) {
        return safe(
                () ->
                        client(
                                        tenant,
                                        Set.of(in.sourceAccountId(), in.destinationAccountId()),
                                        Set.of(
                                                "payments:create",
                                                "payments:read",
                                                "payments:coordinate",
                                                "funds:read",
                                                "funds:reserve",
                                                "funds:reconcile"))
                                .flatMap(
                                        api ->
                                                api.createManagedPaymentJourneyWithHttpInfo(
                                                        tenant,
                                                        new io.fireflysoftware.domain.payments.sdk
                                                                        .model.Create()
                                                                .actionId(in.actionId())
                                                                .sourceAccountId(
                                                                        in.sourceAccountId())
                                                                .destinationAccountId(
                                                                        in.destinationAccountId())
                                                                .amount(in.amount())
                                                                .currency(in.currency())
                                                                .ttlSeconds(in.ttlSeconds())
                                                                .customerNotAfterEpochMillis(
                                                                        in
                                                                                .customerNotAfterEpochMillis())))
                                .map(
                                        response -> {
                                            int status = response.getStatusCode().value();
                                            if (status != 200 && status != 201)
                                                throw fail(
                                                        503, "EXACT_PAYMENT_ACCEPTANCE_REQUIRED");
                                            return new Acceptance(
                                                    view(response.getBody()), status == 200);
                                        }));
    }

    @Override
    public Mono<View> read(String tenant, UUID payment) {
        return safe(
                () ->
                        existing(tenant)
                                .flatMap(api -> api.getManagedPaymentJourney(tenant, payment))
                                .map(SdkManagedDomainPaymentsAdapter::view));
    }

    @Override
    public Mono<View> decide(String tenant, View before, Decision in) {
        String scope = switch (in.kind()) {
            case CONFIRM -> "payments:confirm";
            case CANCEL -> "payments:cancel";
            case REVERSE -> "payments:reverse";
        };
        return safe(
                () ->
                        client(
                                        tenant,
                                        Set.of(
                                                before.sourceAccountId(),
                                                before.destinationAccountId()),
                                        Set.of(
                                                scope,
                                                "payments:read",
                                                "payments:coordinate",
                                                "funds:read",
                                                "funds:reconcile",
                                                switch (in.kind()) {
                                                    case CONFIRM -> "funds:commit";
                                                    case CANCEL -> "funds:release";
                                                    case REVERSE -> "funds:reverse";
                                                }))
                                .flatMap(
                                        api ->
                                                api.decideManagedPaymentJourney(
                                                        tenant,
                                                        before.paymentId(),
                                                        new io.fireflysoftware.domain.payments.sdk
                                                                        .model.Decision()
                                                                .actionId(in.actionId())
                                                                .kind(
                                                                        io.fireflysoftware.domain
                                                                                .payments.sdk.model
                                                                                .Decision.KindEnum
                                                                                .fromValue(
                                                                                        in.kind()
                                                                                                .name()))
                                                                .expectedVersion(
                                                                        in.expectedVersion())))
                                .map(SdkManagedDomainPaymentsAdapter::view));
    }

    @Override
    public Mono<View> recover(String tenant, View before, Recovery in) {
        return safe(
                () ->
                        client(
                                        tenant,
                                        Set.of(
                                                before.sourceAccountId(),
                                                before.destinationAccountId()),
                                        Set.of(
                                                "payments:recover",
                                                "payments:read",
                                                "payments:coordinate",
                                                "funds:read",
                                                "funds:reconcile"))
                                .flatMap(
                                        api ->
                                                api.recoverManagedPaymentJourney(
                                                        tenant,
                                                        before.paymentId(),
                                                        new io.fireflysoftware.domain.payments.sdk
                                                                        .model.Recovery()
                                                                .actionId(in.actionId())
                                                                .expectedVersion(
                                                                        in.expectedVersion())))
                                .map(SdkManagedDomainPaymentsAdapter::view));
    }

    @Override
    public Mono<HistoryPage> history(String tenant, View before, long after, int limit) {
        return safe(
                () ->
                        client(
                                        tenant,
                                        Set.of(
                                                before.sourceAccountId(),
                                                before.destinationAccountId()),
                                        Set.of("payments:read"))
                                .flatMap(
                                        api ->
                                                api.getManagedPaymentJourneyHistory(
                                                        tenant, before.paymentId(), after, limit))
                                .map(
                                        page -> {
                                            if (page.getItems() == null
                                                    || page.getItems().size() > limit)
                                                throw fail(409, "INCONSISTENT_PAYMENT_HISTORY");
                                            return new HistoryPage(
                                                    page.getItems().stream()
                                                            .map(
                                                                    e -> {
                                                                        if (e.getVersion() == null
                                                                                || e.getState()
                                                                                        == null)
                                                                            throw fail(
                                                                                    409,
                                                                                    "INCONSISTENT_PAYMENT_HISTORY");
                                                                        return new HistoryEvent(
                                                                                e.getVersion(),
                                                                                e.getType(),
                                                                                e.getActor(),
                                                                                instant(
                                                                                        e
                                                                                                .getRecordedAt()),
                                                                                e.getActionId(),
                                                                                e.getEffectId(),
                                                                                State.valueOf(
                                                                                        e.getState()
                                                                                                .getValue()),
                                                                                e.getPostingId(),
                                                                                e.getReversalId());
                                                                    })
                                                            .toList());
                                        }));
    }

    static View view(io.fireflysoftware.domain.payments.sdk.model.View v) {
        if (v == null
                || v.getCurrencyScale() == null
                || v.getTtlSeconds() == null
                || v.getNotAfterEpochMillis() == null
                || v.getState() == null
                || v.getVersion() == null) throw fail(409, "INCONSISTENT_PAYMENT_RECEIPT");
        return new View(
                v.getPaymentId(),
                v.getCreateActionId(),
                v.getTenant(),
                v.getSourceAccountId(),
                v.getDestinationAccountId(),
                v.getAmount(),
                v.getCurrency(),
                v.getCurrencyScale(),
                v.getCurrencyPolicy(),
                v.getTtlSeconds(),
                v.getCustomerNotAfterEpochMillis(),
                v.getNotAfterEpochMillis(),
                v.getInitiator(),
                State.valueOf(v.getState().getValue()),
                v.getVersion(),
                v.getReservationId(),
                v.getPostingId(),
                v.getReversalId(),
                v.getAbsenceReceiptId(),
                v.getLedgerVersion(),
                instant(v.getObservedAt()),
                v.getPendingEffectId(),
                v.getRecoveryCode(),
                v.getLedgerState(),
                instant(v.getReservationExpiresAt()));
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static <T> Mono<T> safe(Supplier<Mono<T>> call) {
        return Mono.defer(call)
                .switchIfEmpty(Mono.error(fail(503, "EMPTY_PAYMENT_DOMAIN_RECEIPT")))
                .timeout(Duration.ofSeconds(15))
                .onErrorMap(
                        error -> {
                            int status =
                                    error instanceof ResponseStatusException r
                                            ? r.getStatusCode().value()
                                            : error instanceof WebClientResponseException r
                                                    ? r.getStatusCode().value()
                                                    : 503;
                            return fail(
                                    Set.of(400, 401, 403, 404, 409).contains(status) ? status : 503,
                                    "MANAGED_PAYMENT_DOMAIN_REJECTED");
                        });
    }

    private static boolean valid(String value, boolean loopback) {
        try {
            var uri = java.net.URI.create(value);
            return uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getFragment() == null
                    && ("https".equals(uri.getScheme())
                            || loopback
                                    && "http".equals(uri.getScheme())
                                    && Set.of("127.0.0.1", "localhost", "::1", "[::1]")
                                            .contains(uri.getHost()));
        } catch (RuntimeException error) {
            return false;
        }
    }
}
