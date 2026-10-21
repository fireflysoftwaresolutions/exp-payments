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

public final class SandboxAccessAdapter implements AccessPort {

    @Override
    public Decision check(Request request) {
        if (request == null || IdentityPort.DENIED_ACTOR.equals(request.actorId())) {
            return Decision.denied("ACTOR_NOT_ENTITLED");
        }
        boolean sample = IdentityPort.SAMPLE_ACTOR.equals(request.actorId());
        boolean payment = request.resourceType() == ResourceType.PAYMENT;
        boolean allowedAction = request.action() == Action.READ || request.action() == Action.CREATE;
        if (sample && payment && allowedAction) {
            return Decision.grant();
        }
        return Decision.denied("ACTOR_NOT_ENTITLED");
    }
}
