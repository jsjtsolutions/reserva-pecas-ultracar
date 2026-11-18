package com.example.reserva_pecas.demo.servico;

import com.example.reserva_pecas.demo.api.erro.Erros;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Cursor {

    private static final String PREFIXO = "v1:";

    private Cursor() { }

    public static String codificar(long id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((PREFIXO + id).getBytes(StandardCharsets.UTF_8));
    }

    public static Long decodificar(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String texto = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!texto.startsWith(PREFIXO)) {
                throw Erros.cursorInvalido();
            }
            long id = Long.parseLong(texto.substring(PREFIXO.length()));
            if (id < 0) {
                throw Erros.cursorInvalido();
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw Erros.cursorInvalido();
        }
    }
}
