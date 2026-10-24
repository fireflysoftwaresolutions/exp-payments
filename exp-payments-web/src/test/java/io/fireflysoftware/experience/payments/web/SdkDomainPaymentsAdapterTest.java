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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fireflysoftware.domain.payments.sdk.api.PaymentsApi;
import io.fireflysoftware.domain.payments.sdk.model.MovementResult;
import io.fireflysoftware.experience.payments.JourneyContract;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

class SdkDomainPaymentsAdapterTest {

    @Test
    void journeyCallsTheDomainSdk() {
        PaymentsApi api = mock(PaymentsApi.class);
        UUID paymentId = UUID.randomUUID();
        MovementResult reserved =
                new MovementResult()
                        .paymentId(paymentId)
                        .phase(MovementResult.PhaseEnum.TRYING)
                        .status(MovementResult.StatusEnum.INITIATED)
                        .scheme(MovementResult.SchemeEnum.DOMESTIC_OUTBOUND)
                        .route(MovementResult.RouteEnum.OUTBOUND_RAIL)
                        .challengeValue("482913")
                        .thirdPartySynced(false);
        when(api.tryPayment(any(), any())).thenReturn(Mono.just(reserved));
        when(api.confirmPayment(any(), any(), any()))
                .thenReturn(
                        Mono.just(
                                new MovementResult()
                                        .paymentId(paymentId)
                                        .phase(MovementResult.PhaseEnum.CONFIRMED)
                                        .status(MovementResult.StatusEnum.COMPLETED)
                                        .thirdPartySynced(true)));
        when(api.cancelPayment(any(), any()))
                .thenReturn(
                        Mono.just(
                                new MovementResult()
                                        .paymentId(paymentId)
                                        .phase(MovementResult.PhaseEnum.CANCELLED)
                                        .status(MovementResult.StatusEnum.CANCELLED)));
        when(api.readPayment(any(), any()))
                .thenReturn(
                        Mono.just(
                                new MovementResult()
                                        .paymentId(paymentId)
                                        .phase(MovementResult.PhaseEnum.CONFIRMED)
                                        .status(MovementResult.StatusEnum.COMPLETED)));
        SdkDomainPaymentsAdapter adapter = new SdkDomainPaymentsAdapter(api);
        JourneyContract.Request request =
                new JourneyContract.Request(
                        JourneyContract.Scheme.DOMESTIC_OUTBOUND,
                        "out-1",
                        new BigDecimal("10.00"),
                        "EUR",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        null,
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        null);

        JourneyContract.TryResult tried = adapter.tryPayment(request);
        JourneyContract.Movement confirmed = adapter.confirm(paymentId, tried.challengeValue());
        adapter.cancel(paymentId);
        adapter.readStatus(paymentId);

        assertEquals(paymentId, tried.paymentId());
        assertEquals("482913", tried.challengeValue());
        assertEquals("TRYING", tried.phase());
        assertEquals("CONFIRMED", confirmed.phase());
        assertNotEquals("sandbox", tried.challengeValue());
        assertEquals(JourneyContract.Status.COMPLETED, confirmed.status());
        verify(api).tryPayment(any(), any());
        verify(api).confirmPayment(any(), any(), any());
        verify(api).cancelPayment(any(), any());
        verify(api).readPayment(any(), any());
    }

    @Test
    void everyNativeEnumMapsWithoutFallbackOrLossOfPhaseFailure() {
        var api = mock(PaymentsApi.class);
        var id = UUID.randomUUID();
        var adapter = new SdkDomainPaymentsAdapter(api);
        for (var value : MovementResult.StatusEnum.values()) {
            when(api.readPayment(any(), any()))
                    .thenReturn(Mono.just(new MovementResult().paymentId(id).status(value)));
            assertEquals(value.getValue(), adapter.readStatus(id).status().name());
        }
        for (var value : MovementResult.SchemeEnum.values()) {
            when(api.readPayment(any(), any()))
                    .thenReturn(Mono.just(new MovementResult().paymentId(id).scheme(value)));
            assertEquals(value.getValue(), adapter.readStatus(id).scheme().name());
        }
        for (var value : MovementResult.RouteEnum.values()) {
            when(api.readPayment(any(), any()))
                    .thenReturn(Mono.just(new MovementResult().paymentId(id).route(value)));
            assertEquals(value.getValue(), adapter.readStatus(id).route().name());
        }
        for (var value : MovementResult.PhaseEnum.values()) {
            when(api.readPayment(any(), any()))
                    .thenReturn(Mono.just(new MovementResult().paymentId(id).phase(value)));
            assertEquals(value.getValue(), adapter.readStatus(id).phase());
        }
        for (var value : MovementResult.FailureEnum.values()) {
            when(api.readPayment(any(), any()))
                    .thenReturn(Mono.just(new MovementResult().paymentId(id).failure(value)));
            assertEquals(value.getValue(), adapter.readStatus(id).failure());
        }
    }

    @Test
    void nullableNativeFieldsStayNull() {
        var api = mock(PaymentsApi.class);
        var id = UUID.randomUUID();
        when(api.readPayment(any(), any()))
                .thenReturn(Mono.just(new MovementResult().paymentId(id)));
        var result = new SdkDomainPaymentsAdapter(api).readStatus(id);
        assertNull(result.status());
        assertNull(result.scheme());
        assertNull(result.route());
        assertNull(result.failure());
        assertNull(result.phase());
        assertNull(result.posting());
        assertNull(result.reversal());
    }

    @Test
    void typedPostingSidesAndExactMoneyArePreserved() {
        var api = mock(PaymentsApi.class);
        var id = UUID.randomUUID();
        var account = UUID.randomUUID();
        for (var side : io.fireflysoftware.domain.payments.sdk.model.LedgerLeg.SideEnum.values()) {
            var leg =
                    new io.fireflysoftware.domain.payments.sdk.model.LedgerLeg()
                            .accountId(account)
                            .side(side)
                            .amount(new BigDecimal("10.00"))
                            .currency("EUR");
            var posting =
                    new io.fireflysoftware.domain.payments.sdk.model.LedgerPosting()
                            .transactionId(id)
                            .currency("EUR")
                            .legs(java.util.List.of(leg));
            when(api.readPayment(any(), any()))
                    .thenReturn(Mono.just(new MovementResult().paymentId(id).posting(posting)));
            var mapped =
                    new SdkDomainPaymentsAdapter(api).readStatus(id).posting().legs().getFirst();
            assertEquals(side.getValue(), mapped.side().name());
            assertEquals(account, mapped.accountId());
            assertEquals(new BigDecimal("10.00"), mapped.amount());
        }
    }

    @Test
    void everyInputSchemeUsesTheGenuineTypedCommand() {
        var api = mock(PaymentsApi.class);
        var adapter = new SdkDomainPaymentsAdapter(api);
        when(api.tryPayment(any(), any()))
                .thenReturn(Mono.just(new MovementResult().paymentId(UUID.randomUUID())));
        for (var scheme : JourneyContract.Scheme.values())
            adapter.tryPayment(
                    new JourneyContract.Request(
                            scheme,
                            "typed-" + scheme,
                            new BigDecimal("10.00"),
                            "EUR",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));
        var captured =
                org.mockito.ArgumentCaptor.forClass(
                        io.fireflysoftware.domain.payments.sdk.model.MovementCommand.class);
        verify(api, org.mockito.Mockito.times(JourneyContract.Scheme.values().length))
                .tryPayment(captured.capture(), any());
        for (int i = 0; i < JourneyContract.Scheme.values().length; i++)
            assertEquals(
                    JourneyContract.Scheme.values()[i].name(),
                    captured.getAllValues().get(i).getScheme().getValue());
    }
}
