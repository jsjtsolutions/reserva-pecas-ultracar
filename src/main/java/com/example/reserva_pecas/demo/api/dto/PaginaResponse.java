package com.example.reserva_pecas.demo.api.dto;

import java.util.List;

public record PaginaResponse<T>(List<T> itens, String proximoCursor, boolean temMais) {
}
