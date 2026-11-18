package com.example.reserva_pecas.demo.infra;

import java.util.List;
import java.util.Optional;

import com.example.reserva_pecas.demo.dominio.Peca;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PecaRepository {

    private static final String COLS = "id, oficina_id, codigo, descricao, estoque_fisico";

    private static final RowMapper<Peca> MAPPER = (rs, i) -> new Peca(
            rs.getLong("id"), rs.getLong("oficina_id"), rs.getString("codigo"),
            rs.getString("descricao"), rs.getInt("estoque_fisico"));

    private final NamedParameterJdbcTemplate jdbc;

    public PecaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Peca> buscar(long oficinaId, long pecaId) {
        var p = new MapSqlParameterSource("oficinaId", oficinaId).addValue("id", pecaId);
        return jdbc.query("SELECT " + COLS + " FROM peca WHERE oficina_id = :oficinaId AND id = :id", p, MAPPER)
                .stream().findFirst();
    }

    /**
     * R2: lock pessimista no BANCO (funciona com N instancias).
     * Chamado em loop, em ordem crescente de id, pelo servico.
     * Um unico "WHERE id IN (...) ORDER BY id FOR UPDATE" nao garante a ordem de aquisicao
     * (o planner pode escolher Bitmap Heap Scan) e abriria janela de deadlock.
     */
    public Optional<Peca> buscarParaAtualizacao(long oficinaId, long pecaId) {
        var p = new MapSqlParameterSource("oficinaId", oficinaId).addValue("id", pecaId);
        return jdbc.query("SELECT " + COLS + " FROM peca WHERE oficina_id = :oficinaId AND id = :id FOR UPDATE",
                p, MAPPER).stream().findFirst();
    }

    /** Baixa atomica; o CHECK do banco e esta clausula sao a ultima linha de defesa. */
    public int baixarEstoque(long oficinaId, long pecaId, int quantidade) {
        var p = new MapSqlParameterSource("oficinaId", oficinaId)
                .addValue("id", pecaId).addValue("qtd", quantidade);
        return jdbc.update("""
                UPDATE peca SET estoque_fisico = estoque_fisico - :qtd
                WHERE id = :id AND oficina_id = :oficinaId AND estoque_fisico >= :qtd
                """, p);
    }

    /**
     * D2: keyset pagination. SQL montado em tempo de execucao de proposito:
     * "(:busca IS NULL OR ...)" impediria o uso dos indices GIN (generic plan).
     */
    public List<Peca> buscarPagina(long oficinaId, String busca, Long cursorId, int limite) {
        var sql = new StringBuilder("SELECT " + COLS + " FROM peca WHERE oficina_id = :oficinaId");
        var p = new MapSqlParameterSource("oficinaId", oficinaId).addValue("limite", limite);
        if (busca != null && !busca.isBlank()) {
            sql.append(" AND (codigo ILIKE '%' || :busca || '%' ESCAPE '\\'"
                    + " OR descricao ILIKE '%' || :busca || '%' ESCAPE '\\')");
            p.addValue("busca", escaparLike(busca.strip()));
        }
        if (cursorId != null) {
            sql.append(" AND id > :cursor");
            p.addValue("cursor", cursorId);
        }
        sql.append(" ORDER BY id LIMIT :limite");
        return jdbc.query(sql.toString(), p, MAPPER);
    }

    /** Evita que % e _ do usuario virem curingas do LIKE. */
    private static String escaparLike(String termo) {
        return termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
