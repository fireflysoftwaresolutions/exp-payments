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
 * The only port the journey calls. {@code SdkDomainPaymentsAdapter} is the adapter a future BPM replaces.
 * It calls {@code domain-payments-sdk}. It does not import the ledger, library-rails, library-psps,
 * the payments core SDK, or the domain coordinator.
 */
public interface DomainPaymentsPort {

    JourneyContract.TryResult tryPayment(JourneyContract.Request request);

    JourneyContract.Movement confirm(UUID paymentId, String response);

    JourneyContract.Movement cancel(UUID paymentId);

    JourneyContract.Movement readStatus(UUID paymentId);
}
