package com.example.reserva_pecas.demo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.example.reserva_pecas.demo.servico.ExpiracaoService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservaFluxoIT extends BaseIT {

    private static final long OFICINA = 42;
    private static final long OUTRA = 43;

    @Autowired private ExpiracaoService expiracao;

    private long pecaId;

    @BeforeEach
    void setup() {
        limpar();
        pecaId = inserirPeca(OFICINA, "P-FLUXO", 10);
    }

    private long criar(String os, int qtd) {
        var r = post("/api/v1/reservas", OFICINA, UUID.randomUUID().toString(), corpoReserva(os, pecaId, qtd));
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        return ((Number) JsonPath.read(r.getBody(), "$.id")).longValue();
    }

    private int saldo() {
        var r = get("/api/v1/pecas/" + pecaId + "/saldo", OFICINA);
        return (Integer) JsonPath.read(r.getBody(), "$.saldoDisponivel");
    }

    @Test
    @DisplayName("criar -> confirmar: baixa o estoque fisico e mantem o saldo")
    void confirmar() {
        long id = criar("OS-1", 4);
        assertThat(saldo()).isEqualTo(6);

        var conf = post("/api/v1/reservas/" + id + "/confirmacao", OFICINA, null, null);
        assertThat(conf.getStatusCode().value()).isEqualTo(200);
        assertThat(saldo()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT estoque_fisico FROM peca WHERE id=?", Integer.class, pecaId)).isEqualTo(6);

        var deNovo = post("/api/v1/reservas/" + id + "/confirmacao", OFICINA, null, null);
        assertThat(deNovo.getStatusCode().value()).isEqualTo(409);
        assertThat(deNovo.getHeaders().getContentType().toString()).isEqualTo("application/problem+json");
        assertThat(deNovo.getBody()).contains("TRANSICAO_INVALIDA");
    }

    @Test
    @DisplayName("criar -> cancelar: libera o saldo sem mexer no estoque")
    void cancelar() {
        long id = criar("OS-2", 7);
        assertThat(saldo()).isEqualTo(3);

        assertThat(post("/api/v1/reservas/" + id + "/cancelamento", OFICINA, null, null).getStatusCode().value()).isEqualTo(200);
        assertThat(saldo()).isEqualTo(10);
        assertThat(post("/api/v1/reservas/" + id + "/cancelamento", OFICINA, null, null).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("R1: falta saldo -> 409 detalhando item e saldo, sem reserva parcial")
    void saldoInsuficiente() {
        criar("OS-3", 8);
        var r = post("/api/v1/reservas", OFICINA, UUID.randomUUID().toString(), corpoReserva("OS-4", pecaId, 5));
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody()).contains("SALDO_INSUFICIENTE").contains("\"disponivel\":2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reserva", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R3: replay identico e 422 com corpo diferente")
    void idempotencia() {
        String chave = UUID.randomUUID().toString();
        var um = post("/api/v1/reservas", OFICINA, chave, corpoReserva("OS-5", pecaId, 2));
        var dois = post("/api/v1/reservas", OFICINA, chave, corpoReserva("OS-5", pecaId, 2));
        assertThat(um.getStatusCode().value()).isEqualTo(201);
        assertThat(dois.getStatusCode().value()).isEqualTo(201);
        assertThat(dois.getBody()).isEqualTo(um.getBody());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reserva", Long.class)).isEqualTo(1L);

        var tres = post("/api/v1/reservas", OFICINA, chave, corpoReserva("OS-5", pecaId, 3));
        assertThat(tres.getStatusCode().value()).isEqualTo(422);
        assertThat(tres.getBody()).contains("IDEMPOTENCY_KEY_REUTILIZADA");
    }

    @Test
    @DisplayName("R4: reserva vencida libera saldo, confirmar da 409 e status vira EXPIRADA")
    void expiracao() {
        long id = criar("OS-6", 6);
        assertThat(saldo()).isEqualTo(4);
        jdbc.update("UPDATE reserva SET expira_em = now() - interval '1 minute' WHERE id = ?", id);

        assertThat(saldo()).as("saldo liberado preguicosamente").isEqualTo(10);

        var conf = post("/api/v1/reservas/" + id + "/confirmacao", OFICINA, null, null);
        assertThat(conf.getStatusCode().value()).isEqualTo(409);
        assertThat(conf.getBody()).contains("RESERVA_EXPIRADA");
        assertThat(get("/api/v1/reservas/" + id, OFICINA).getBody()).contains("EXPIRADA");
    }

    @Test
    @DisplayName("R4: job em lote marca vencidas como EXPIRADA")
    void jobExpiracao() {
        long id = criar("OS-7", 1);
        jdbc.update("UPDATE reserva SET expira_em = now() - interval '1 minute' WHERE id = ?", id);
        assertThat(expiracao.expirarVencidas()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM reserva WHERE id=?", String.class, id)).isEqualTo("EXPIRADA");
    }

    @Test
    @DisplayName("R6: recursos de outra oficina -> 404")
    void isolamento() {
        long id = criar("OS-8", 1);
        assertThat(get("/api/v1/pecas/" + pecaId + "/saldo", OUTRA).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/api/v1/reservas/" + id, OUTRA).getStatusCode().value()).isEqualTo(404);
        assertThat(post("/api/v1/reservas/" + id + "/confirmacao", OUTRA, null, null).getStatusCode().value()).isEqualTo(404);
        assertThat(post("/api/v1/reservas", OUTRA, UUID.randomUUID().toString(), corpoReserva("OS-9", pecaId, 1))
                .getStatusCode().value()).isEqualTo(404);
        assertThat(get("/api/v1/pecas?busca=P-FLUXO", OUTRA).getBody()).contains("\"itens\":[]");
    }

    @Test
    @DisplayName("R7: erros em application/problem+json com code")
    void formatoErro() {
        var semChave = post("/api/v1/reservas", OFICINA, null, corpoReserva("OS-10", pecaId, 1));
        assertThat(semChave.getStatusCode().value()).isEqualTo(400);
        assertThat(semChave.getHeaders().getContentType().toString()).isEqualTo("application/problem+json");
        assertThat(semChave.getBody()).contains("IDEMPOTENCY_KEY_AUSENTE").contains("\"title\"").contains("\"type\"");

        var invalido = post("/api/v1/reservas", OFICINA, UUID.randomUUID().toString(),
                "{\"ordemServicoId\":\"\",\"itens\":[]}");
        assertThat(invalido.getStatusCode().value()).isEqualTo(422);
        assertThat(invalido.getBody()).contains("VALIDACAO_INVALIDA");
    }
}
