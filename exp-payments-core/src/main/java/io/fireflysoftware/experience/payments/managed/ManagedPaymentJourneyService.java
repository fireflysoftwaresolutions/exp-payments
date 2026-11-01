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

import static io.fireflysoftware.experience.payments.managed.ManagedPaymentContracts.*;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ManagedPaymentJourneyService {
    Mono<Acceptance> create(String tenant, Create request);

    Mono<View> read(String tenant, UUID paymentId);

    Mono<View> decide(String tenant, UUID paymentId, Decision request);

    Mono<View> recover(String tenant, UUID paymentId, Recovery request);

    Mono<HistoryPage> history(String tenant, UUID paymentId, long after, int limit);
}
