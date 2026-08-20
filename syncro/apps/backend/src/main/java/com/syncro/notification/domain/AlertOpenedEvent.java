package com.syncro.notification.domain;

import java.util.UUID;

public record AlertOpenedEvent(UUID alertId, UUID machineId, String traceId) {}
