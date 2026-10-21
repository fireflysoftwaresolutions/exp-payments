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

package io.fireflysoftware.experience.payments;

import java.util.UUID;

public interface AccessPort {

    Decision check(Request request);

    enum Action {
        READ,
        CREATE
    }

    enum ResourceType {
        PAYMENT
    }

    record Request(UUID actorId, Action action, ResourceType resourceType, UUID resourceId) {
    }

    record Decision(boolean allowed, String reason) {
        public static Decision grant() {
            return new Decision(true, null);
        }

        public static Decision denied(String reason) {
            return new Decision(false, reason);
        }
    }
}
