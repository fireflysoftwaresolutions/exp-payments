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

import java.util.UUID;

/**
 * A person makes an internal transfer, sends an outbound payment, sees an inbound credit, and reads status.
 * Each step is identity, then access, then {@link DomainPaymentsPort}.
 */
public final class PaymentJourney {

    public static final UUID PAYMENT_RESOURCE = UUID.fromString("10000000-0000-4000-8000-0000000000a1");

    private final IdentityPort identity;
    private final AccessPort access;
    private final DomainPaymentsPort domain;

    public PaymentJourney(IdentityPort identity, AccessPort access, DomainPaymentsPort domain) {
        this.identity = identity;
        this.access = access;
        this.domain = domain;
    }

    public JourneyContract.TryResult tryPayment(String username, JourneyContract.Request request) {
        require(username, AccessPort.Action.CREATE);
        return domain.tryPayment(request);
    }

    public JourneyContract.Movement confirm(String username, UUID paymentId, String response) {
        require(username, AccessPort.Action.CREATE);
        return domain.confirm(paymentId, response);
    }

    public JourneyContract.Movement cancel(String username, UUID paymentId) {
        require(username, AccessPort.Action.CREATE);
        return domain.cancel(paymentId);
    }

    public JourneyContract.Movement status(String username, UUID paymentId) {
        require(username, AccessPort.Action.READ);
        return domain.readStatus(paymentId);
    }

    private void require(String username, AccessPort.Action action) {
        IdentityPort.Subject subject = identity.resolve(username);
        AccessPort.Decision decision = access.check(new AccessPort.Request(
                subject.actorId(), action, AccessPort.ResourceType.PAYMENT, PAYMENT_RESOURCE));
        if (!decision.allowed()) {
            throw new AccessDeniedException(decision.reason() == null ? "ACTOR_NOT_ENTITLED" : decision.reason());
        }
    }
}
