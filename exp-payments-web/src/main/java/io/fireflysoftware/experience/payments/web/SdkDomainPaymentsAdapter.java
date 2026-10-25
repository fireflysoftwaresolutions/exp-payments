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

import io.fireflysoftware.domain.payments.sdk.api.PaymentsApi;
import io.fireflysoftware.domain.payments.sdk.invoker.ApiClient;
import io.fireflysoftware.domain.payments.sdk.model.Confirmation;
import io.fireflysoftware.domain.payments.sdk.model.LedgerLeg;
import io.fireflysoftware.domain.payments.sdk.model.LedgerPosting;
import io.fireflysoftware.domain.payments.sdk.model.MovementCommand;
import io.fireflysoftware.domain.payments.sdk.model.MovementResult;
import io.fireflysoftware.experience.payments.DomainPaymentsPort;
import io.fireflysoftware.experience.payments.JourneyContract;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Calls the payments domain through {@code domain-payments-sdk}. A future BPM replaces this
 * adapter. It does not call the payment core, the ledger, or a hand-written HTTP client.
 */
public final class SdkDomainPaymentsAdapter implements DomainPaymentsPort {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final PaymentsApi api;

    public SdkDomainPaymentsAdapter(String baseUrl) {
        ApiClient client = new ApiClient();
        client.setBasePath(strip(baseUrl));
        this(new PaymentsApi(client));
    }

    public SdkDomainPaymentsAdapter(PaymentsApi api) {
        this.api = api;
    }

    @Override
    public JourneyContract.TryResult tryPayment(JourneyContract.Request request) {
        MovementResult result = body(api.tryPayment(command(request), request.idempotencyKey()));
        return new JourneyContract.TryResult(
                result.getPaymentId(),
                result.getChallengeId(),
                result.getChallengeValue(),
                phase(result.getPhase()));
    }

    @Override
    public JourneyContract.Movement confirm(UUID paymentId, String response) {
        return movement(
                body(
                        api.confirmPayment(
                                paymentId,
                                new Confirmation().response(response),
                                paymentId.toString())));
    }

    @Override
    public JourneyContract.Movement cancel(UUID paymentId) {
        return movement(body(api.cancelPayment(paymentId, paymentId.toString())));
    }

    @Override
    public JourneyContract.Movement readStatus(UUID paymentId) {
        return movement(body(api.readPayment(paymentId, paymentId.toString())));
    }

    private static MovementCommand command(JourneyContract.Request request) {
        return new MovementCommand()
                .scheme(commandScheme(request.scheme()))
                .idempotencyKey(request.idempotencyKey())
                .amount(request.amount())
                .currency(request.currency())
                .partyId(request.partyId())
                .contractId(request.contractId())
                .sourceAccountId(request.sourceAccountId())
                .destinationAccountId(request.destinationAccountId())
                .sourceSpaceId(request.sourceSpaceId())
                .destinationSpaceId(request.destinationSpaceId())
                .spaceType(request.spaceType())
                .paymentMethodId(request.paymentMethodId())
                .externalAccountName(request.externalAccountName())
                .externalIban(request.externalIban())
                .externalBic(request.externalBic())
                .thirdPartyReference(request.thirdPartyReference());
    }

    private static JourneyContract.Movement movement(MovementResult result) {
        return new JourneyContract.Movement(
                result.getPaymentId(),
                status(result.getStatus()),
                scheme(result.getScheme()),
                route(result.getRoute()),
                result.getIdempotencyKey(),
                result.getThirdPartyReference(),
                Boolean.TRUE.equals(result.getThirdPartySynced()),
                result.getSyncReference(),
                failure(result.getFailure()),
                posting(result.getPosting()),
                posting(result.getReversal()),
                phase(result.getPhase()));
    }

    private static JourneyContract.Posting posting(LedgerPosting posting) {
        if (posting == null) {
            return null;
        }
        List<JourneyContract.Leg> legs = new ArrayList<>();
        if (posting.getLegs() != null) {
            for (LedgerLeg leg : posting.getLegs()) {
                legs.add(
                        new JourneyContract.Leg(
                                leg.getAccountId(),
                                leg.getAccountSpaceId(),
                                side(leg.getSide()),
                                leg.getAmount(),
                                leg.getCurrency()));
            }
        }
        return new JourneyContract.Posting(
                posting.getTransactionId(),
                posting.getIdempotencyKey(),
                posting.getCurrency(),
                List.copyOf(legs));
    }

    private static JourneyContract.Status status(MovementResult.StatusEnum value) {
        return value == null
                ? null
                : switch (value) {
                    case INITIATED -> JourneyContract.Status.INITIATED;
                    case COMPLETED -> JourneyContract.Status.COMPLETED;
                    case FAILED -> JourneyContract.Status.FAILED;
                    case UNMATCHED -> JourneyContract.Status.UNMATCHED;
                    case CANCELLED -> JourneyContract.Status.CANCELLED;
                };
    }

    private static JourneyContract.Scheme scheme(MovementResult.SchemeEnum value) {
        return value == null
                ? null
                : switch (value) {
                    case INTERNAL_TRANSFER -> JourneyContract.Scheme.INTERNAL_TRANSFER;
                    case SPACE_TRANSFER -> JourneyContract.Scheme.SPACE_TRANSFER;
                    case DOMESTIC_OUTBOUND -> JourneyContract.Scheme.DOMESTIC_OUTBOUND;
                    case INTERNATIONAL_OUTBOUND -> JourneyContract.Scheme.INTERNATIONAL_OUTBOUND;
                    case PSP_PAYOUT -> JourneyContract.Scheme.PSP_PAYOUT;
                    case RAIL_INBOUND -> JourneyContract.Scheme.RAIL_INBOUND;
                    case PSP_INBOUND -> JourneyContract.Scheme.PSP_INBOUND;
                };
    }

    private static JourneyContract.Route route(MovementResult.RouteEnum value) {
        return value == null
                ? null
                : switch (value) {
                    case INTERNAL_BOOK -> JourneyContract.Route.INTERNAL_BOOK;
                    case OUTBOUND_RAIL -> JourneyContract.Route.OUTBOUND_RAIL;
                    case OUTBOUND_PSP -> JourneyContract.Route.OUTBOUND_PSP;
                    case INBOUND_RAIL -> JourneyContract.Route.INBOUND_RAIL;
                    case INBOUND_PSP -> JourneyContract.Route.INBOUND_PSP;
                };
    }

    private static String phase(MovementResult.PhaseEnum value) {
        return value == null
                ? null
                : switch (value) {
                    case TRYING -> "TRYING";
                    case CONFIRMED -> "CONFIRMED";
                    case CANCELLED -> "CANCELLED";
                };
    }

    private static String failure(MovementResult.FailureEnum value) {
        return value == null
                ? null
                : switch (value) {
                    case VALIDATION -> "VALIDATION";
                    case LIMIT_EXCEEDED -> "LIMIT_EXCEEDED";
                    case CURRENCY_NOT_IN_MASTER -> "CURRENCY_NOT_IN_MASTER";
                    case CURRENCY_PAIR_NOT_CONFIGURED -> "CURRENCY_PAIR_NOT_CONFIGURED";
                    case SPACE_TYPE_NOT_CONFIGURED -> "SPACE_TYPE_NOT_CONFIGURED";
                    case ROUTE_NOT_CONFIGURED -> "ROUTE_NOT_CONFIGURED";
                    case UNBALANCED_POSTING -> "UNBALANCED_POSTING";
                    case POSITION_FAILED -> "POSITION_FAILED";
                    case SYNC_REJECTED -> "SYNC_REJECTED";
                    case RECORD_FAILED -> "RECORD_FAILED";
                    case COMPENSATED -> "COMPENSATED";
                    case NOT_FOUND -> "NOT_FOUND";
                };
    }

    private static JourneyContract.Side side(LedgerLeg.SideEnum value) {
        return value == null
                ? null
                : switch (value) {
                    case DEBIT -> JourneyContract.Side.DEBIT;
                    case CREDIT -> JourneyContract.Side.CREDIT;
                };
    }

    private static MovementCommand.SchemeEnum commandScheme(JourneyContract.Scheme value) {
        return value == null
                ? null
                : switch (value) {
                    case INTERNAL_TRANSFER -> MovementCommand.SchemeEnum.INTERNAL_TRANSFER;
                    case SPACE_TRANSFER -> MovementCommand.SchemeEnum.SPACE_TRANSFER;
                    case DOMESTIC_OUTBOUND -> MovementCommand.SchemeEnum.DOMESTIC_OUTBOUND;
                    case INTERNATIONAL_OUTBOUND ->
                            MovementCommand.SchemeEnum.INTERNATIONAL_OUTBOUND;
                    case PSP_PAYOUT -> MovementCommand.SchemeEnum.PSP_PAYOUT;
                    case RAIL_INBOUND -> MovementCommand.SchemeEnum.RAIL_INBOUND;
                    case PSP_INBOUND -> MovementCommand.SchemeEnum.PSP_INBOUND;
                };
    }

    private static <T> T body(Mono<T> call) {
        T value = call.block(TIMEOUT);
        if (value == null) {
            throw new IllegalStateException("The payments domain returned an empty response.");
        }
        return value;
    }

    private static String strip(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
