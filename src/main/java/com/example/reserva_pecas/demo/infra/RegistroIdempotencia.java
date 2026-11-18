package com.example.reserva_pecas.demo.infra;

public record RegistroIdempotencia(String hashCorpo, Integer respostaStatus,
                                   String respostaContentType, String respostaBody) {
    public boolean concluida() {
        return respostaStatus != null;
    }
}
