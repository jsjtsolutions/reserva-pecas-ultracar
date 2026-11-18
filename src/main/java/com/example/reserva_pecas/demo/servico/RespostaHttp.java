package com.example.reserva_pecas.demo.servico;

/**
 * Resultado de negocio ja serializado. 409 e retorno (nao excecao) porque a
 * transicao ATIVA -> EXPIRADA precisa ser COMMITADA junto com a resposta.
 */
public record RespostaHttp(int status, String contentType, String body) {
}
