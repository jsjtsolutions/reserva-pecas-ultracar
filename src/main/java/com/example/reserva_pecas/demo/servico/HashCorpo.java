package com.example.reserva_pecas.demo.servico;

import com.example.reserva_pecas.demo.dominio.ItemSolicitado;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

public final class HashCorpo {

    private HashCorpo() { }

    /** Hash do corpo CANONICO (itens ordenados/consolidados): mesma semantica => mesmo hash. */
    public static String de(long oficinaId, String ordemServicoId, List<ItemSolicitado> itens) {
        String canonico = oficinaId + "#" + ordemServicoId + "|" + itens.stream()
                .map(i -> i.pecaId() + ":" + i.quantidade())
                .collect(Collectors.joining(","));
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonico.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
