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

import io.fireflysoftware.experience.payments.DomainPaymentsPort;
import io.fireflysoftware.experience.payments.PaymentJourney;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootApplication(scanBasePackages = "io.fireflysoftware.experience.payments")
public class ExpPaymentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExpPaymentsApplication.class, args);
    }

    @Bean
    DomainPaymentsPort domainPaymentsPort(@Value("${firefly.experience.payments.domain-base-url}") String baseUrl) {
        return new SdkDomainPaymentsAdapter(baseUrl);
    }

    @Bean
    WebClient securityCenterWebClient(
            @Value("${api-configuration.domain-platform.security-center.base-path:http://localhost:8085}") String basePath) {
        return WebClient.builder().baseUrl(basePath).build();
    }

    @Bean
    PaymentJourney paymentJourney(DomainPaymentsPort domain, WebClient securityCenterWebClient) {
        return new PaymentJourney(
                new HttpSecurityCenterAdapters.Identity(securityCenterWebClient),
                new HttpSecurityCenterAdapters.Access(securityCenterWebClient),
                domain);
    }
}
