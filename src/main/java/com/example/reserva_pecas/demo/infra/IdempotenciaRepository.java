package com.example.reserva_pecas.demo.infra;

import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotenciaRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IdempotenciaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * R3 entre instancias: o INSERT concorrente com a mesma chave BLOQUEIA no indice unico
     * ate a transacao dona commitar/rollback. Se commitou, retornamos o registro para replay.
     * Optional.empty() == "esta transacao e a dona da chave".
     */
    public Optional<RegistroIdempotencia> inserirOuBuscar(long oficinaId, String chave, String hash) {
        var p = new MapSqlParameterSource("oficinaId", oficinaId)
                .addValue("chave", chave).addValue("hash", hash);
        int inseridos = jdbc.update("""
                INSERT INTO idempotencia (oficina_id, chave, hash_corpo)
                VALUES (:oficinaId, :chave, :hash)
                ON CONFLICT (oficina_id, chave) DO NOTHING
                """, p);
        if (inseridos == 1) {
            return Optional.empty();
        }
        // READ COMMITTED: este SELECT ve a linha commitada pela outra transacao.
        return jdbc.query("""
                SELECT hash_corpo, resposta_status, resposta_content_type, resposta_body
                FROM idempotencia WHERE oficina_id = :oficinaId AND chave = :chave
                """, p, (rs, i) -> new RegistroIdempotencia(
                        rs.getString("hash_corpo"),
                        rs.getObject("resposta_status", Integer.class),
                        rs.getString("resposta_content_type"),
                        rs.getString("resposta_body")))
                .stream().findFirst();
    }

    public void concluir(long oficinaId, String chave, int status, String contentType, String body) {
        var p = new MapSqlParameterSource("oficinaId", oficinaId).addValue("chave", chave)
                .addValue("status", status).addValue("ct", contentType).addValue("body", body);
        jdbc.update("""
                UPDATE idempotencia
                SET resposta_status = :status, resposta_content_type = :ct, resposta_body = :body
                WHERE oficina_id = :oficinaId AND chave = :chave
                """, p);
    }
}
