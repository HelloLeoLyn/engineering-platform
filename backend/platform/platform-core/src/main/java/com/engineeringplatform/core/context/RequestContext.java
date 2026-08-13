package com.engineeringplatform.core.context;

import java.util.Optional;

public interface RequestContext {
    String requestId();
    Optional<String> traceId();
    Optional<String> correlationId();
    Optional<String> actorId();
    Optional<String> organizationId();
}
