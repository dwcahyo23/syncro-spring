package com.syncro.machine.api;

import com.syncro.machine.domain.ResponsibilityLevel;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class MachineResponsibilityDtos {

    public record CreateMachineResponsibilityRequest(
        @NotNull UUID machineId,
        @NotNull UUID userId,
        @NotNull ResponsibilityLevel level
    ) {}

    public record UpdateMachineResponsibilityRequest(
        @NotNull ResponsibilityLevel level
    ) {}

    public record MachineResponsibilityResponse(
        UUID id,
        UUID machineId,
        UUID userId,
        String userName,
        ResponsibilityLevel level,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record PageResponse<T>(
        List<T> items,
        int totalPages,
        long totalElements,
        int size,
        int number
    ) {
        public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getTotalPages(), page.getTotalElements(), page.getSize(), page.getNumber());
        }
    }
}
