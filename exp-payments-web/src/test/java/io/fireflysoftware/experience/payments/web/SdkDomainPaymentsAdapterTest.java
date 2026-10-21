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

import io.fireflysoftware.domain.payments.sdk.api.PaymentsApi;
import io.fireflysoftware.domain.payments.sdk.model.MovementResult;
import io.fireflysoftware.experience.payments.JourneyContract;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SdkDomainPaymentsAdapterTest {

    @Test
    void journeyCallsTheDomainSdk() {
        PaymentsApi api = mock(PaymentsApi.class);
        UUID paymentId = UUID.randomUUID();
        MovementResult reserved = new MovementResult()
                .paymentId(paymentId)
                .phase("TRYING")
                .status("INITIATED")
                .scheme("DOMESTIC_OUTBOUND")
                .route("OUTBOUND_RAIL")
                .challengeValue("482913")
                .thirdPartySynced(false);
        when(api.tryPayment(any(), any())).thenReturn(Mono.just(reserved));
        when(api.confirmPayment(any(), any(), any())).thenReturn(Mono.just(reserved.phase("CONFIRMED").status("COMPLETED").thirdPartySynced(true)));
        when(api.cancelPayment(any(), any())).thenReturn(Mono.just(reserved.phase("CANCELLED").status("CANCELLED")));
        when(api.readPayment(any(), any())).thenReturn(Mono.just(reserved.phase("CONFIRMED").status("COMPLETED")));
        SdkDomainPaymentsAdapter adapter = new SdkDomainPaymentsAdapter(api);
        JourneyContract.Request request = new JourneyContract.Request(JourneyContract.Scheme.DOMESTIC_OUTBOUND, "out-1",
                new BigDecimal("10.00"), "EUR", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                null, null, null, UUID.randomUUID(), null, null, null, null);

        JourneyContract.TryResult tried = adapter.tryPayment(request);
        JourneyContract.Movement confirmed = adapter.confirm(paymentId, tried.challengeValue());
        adapter.cancel(paymentId);
        adapter.readStatus(paymentId);

        assertEquals(paymentId, tried.paymentId());
        assertEquals("482913", tried.challengeValue());
        assertNotEquals("sandbox", tried.challengeValue());
        assertEquals(JourneyContract.Status.COMPLETED, confirmed.status());
        verify(api).tryPayment(any(), any());
        verify(api).confirmPayment(any(), any(), any());
        verify(api).cancelPayment(any(), any());
        verify(api).readPayment(any(), any());
    }
}
