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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

public final class ManagedPaymentContracts {
    private ManagedPaymentContracts() {}

    @Schema(
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {
                "actionId",
                "sourceAccountId",
                "destinationAccountId",
                "amount",
                "currency",
                "ttlSeconds"
            })
    public record Create(
            UUID actionId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            BigDecimal amount,
            String currency,
            int ttlSeconds,
            Long customerNotAfterEpochMillis) {}

    public enum DecisionKind {
        CONFIRM,
        CANCEL
    }

    @Schema(
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"actionId", "expectedVersion", "kind"})
    public record Decision(UUID actionId, long expectedVersion, DecisionKind kind) {}

    @Schema(
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"actionId", "expectedVersion"})
    public record Recovery(UUID actionId, long expectedVersion) {}

    public enum State {
        RECEIVED,
        RESERVING,
        RESERVED,
        COMMITTING,
        CANCELLING,
        COMPLETED,
        REVERSED,
        CANCELLED,
        EXPIRED,
        RECOVERY_REQUIRED
    }

    public record Acceptance(View payment, boolean replayed) {}

    public record View(
            UUID paymentId,
            UUID createActionId,
            String tenant,
            UUID sourceAccountId,
            UUID destinationAccountId,
            String amount,
            String currency,
            int currencyScale,
            String currencyPolicy,
            int ttlSeconds,
            Long customerNotAfterEpochMillis,
            long notAfterEpochMillis,
            String initiator,
            State state,
            long version,
            UUID reservationId,
            UUID postingId,
            UUID reversalId,
            UUID absenceReceiptId,
            Long ledgerVersion,
            java.time.Instant observedAt,
            UUID pendingEffectId,
            String recoveryCode,
            String ledgerState,
            java.time.Instant reservationExpiresAt) {}

    public record HistoryPage(List<HistoryEvent> items) {
        public HistoryPage {
            items = List.copyOf(items);
        }
    }

    public record HistoryEvent(
            long version,
            String type,
            String actor,
            java.time.Instant recordedAt,
            UUID actionId,
            UUID effectId,
            State state,
            UUID postingId,
            UUID reversalId) {}

    private static final ObjectMapper JSON =
            new ObjectMapper(
                            new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static Create decodeCreate(byte[] raw) {
        var node =
                object(
                        raw,
                        Set.of(
                                "actionId",
                                "sourceAccountId",
                                "destinationAccountId",
                                "amount",
                                "currency",
                                "ttlSeconds",
                                "customerNotAfterEpochMillis"),
                        "actionId",
                        "sourceAccountId",
                        "destinationAccountId",
                        "amount",
                        "currency",
                        "ttlSeconds");
        UUID action = uuid(node, "actionId"),
                source = uuid(node, "sourceAccountId"),
                destination = uuid(node, "destinationAccountId");
        if (source.equals(destination)) throw bad();
        JsonNode amountNode = node.get("amount");
        if (!amountNode.isNumber()) throw bad();
        BigDecimal amount;
        try {
            amount = amountNode.decimalValue();
        } catch (RuntimeException error) {
            throw bad();
        }
        if (amount.signum() <= 0
                || amount.compareTo(BigDecimal.TEN.pow(20)) >= 0
                || amount.stripTrailingZeros().scale() > 18) throw bad();
        String currency = text(node, "currency");
        if (!currency.matches("[A-Za-z]{3}")) throw bad();
        long ttl = integer(node, "ttlSeconds");
        if (ttl < 1 || ttl > 900) throw bad();
        Long deadline = null;
        if (node.hasNonNull("customerNotAfterEpochMillis")) {
            deadline = integer(node, "customerNotAfterEpochMillis");
            if (deadline <= 0) throw bad();
        }
        return new Create(
                action,
                source,
                destination,
                amount,
                currency.toUpperCase(Locale.ROOT),
                (int) ttl,
                deadline);
    }

    public static Decision decodeDecision(byte[] raw) {
        var node =
                object(
                        raw,
                        Set.of("actionId", "expectedVersion", "kind"),
                        "actionId",
                        "expectedVersion",
                        "kind");
        DecisionKind kind;
        try {
            kind = DecisionKind.valueOf(text(node, "kind"));
        } catch (IllegalArgumentException error) {
            throw bad();
        }
        return new Decision(uuid(node, "actionId"), version(node), kind);
    }

    public static Recovery decodeRecovery(byte[] raw) {
        var node =
                object(raw, Set.of("actionId", "expectedVersion"), "actionId", "expectedVersion");
        return new Recovery(uuid(node, "actionId"), version(node));
    }

    private static JsonNode object(byte[] raw, Set<String> allowed, String... required) {
        if (raw == null || raw.length == 0 || raw.length > 8192) throw bad();
        try {
            var node = JSON.readTree(raw);
            if (node == null || !node.isObject()) throw bad();
            var names = node.fieldNames();
            while (names.hasNext()) if (!allowed.contains(names.next())) throw bad();
            for (String name : required) if (!node.hasNonNull(name)) throw bad();
            return node;
        } catch (java.io.IOException error) {
            throw bad();
        }
    }

    private static String text(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || !value.isTextual()) throw bad();
        return value.textValue();
    }

    private static UUID uuid(JsonNode node, String name) {
        String value = text(node, name);
        if (!value.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw bad();
        UUID id = UUID.fromString(value);
        if (id.equals(new UUID(0, 0))) throw bad();
        return id;
    }

    private static long integer(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw bad();
        return value.longValue();
    }

    private static long version(JsonNode node) {
        long value = integer(node, "expectedVersion");
        if (value < 0) throw bad();
        return value;
    }

    private static ResponseStatusException bad() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MANAGED_PAYMENT_INPUT");
    }
}
