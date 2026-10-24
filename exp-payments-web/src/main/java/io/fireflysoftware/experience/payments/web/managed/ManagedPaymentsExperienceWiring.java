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

import io.fireflysoftware.experience.payments.managed.*;
import io.fireflysoftware.experience.payments.managed.impl.ManagedPaymentAccessServiceImpl;
import io.fireflysoftware.experience.payments.managed.impl.ManagedPaymentJourneyServiceImpl;

import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ManagedPaymentsExperienceWiring {
    @Bean
    @ConditionalOnMissingBean(ManagedPaymentDelegationPort.class)
    ManagedPaymentDelegationPort managedExperienceDelegation(
            @Value("${managed-payments.delegation.mode:unconfigured}") String mode) {
        return new ManagedExperienceDelegationAdapter(mode);
    }

    @Bean
    @ConditionalOnMissingBean(ManagedDomainPaymentsPort.class)
    ManagedDomainPaymentsPort managedDomainPayments(
            ManagedPaymentDelegationPort delegation,
            @Value("${managed-payments.peers.domain.base-url:}") String base,
            @Value("${managed-payments.peers.domain.audience:}") String audience,
            @Value("${managed-payments.allow-loopback-http:false}") boolean loopback) {
        return new SdkManagedDomainPaymentsAdapter(delegation, base, audience, loopback);
    }

    @Bean
    ManagedPaymentAccessService managedPaymentAccessService(
            ObjectProvider<PolicyDecisionPort> policies,
            @Value("${firefly.security.resource-server.strict-policy:false}") boolean strict) {
        return new ManagedPaymentAccessServiceImpl(policies, strict);
    }

    @Bean
    ManagedPaymentJourneyService managedPaymentJourneyService(
            ManagedDomainPaymentsPort owner, ManagedPaymentAccessService access) {
        return new ManagedPaymentJourneyServiceImpl(owner, access);
    }
}
