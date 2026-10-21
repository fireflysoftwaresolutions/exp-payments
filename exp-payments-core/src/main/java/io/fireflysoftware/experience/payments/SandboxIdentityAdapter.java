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

public final class SandboxIdentityAdapter implements IdentityPort {

    @Override
    public Subject resolve(String username) {
        String name = username == null || username.isBlank() ? SAMPLE_USERNAME : username;
        if (SAMPLE_USERNAME.equals(name)) {
            return new Subject(SAMPLE_ACTOR, name);
        }
        if (DENIED_USERNAME.equals(name)) {
            return new Subject(DENIED_ACTOR, name);
        }
        throw new AccessDeniedException("ACTOR_UNKNOWN");
    }
}
