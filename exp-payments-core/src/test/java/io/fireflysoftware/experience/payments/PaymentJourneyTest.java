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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentJourneyTest {

    private static final UUID SOURCE = UUID.fromString("10000000-0000-4000-8000-000000000011");
    private static final UUID DESTINATION = UUID.fromString("10000000-0000-4000-8000-000000000012");
    private static final BigDecimal AMOUNT = new BigDecimal("25.00");

    @Test
    void sampleJourneyCompletesInternalOutboundInboundAndReadsStatus() {
        RecordingPort port = new RecordingPort();
        PaymentJourney journey = journey(port);

        JourneyContract.Movement internal = journey.transfer(IdentityPort.SAMPLE_USERNAME,
                request(JourneyContract.Scheme.INTERNAL_TRANSFER, "internal-1", SOURCE, DESTINATION, null));
        JourneyContract.Movement outbound = journey.transfer(IdentityPort.SAMPLE_USERNAME,
                request(JourneyContract.Scheme.DOMESTIC_OUTBOUND, "outbound-1", SOURCE, null, null));
        JourneyContract.Movement inbound = journey.inboundCredit(IdentityPort.SAMPLE_USERNAME,
                request(JourneyContract.Scheme.RAIL_INBOUND, null, null, DESTINATION, "TP-100"));
        JourneyContract.Movement internalStatus = journey.status(IdentityPort.SAMPLE_USERNAME, internal.paymentId());
        JourneyContract.Movement outboundStatus = journey.status(IdentityPort.SAMPLE_USERNAME, outbound.paymentId());
        JourneyContract.Movement inboundStatus = journey.status(IdentityPort.SAMPLE_USERNAME, inbound.paymentId());

        assertEquals(List.of("INTERNAL_TRANSFER", "DOMESTIC_OUTBOUND", "RAIL_INBOUND"), port.schemes);
        assertEquals(3, port.statusReads);
        assertEquals(JourneyContract.Route.INTERNAL_BOOK, internal.route());
        assertEquals(JourneyContract.Side.DEBIT, internal.posting().legs().get(0).side());
        assertEquals(JourneyContract.Side.CREDIT, internal.posting().legs().get(1).side());
        assertEquals(false, internal.thirdPartySynced());
        assertEquals(JourneyContract.Route.OUTBOUND_RAIL, outbound.route());
        assertTrue(outbound.thirdPartySynced());
        assertEquals(JourneyContract.Side.DEBIT, outbound.posting().legs().get(0).side());
        assertEquals(JourneyContract.Side.CREDIT, outbound.posting().legs().get(1).side());
        assertEquals(JourneyContract.Route.INBOUND_RAIL, inbound.route());
        assertEquals("TP-100", inbound.idempotencyKey());
        assertEquals(JourneyContract.Side.DEBIT, inbound.posting().legs().get(0).side());
        assertEquals(DESTINATION, inbound.posting().legs().get(1).accountId());
        assertEquals(internal.paymentId(), internalStatus.paymentId());
        assertEquals(outbound.paymentId(), outboundStatus.paymentId());
        assertEquals(inbound.paymentId(), inboundStatus.paymentId());
    }

    @Test
    void deniedActorStopsBeforeTheDomain() {
        RecordingPort port = new RecordingPort();
        PaymentJourney journey = journey(port);
        AccessDeniedException denied = assertThrows(AccessDeniedException.class, () -> journey.transfer(
                IdentityPort.DENIED_USERNAME,
                request(JourneyContract.Scheme.INTERNAL_TRANSFER, "denied-1", SOURCE, DESTINATION, null)));
        assertEquals("ACTOR_NOT_ENTITLED", denied.getMessage());
        assertTrue(port.schemes.isEmpty());
        assertEquals(0, port.statusReads);
    }

    private static PaymentJourney journey(DomainPaymentsPort port) {
        return new PaymentJourney(new SandboxIdentityAdapter(), new SandboxAccessAdapter(), port);
    }

    private static JourneyContract.Request request(JourneyContract.Scheme scheme,
                                                   String key,
                                                   UUID source,
                                                   UUID destination,
                                                   String thirdPartyReference) {
        return new JourneyContract.Request(scheme, key, AMOUNT, "EUR", IdentityPort.SAMPLE_ACTOR,
                UUID.fromString("10000000-0000-4000-8000-000000000021"), source, destination,
                null, null, null, UUID.fromString("10000000-0000-4000-8000-000000000031"),
                "Ada Lovelace", null, null, thirdPartyReference);
    }

    /**
     * Records the domain calls and returns the posting shape the hub publishes for each scheme.
     * The experience does not calculate the ledger; it returns the port result.
     */
    private static final class RecordingPort implements DomainPaymentsPort {
        private final List<String> schemes = new ArrayList<>();
        private int statusReads;

        @Override
        public JourneyContract.Movement move(JourneyContract.Request request) {
            schemes.add(request.scheme().name());
            JourneyContract.Route route = switch (request.scheme()) {
                case INTERNAL_TRANSFER, SPACE_TRANSFER -> JourneyContract.Route.INTERNAL_BOOK;
                case DOMESTIC_OUTBOUND, INTERNATIONAL_OUTBOUND -> JourneyContract.Route.OUTBOUND_RAIL;
                case PSP_PAYOUT -> JourneyContract.Route.OUTBOUND_PSP;
                case RAIL_INBOUND -> JourneyContract.Route.INBOUND_RAIL;
                case PSP_INBOUND -> JourneyContract.Route.INBOUND_PSP;
            };
            boolean synced = route != JourneyContract.Route.INTERNAL_BOOK;
            UUID debit = route == JourneyContract.Route.INBOUND_RAIL || route == JourneyContract.Route.INBOUND_PSP
                    ? UUID.fromString("00000000-0000-4000-8000-0000000000c2")
                    : request.sourceAccountId();
            UUID credit = route == JourneyContract.Route.OUTBOUND_RAIL || route == JourneyContract.Route.OUTBOUND_PSP
                    ? UUID.fromString("00000000-0000-4000-8000-0000000000c1")
                    : request.destinationAccountId();
            String key = request.thirdPartyReference() == null ? request.idempotencyKey() : request.thirdPartyReference();
            JourneyContract.Posting posting = new JourneyContract.Posting(UUID.randomUUID(), key, request.currency(), List.of(
                    new JourneyContract.Leg(debit, null, JourneyContract.Side.DEBIT, request.amount(), request.currency()),
                    new JourneyContract.Leg(credit, null, JourneyContract.Side.CREDIT, request.amount(), request.currency())));
            return new JourneyContract.Movement(UUID.randomUUID(), JourneyContract.Status.COMPLETED, request.scheme(),
                    route, key, request.thirdPartyReference(), synced, synced ? "SBX-" + key : null, null, posting, null);
        }

        @Override
        public JourneyContract.Movement readStatus(UUID paymentId) {
            statusReads++;
            return new JourneyContract.Movement(paymentId, JourneyContract.Status.COMPLETED, null, null,
                    null, null, false, null, null, null, null);
        }
    }
}
