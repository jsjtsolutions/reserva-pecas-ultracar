package com.example.reserva_pecas.demo.dominio;

public record Peca(long id, long oficinaId, String codigo, String descricao, int estoqueFisico) {
}