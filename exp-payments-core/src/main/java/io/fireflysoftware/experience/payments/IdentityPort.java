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

public interface IdentityPort {

    UUID SAMPLE_ACTOR = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID DENIED_ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222");
    String SAMPLE_USERNAME = "sample-journey";
    String DENIED_USERNAME = "denied-actor";

    Subject resolve(String username);

    record Subject(UUID actorId, String username) {
    }
}
