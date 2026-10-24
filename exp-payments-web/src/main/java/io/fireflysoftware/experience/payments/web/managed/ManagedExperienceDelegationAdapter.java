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

import io.fireflysoftware.experience.payments.managed.ManagedPaymentDelegationPort;

import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

public final class ManagedExperienceDelegationAdapter implements ManagedPaymentDelegationPort {
    private final String mode;

    public ManagedExperienceDelegationAdapter(String mode) {
        this.mode = mode;
    }

    @Override
    public Mono<String> token(
            String audience, String tenant, Set<UUID> accounts, Set<String> scopes) {
        return current()
                .flatMap(
                        call -> {
                            if (!"validated-multi-audience".equals(mode))
                                return Mono.error(fail(503, "TRUSTED_PAYMENT_DELEGATION_REQUIRED"));
                            if (audience == null || audience.isBlank() || !call.audience(audience))
                                return Mono.error(fail(403, "PAYMENT_PEER_AUDIENCE_DENIED"));
                            if (accounts == null
                                    || accounts.isEmpty()
                                    || accounts.size() > 1000
                                    || scopes == null
                                    || scopes.isEmpty())
                                return Mono.error(fail(403, "EXACT_PAYMENT_DELEGATION_REQUIRED"));
                            for (String scope : scopes)
                                require(call, tenant, scope, accounts.toArray(UUID[]::new));
                            return Mono.just(call.bearer());
                        });
    }
}
