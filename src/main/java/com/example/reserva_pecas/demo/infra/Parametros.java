package com.example.reserva_pecas.demo.infra;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class Parametros {
    private Parametros() { }

    /** pgjdbc mapeia OffsetDateTime para timestamptz sem ambiguidade de fuso. */
    public static OffsetDateTime instante(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    public static Instant ler(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        return rs.getObject(coluna, OffsetDateTime.class).toInstant();
    }
}
