package com.example.reserva_pecas.demo.api;

import com.example.reserva_pecas.demo.api.erro.Erros;

/** Fora de escopo: autenticacao real. Headers sao a identidade (R6). */
public record Contexto(long oficinaId, Long userId) {

    public static Contexto leitura(Long oficinaId) {
        if (oficinaId == null) {
            throw Erros.headerAusente("X-Oficina-Id");
        }
        if (oficinaId <= 0) {
            throw Erros.validacao("X-Oficina-Id deve ser positivo.", java.util.Map.of());
        }
        return new Contexto(oficinaId, null);
    }

    public static Contexto escrita(Long oficinaId, Long userId) {
        Contexto c = leitura(oficinaId);
        if (userId == null) {
            throw Erros.headerAusente("X-User-Id");
        }
        return new Contexto(c.oficinaId(), userId);
    }
}
