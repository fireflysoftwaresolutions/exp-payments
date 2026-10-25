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

import org.fireflyframework.security.rs.AccessTokenValidators;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class ManagedPaymentsExperienceSecurityConfiguration {
    @Bean
    @Order(-100)
    SecurityWebFilterChain managedPaymentsExperienceSecurity(
            ServerHttpSecurity http,
            @Value("${managed-payments.security.issuer:}") String issuer,
            @Value("${managed-payments.security.audience:}") String audience,
            @Value("${managed-payments.security.jwk-set-uri:}") String jwks) {
        ReactiveJwtDecoder decoder;
        if (issuer.isBlank() || audience.isBlank() || jwks.isBlank())
            decoder =
                    token ->
                            Mono.error(new JwtException("Managed payment trust is not configured"));
        else {
            var configured = NimbusReactiveJwtDecoder.withJwkSetUri(jwks).build();
            configured.setJwtValidator(
                    AccessTokenValidators.forIssuerAndAudience(issuer, audience));
            decoder = configured;
        }
        return stateless(http)
                .securityMatcher(
                        ServerWebExchangeMatchers.pathMatchers(
                                "/api/v1/tenants/*/internal-payments",
                                "/api/v1/tenants/*/internal-payments/**"))
                .authorizeExchange(e -> e.anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtDecoder(decoder)))
                .build();
    }

    @Bean
    @Order(-99)
    SecurityWebFilterChain managedPaymentsExperiencePublicContract(ServerHttpSecurity http) {
        return stateless(http)
                .securityMatcher(
                        ServerWebExchangeMatchers.pathMatchers(
                                HttpMethod.GET,
                                "/v3/api-docs",
                                "/v3/api-docs.yaml",
                                "/v3/api-docs/**"))
                .authorizeExchange(e -> e.anyExchange().permitAll())
                .build();
    }

    @Bean
    @Order(-98)
    SecurityWebFilterChain managedPaymentsExperienceFallbackDenied(ServerHttpSecurity http) {
        return stateless(http)
                .authorizeExchange(e -> e.anyExchange().denyAll())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                        new HttpStatusServerEntryPoint(HttpStatus.FORBIDDEN)))
                .build();
    }

    private static ServerHttpSecurity stateless(ServerHttpSecurity http) {
        return http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()));
    }
}
