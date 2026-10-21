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

import io.fireflysoftware.experience.payments.AccessDeniedException;
import io.fireflysoftware.experience.payments.JourneyContract;
import io.fireflysoftware.experience.payments.PaymentJourney;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentsJourneyController {

    private final PaymentJourney journey;

    public PaymentsJourneyController(PaymentJourney journey) {
        this.journey = journey;
    }

    @PostMapping("/tries")
    @ResponseStatus(HttpStatus.CREATED)
    public JourneyContract.TryResult tryPayment(
            @RequestHeader(value = "X-Firefly-Username", required = false) String username,
            @RequestBody JourneyContract.Request request) {
        return journey.tryPayment(username, request);
    }

    @PostMapping("/{paymentId}/confirmations")
    public JourneyContract.Movement confirm(
            @RequestHeader(value = "X-Firefly-Username", required = false) String username,
            @PathVariable UUID paymentId,
            @RequestBody Confirmation confirmation) {
        return journey.confirm(username, paymentId, confirmation.response());
    }

    @PostMapping("/{paymentId}/cancellations")
    public JourneyContract.Movement cancel(
            @RequestHeader(value = "X-Firefly-Username", required = false) String username,
            @PathVariable UUID paymentId) {
        return journey.cancel(username, paymentId);
    }

    @GetMapping("/{paymentId}")
    public JourneyContract.Movement status(
            @RequestHeader(value = "X-Firefly-Username", required = false) String username,
            @PathVariable UUID paymentId) {
        return journey.status(username, paymentId);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> denied(AccessDeniedException exception) {
        return Map.of("reason", exception.getMessage());
    }

    public record Confirmation(String response) {
    }
}
