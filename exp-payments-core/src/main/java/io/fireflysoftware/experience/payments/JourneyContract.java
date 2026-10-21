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

package io.fireflysoftware.experience.payments;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Experience contract. Names match the domain hub JSON. This module does not import the domain saga.
 */
public final class JourneyContract {

    private JourneyContract() {
    }

    public enum Scheme {
        INTERNAL_TRANSFER,
        SPACE_TRANSFER,
        DOMESTIC_OUTBOUND,
        INTERNATIONAL_OUTBOUND,
        PSP_PAYOUT,
        RAIL_INBOUND,
        PSP_INBOUND
    }

    public enum Status {
        INITIATED,
        COMPLETED,
        FAILED,
        UNMATCHED
    }

    public enum Route {
        INTERNAL_BOOK,
        OUTBOUND_RAIL,
        OUTBOUND_PSP,
        INBOUND_RAIL,
        INBOUND_PSP
    }

    public enum Side {
        DEBIT,
        CREDIT
    }

    public record Leg(UUID accountId, UUID accountSpaceId, Side side, BigDecimal amount, String currency) {
    }

    public record Posting(UUID transactionId, String idempotencyKey, String currency, List<Leg> legs) {
    }

    public record Request(
            Scheme scheme,
            String idempotencyKey,
            BigDecimal amount,
            String currency,
            UUID partyId,
            UUID contractId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            UUID sourceSpaceId,
            UUID destinationSpaceId,
            String spaceType,
            UUID paymentMethodId,
            String externalAccountName,
            String externalIban,
            String externalBic,
            String thirdPartyReference
    ) {
    }

    public record TryResult(UUID paymentId, UUID challengeId, String challengeValue, String phase) {
    }

    public record Movement(
            UUID paymentId,
            Status status,
            Scheme scheme,
            Route route,
            String idempotencyKey,
            String thirdPartyReference,
            boolean thirdPartySynced,
            String syncReference,
            String failure,
            Posting posting,
            Posting reversal,
            String phase
    ) {
    }
}
