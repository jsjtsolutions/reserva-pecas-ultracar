package com.example.reserva_pecas.demo.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CriarReservaRequest(@NotBlank @Size(max = 40) String ordemServicoId,
                                  @NotEmpty @Size(max = 100) @Valid List<ItemRequest> itens) {
}
