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

import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

public final class ManagedPaymentContext {
    private ManagedPaymentContext() {}

    private static final Object CONTEXT = new Object();

    public static final class Call {
        private final SecurityPrincipal principal;
        private final String bearer;
        private final Set<String> audiences;

        public Call(SecurityPrincipal principal, String bearer, Set<String> audiences) {
            this.principal = Objects.requireNonNull(principal);
            this.bearer = Objects.requireNonNull(bearer);
            this.audiences = Set.copyOf(audiences);
        }

        public SecurityPrincipal principal() {
            return principal;
        }

        public String bearer() {
            return bearer;
        }

        public boolean audience(String audience) {
            return audiences.contains(audience);
        }

        @Override
        public String toString() {
            return "ManagedPaymentCall[redacted]";
        }
    }

    public static reactor.util.context.Context with(
            reactor.util.context.Context context, Call call) {
        return context.put(CONTEXT, call);
    }

    public static Mono<Call> current() {
        return Mono.deferContextual(
                context ->
                        context.hasKey(CONTEXT)
                                ? Mono.just(context.get(CONTEXT))
                                : Mono.error(fail(401, "TRUSTED_PAYMENT_CONTEXT_REQUIRED")));
    }

    public static void require(Call call, String tenant, String scope, UUID... accounts) {
        var actor = call.principal();
        Object exp = actor.claims().get("exp");
        if (actor.issuer() == null
                || actor.issuer().isBlank()
                || actor.subject() == null
                || actor.subject().isBlank()
                || actor.subject().length() > 255
                || !(exp instanceof Instant expiry)
                || !expiry.isAfter(Instant.now()))
            throw fail(401, "FINITE_TRUSTED_PAYMENT_PRINCIPAL_REQUIRED");
        if (tenant == null
                || !tenant.matches("[A-Za-z0-9_-]{1,64}")
                || !tenant.equals(actor.tenantId())
                || !actor.hasScope(scope)) throw fail(403, "CURRENT_PAYMENT_AUTHORITY_DENIED");
        Object resources = actor.claims().get("funds_accounts");
        if (!(resources instanceof List<?> grants) || grants.isEmpty() || grants.size() > 1000)
            throw fail(403, "BOUNDED_PAYMENT_RESOURCES_REQUIRED");
        for (Object grant : grants) {
            if (!(grant instanceof String value))
                throw fail(403, "VALID_PAYMENT_RESOURCES_REQUIRED");
            try {
                UUID.fromString(value);
            } catch (RuntimeException error) {
                throw fail(403, "VALID_PAYMENT_RESOURCES_REQUIRED");
            }
        }
        for (UUID account : accounts)
            if (account == null || !grants.contains(account.toString()))
                throw fail(403, "EXACT_PAYMENT_RESOURCE_DENIED");
    }

    public static ResponseStatusException fail(int status, String reason) {
        return new ResponseStatusException(HttpStatusCode.valueOf(status), reason);
    }
}
