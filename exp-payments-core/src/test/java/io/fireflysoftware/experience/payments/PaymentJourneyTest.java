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

import io.fireflysoftware.experience.payments.impl.PaymentJourneyServiceImpl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentJourneyTest {

    private static final UUID SOURCE = UUID.fromString("10000000-0000-4000-8000-000000000011");
    private static final UUID DESTINATION = UUID.fromString("10000000-0000-4000-8000-000000000012");

    @Test
    void sampleJourneyConfirmsOutboundWithTheDeliveredChallenge() {
        RecordingPort port = new RecordingPort();
        PaymentJourneyService journey = new PaymentJourneyServiceImpl(new SandboxIdentityAdapter(), new SandboxAccessAdapter(), port);
        JourneyContract.TryResult tried = journey.tryPayment(IdentityPort.SAMPLE_USERNAME, outbound());
        assertNotNull(tried.challengeId());
        assertNotEquals("sandbox", tried.challengeValue());
        JourneyContract.Movement rejected = journey.confirm(IdentityPort.SAMPLE_USERNAME, tried.paymentId(), "sandbox");
        assertEquals("CANCELLED", rejected.phase());
        JourneyContract.TryResult second = journey.tryPayment(IdentityPort.SAMPLE_USERNAME, outbound());
        JourneyContract.Movement confirmed = journey.confirm(IdentityPort.SAMPLE_USERNAME, second.paymentId(), second.challengeValue());
        assertEquals("CONFIRMED", confirmed.phase());
        assertTrue(confirmed.thirdPartySynced());
        assertEquals(JourneyContract.Side.DEBIT, confirmed.posting().legs().get(0).side());
        assertEquals(2, port.confirms);
    }

    @Test
    void internalTryHasNoChallenge() {
        RecordingPort port = new RecordingPort();
        PaymentJourneyService journey = new PaymentJourneyServiceImpl(new SandboxIdentityAdapter(), new SandboxAccessAdapter(), port);
        JourneyContract.TryResult tried = journey.tryPayment(IdentityPort.SAMPLE_USERNAME, internal());
        assertNull(tried.challengeId());
        assertNull(tried.challengeValue());
    }

    @Test
    void deniedActorNeverReachesTry() {
        RecordingPort port = new RecordingPort();
        PaymentJourneyService journey = new PaymentJourneyServiceImpl(new SandboxIdentityAdapter(), new SandboxAccessAdapter(), port);
        AccessDeniedException denied = assertThrows(AccessDeniedException.class,
                () -> journey.tryPayment(IdentityPort.DENIED_USERNAME, outbound()));
        assertEquals("ACTOR_NOT_ENTITLED", denied.getMessage());
        assertEquals(0, port.tries);
    }

    private static JourneyContract.Request outbound() {
        return request(JourneyContract.Scheme.DOMESTIC_OUTBOUND, SOURCE, null);
    }

    private static JourneyContract.Request internal() {
        return request(JourneyContract.Scheme.INTERNAL_TRANSFER, SOURCE, DESTINATION);
    }

    private static JourneyContract.Request request(JourneyContract.Scheme scheme, UUID source, UUID destination) {
        return new JourneyContract.Request(scheme, "journey-1", new BigDecimal("25.00"), "EUR",
                UUID.fromString("10000000-0000-4000-8000-0000000000aa"),
                UUID.fromString("10000000-0000-4000-8000-000000000021"),
                source, destination, null, null, null,
                UUID.fromString("10000000-0000-4000-8000-000000000031"),
                "Beneficiary", null, null, null);
    }

    private static final class RecordingPort implements DomainPaymentsPort {
        private int tries;
        private int confirms;

        @Override
        public JourneyContract.TryResult tryPayment(JourneyContract.Request request) {
            tries++;
            boolean outbound = request.scheme() == JourneyContract.Scheme.DOMESTIC_OUTBOUND
                    || request.scheme() == JourneyContract.Scheme.INTERNATIONAL_OUTBOUND
                    || request.scheme() == JourneyContract.Scheme.PSP_PAYOUT;
            return new JourneyContract.TryResult(UUID.randomUUID(),
                    outbound ? UUID.randomUUID() : null,
                    outbound ? "482913" : null,
                    "TRYING");
        }

        @Override
        public JourneyContract.Movement confirm(UUID paymentId, String response) {
            confirms++;
            boolean accepted = "482913".equals(response);
            return new JourneyContract.Movement(paymentId, accepted ? JourneyContract.Status.COMPLETED : JourneyContract.Status.FAILED,
                    JourneyContract.Scheme.DOMESTIC_OUTBOUND,
                    accepted ? JourneyContract.Route.OUTBOUND_RAIL : null,
                    "journey-1", null, accepted, accepted ? "SBX-1" : null, accepted ? null : "VALIDATION",
                    accepted ? new JourneyContract.Posting(UUID.randomUUID(), "journey-1", "EUR", java.util.List.of(
                            new JourneyContract.Leg(SOURCE, null, JourneyContract.Side.DEBIT, new BigDecimal("25.00"), "EUR"),
                            new JourneyContract.Leg(UUID.fromString("44444444-4444-4444-8444-444444444441"), null,
                                    JourneyContract.Side.CREDIT, new BigDecimal("25.00"), "EUR"))) : null,
                    null, accepted ? "CONFIRMED" : "CANCELLED");
        }

        @Override
        public JourneyContract.Movement cancel(UUID paymentId) {
            return new JourneyContract.Movement(paymentId, JourneyContract.Status.FAILED, null, null, null, null,
                    false, null, null, null, null, "CANCELLED");
        }

        @Override
        public JourneyContract.Movement readStatus(UUID paymentId) {
            return new JourneyContract.Movement(paymentId, JourneyContract.Status.COMPLETED, null, null, null, null,
                    false, null, null, null, null, "CONFIRMED");
        }
    }

    @Test void businessJourneyHasServiceInterfaceAndImplementation() throws Exception {
        var service=Class.forName("io.fireflysoftware.experience.payments.PaymentJourneyService");
        assertTrue(service.isInterface());
        assertTrue(service.isAssignableFrom(Class.forName("io.fireflysoftware.experience.payments.impl.PaymentJourneyServiceImpl")));
    }
}
