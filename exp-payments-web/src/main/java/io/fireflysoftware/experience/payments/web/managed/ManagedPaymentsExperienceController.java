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

import io.fireflysoftware.experience.payments.managed.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.*;

@RestController
@RequestMapping("/api/v1/tenants/{tenant}/internal-payments")
@Tag(name = "Managed Payment Experiences")
public class ManagedPaymentsExperienceController {
    private final ManagedPaymentJourneyService journey;

    public ManagedPaymentsExperienceController(ManagedPaymentJourneyService journey) {
        this.journey = journey;
    }

    @DisableIdempotency
    @PostMapping(consumes = "application/json")
    @Operation(
            operationId = "createManagedPaymentExperience",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content = @Content(schema = @Schema(implementation = Create.class))))
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "New retained instruction; explicit financial state",
                content = @Content(schema = @Schema(implementation = View.class))),
        @ApiResponse(
                responseCode = "200",
                description = "Current authorized owner replay",
                content = @Content(schema = @Schema(implementation = View.class)))
    })
    public Mono<ResponseEntity<View>> create(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @Parameter(hidden = true) @RequestBody Mono<byte[]> raw) {
        return raw.defaultIfEmpty(new byte[0])
                .flatMap(
                        bytes ->
                                Mono.defer(
                                        () -> {
                                            var call = call(jwt);
                                            var input = ManagedPaymentContracts.decodeCreate(bytes);
                                            return journey.create(tenant, input)
                                                    .map(
                                                            a ->
                                                                    ResponseEntity.status(
                                                                                    a.replayed()
                                                                                            ? 200
                                                                                            : 201)
                                                                            .body(a.payment()))
                                                    .contextWrite(
                                                            c ->
                                                                    ManagedPaymentContext.with(
                                                                            c, call));
                                        }));
    }

    @DisableIdempotency
    @GetMapping("/{paymentId}")
    @Operation(operationId = "getManagedPaymentExperience")
    @ApiResponse(
            responseCode = "200",
            description = "Current authorized retained financial receipt",
            content = @Content(schema = @Schema(implementation = View.class)))
    public Mono<View> read(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable
                    String paymentId) {
        return Mono.defer(
                () ->
                        journey.read(tenant, payment(paymentId))
                                .contextWrite(c -> ManagedPaymentContext.with(c, call(jwt))));
    }

    @DisableIdempotency
    @GetMapping("/{paymentId}/history")
    @Operation(operationId = "getManagedPaymentExperienceHistory")
    @ApiResponse(
            responseCode = "200",
            description = "Bounded current-authorized retained business history",
            content = @Content(schema = @Schema(implementation = HistoryPage.class)))
    public Mono<HistoryPage> history(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable
                    String paymentId,
            @RequestParam(defaultValue = "-1") long afterVersion,
            @RequestParam(defaultValue = "25") int limit) {
        return Mono.defer(
                () ->
                        journey.history(tenant, payment(paymentId), afterVersion, limit)
                                .contextWrite(c -> ManagedPaymentContext.with(c, call(jwt))));
    }

    @DisableIdempotency
    @PostMapping(value = "/{paymentId}/decisions", consumes = "application/json")
    @Operation(
            operationId = "decideManagedPaymentExperience",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content = @Content(schema = @Schema(implementation = Decision.class))))
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Current authorized financial receipt",
                content = @Content(schema = @Schema(implementation = View.class))),
        @ApiResponse(
                responseCode = "202",
                description = "Decision retained with pending or uncertain effect",
                content = @Content(schema = @Schema(implementation = View.class)))
    })
    public Mono<ResponseEntity<View>> decide(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable
                    String paymentId,
            @Parameter(hidden = true) @RequestBody Mono<byte[]> raw) {
        return raw.defaultIfEmpty(new byte[0])
                .flatMap(
                        bytes ->
                                Mono.defer(
                                        () -> {
                                            var call = call(jwt);
                                            var input =
                                                    ManagedPaymentContracts.decodeDecision(bytes);
                                            return journey.decide(tenant, payment(paymentId), input)
                                                    .map(
                                                            ManagedPaymentsExperienceController
                                                                    ::response)
                                                    .contextWrite(
                                                            c ->
                                                                    ManagedPaymentContext.with(
                                                                            c, call));
                                        }));
    }

    @DisableIdempotency
    @PostMapping(value = "/{paymentId}/recovery", consumes = "application/json")
    @Operation(
            operationId = "recoverManagedPaymentExperience",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content = @Content(schema = @Schema(implementation = Recovery.class))))
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Current authorized financial receipt",
                content = @Content(schema = @Schema(implementation = View.class))),
        @ApiResponse(
                responseCode = "202",
                description = "Recovery retained with pending or uncertain effect",
                content = @Content(schema = @Schema(implementation = View.class)))
    })
    public Mono<ResponseEntity<View>> recover(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable
                    String paymentId,
            @Parameter(hidden = true) @RequestBody Mono<byte[]> raw) {
        return raw.defaultIfEmpty(new byte[0])
                .flatMap(
                        bytes ->
                                Mono.defer(
                                        () -> {
                                            var call = call(jwt);
                                            var input =
                                                    ManagedPaymentContracts.decodeRecovery(bytes);
                                            return journey.recover(
                                                            tenant, payment(paymentId), input)
                                                    .map(
                                                            ManagedPaymentsExperienceController
                                                                    ::response)
                                                    .contextWrite(
                                                            c ->
                                                                    ManagedPaymentContext.with(
                                                                            c, call));
                                        }));
    }

    private static ResponseEntity<View> response(View view) {
        return ResponseEntity.status(
                        view.pendingEffectId() != null || view.state() == State.RECOVERY_REQUIRED
                                ? 202
                                : 200)
                .body(view);
    }

    private static UUID payment(String raw) {
        try {
            if (raw == null
                    || !raw.matches(
                            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw fail(400, "EXACT_MANAGED_PAYMENT_REQUIRED");
            var id = UUID.fromString(raw);
            if (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0)
                throw fail(400, "EXACT_MANAGED_PAYMENT_REQUIRED");
            return id;
        } catch (IllegalArgumentException error) {
            throw fail(400, "EXACT_MANAGED_PAYMENT_REQUIRED");
        }
    }

    private static Call call(Jwt jwt) {
        if (jwt == null) throw fail(401, "TRUSTED_PAYMENT_CONTEXT_REQUIRED");
        try {
            String scopes = jwt.getClaimAsString("scope");
            var actor =
                    SecurityPrincipal.builder()
                            .issuer(jwt.getIssuer().toString())
                            .subject(jwt.getSubject())
                            .tenantId(jwt.getClaimAsString("tenant_id"))
                            .scopes(
                                    scopes == null
                                            ? Set.of()
                                            : new HashSet<>(Arrays.asList(scopes.split(" "))))
                            .claims(jwt.getClaims())
                            .build();
            return new Call(actor, jwt.getTokenValue(), Set.copyOf(jwt.getAudience()));
        } catch (RuntimeException error) {
            throw fail(401, "VALID_PAYMENT_PRINCIPAL_REQUIRED");
        }
    }
}
