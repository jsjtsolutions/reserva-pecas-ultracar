package com.example.reserva_pecas.demo.infra;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.example.reserva_pecas.demo.dominio.ItemSolicitado;
import com.example.reserva_pecas.demo.dominio.Reserva;
import com.example.reserva_pecas.demo.dominio.StatusReserva;
import com.example.reserva_pecas.demo.dominio.ItemReserva;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ReservaRepository {

    private static final String COLS = "id, oficina_id, ordem_servico_id, status, criada_em, expira_em, criada_por";

    private static final RowMapper<Reserva> MAPPER = (rs, i) -> new Reserva(
            rs.getLong("id"), rs.getLong("oficina_id"), rs.getString("ordem_servico_id"),
            StatusReserva.valueOf(rs.getString("status")),
            Parametros.ler(rs, "criada_em"), Parametros.ler(rs, "expira_em"),
            rs.getObject("criada_por", Long.class));

    private final NamedParameterJdbcTemplate jdbc;

    public ReservaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long inserir(long oficinaId, String ordemServicoId, Long userId, Instant criadaEm, Instant expiraEm) {
        var p = new MapSqlParameterSource("oficinaId", oficinaId)
                .addValue("os", ordemServicoId)
                .addValue("userId", userId)
                .addValue("criadaEm", Parametros.instante(criadaEm))
                .addValue("expiraEm", Parametros.instante(expiraEm));
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO reserva (oficina_id, ordem_servico_id, status, criada_em, expira_em, criada_por, atualizada_em)
                VALUES (:oficinaId, :os, 'ATIVA', :criadaEm, :expiraEm, :userId, :criadaEm)
                """, p, kh, new String[]{"id"});
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    public void inserirItens(long reservaId, List<ItemSolicitado> itens) {
        var lote = itens.stream()
                .map(i -> new MapSqlParameterSource("reservaId", reservaId)
                        .addValue("pecaId", i.pecaId()).addValue("quantidade", i.quantidade()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("INSERT INTO reserva_item (reserva_id, peca_id, quantidade) VALUES (:reservaId, :pecaId, :quantidade)", lote);
    }

    public Optional<Reserva> buscar(long oficinaId, long id) {
        var p = new MapSqlParameterSource("oficinaId", oficinaId).addValue("id", id);
        return jdbc.query("SELECT " + COLS + " FROM reserva WHERE oficina_id = :oficinaId AND id = :id", p, MAPPER)
                .stream().findFirst();
    }

    public Optional<Reserva> buscarParaAtualizacao(long oficinaId, long id) {
        var p = new MapSqlParameterSource("oficinaId", oficinaId).addValue("id", id);
        return jdbc.query("SELECT " + COLS + " FROM reserva WHERE oficina_id = :oficinaId AND id = :id FOR UPDATE",
                p, MAPPER).stream().findFirst();
    }

    public int mudarStatus(long id, StatusReserva de, StatusReserva para, Instant agora) {
        var p = new MapSqlParameterSource("id", id)
                .addValue("de", de.name()).addValue("para", para.name())
                .addValue("agora", Parametros.instante(agora));
        return jdbc.update("UPDATE reserva SET status = :para, atualizada_em = :agora WHERE id = :id AND status = :de", p);
    }

    public List<ItemReserva> itens(long reservaId) {
        var p = new MapSqlParameterSource("reservaId", reservaId);
        return jdbc.query("""
                SELECT ri.peca_id, p.codigo, ri.quantidade
                FROM reserva_item ri JOIN peca p ON p.id = ri.peca_id
                WHERE ri.reserva_id = :reservaId ORDER BY ri.peca_id
                """, p, (rs, i) -> new ItemReserva(rs.getLong("peca_id"), rs.getString("codigo"), rs.getInt("quantidade")));
    }

    /**
     * Saldo reservado por peca: apenas ATIVAS e NAO expiradas (R4 lazy).
     * Uma unica query para a pagina inteira (evita N+1 - ver DESEMPENHO.md).
     */
    public Map<Long, Integer> reservadoAtivo(long oficinaId, Collection<Long> pecaIds, Instant agora) {
        if (pecaIds == null || pecaIds.isEmpty()) {
            return Map.of();
        }
        var p = new MapSqlParameterSource("oficinaId", oficinaId)
                .addValue("ids", pecaIds).addValue("agora", Parametros.instante(agora));
        Map<Long, Integer> saida = new HashMap<>();
        jdbc.query("""
                SELECT ri.peca_id, SUM(ri.quantidade) AS reservado
                FROM reserva_item ri
                JOIN reserva r ON r.id = ri.reserva_id
                WHERE r.oficina_id = :oficinaId
                  AND r.status = 'ATIVA'
                  AND r.expira_em > :agora
                  AND ri.peca_id IN (:ids)
                GROUP BY ri.peca_id
                """, p, rs -> { saida.put(rs.getLong("peca_id"), rs.getInt("reservado")); });
        return saida;
    }

    /** R4 proativo: SKIP LOCKED permite rodar em todas as instancias sem coordenacao extra. */
    public int expirarLote(Instant agora, int lote) {
        var p = new MapSqlParameterSource("agora", Parametros.instante(agora)).addValue("lote", lote);
        return jdbc.update("""
                UPDATE reserva SET status = 'EXPIRADA', atualizada_em = :agora
                WHERE status = 'ATIVA'
                  AND id IN (SELECT id FROM reserva
                             WHERE status = 'ATIVA' AND expira_em <= :agora
                             ORDER BY expira_em LIMIT :lote FOR UPDATE SKIP LOCKED)
                """, p);
    }
}