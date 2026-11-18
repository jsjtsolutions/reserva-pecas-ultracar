package com.example.reserva_pecas.demo.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ItemRequest(@NotNull @Positive Long pecaId,
                          @NotNull @Positive @Max(1_000_000) Integer quantidade) {
}
