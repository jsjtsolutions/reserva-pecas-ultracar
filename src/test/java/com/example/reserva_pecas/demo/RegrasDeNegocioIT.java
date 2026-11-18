package com.example.reserva_pecas.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.example.reserva_pecas.demo.servico.ExpiracaoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Regras R1, R3, R4, R5, R6 e R7 pela API HTTP")
class RegrasDeNegocioIT extends BaseIntegracao {

    @Autowired private ExpiracaoService expiracao;

    @Test
    @DisplayName("criar e confirmar: saldo se mantem e estoque fisico cai")
    void criarEConfirmar() {
        long peca = novaPeca(OFICINA, "P-1", 10);
        long reserva = criarReserva(OFICINA, "OS-1", peca, 4);

        assertThat(saldoDe(OFICINA, peca)).isEqualTo(6);

        var r = post("/api/v1/reservas/" + reserva + "/confirmacao", OFICINA, null, null);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(texto(r.getBody(), "$.status")).isEqualTo("CONFIRMADA");
        assertThat(saldoDe(OFICINA, peca)).isEqualTo(6);
        assertThat(estoqueFisico(peca)).isEqualTo(6);
    }

    @Test
    @DisplayName("criar e cancelar: saldo volta, estoque fisico intacto")
    void criarECancelar() {
        long peca = novaPeca(OFICINA, "P-2", 10);
        long reserva = criarReserva(OFICINA, "OS-2", peca, 7);
        assertThat(saldoDe(OFICINA, peca)).isEqualTo(3);

        var r = post("/api/v1/reservas/" + reserva + "/cancelamento", OFICINA, null, null);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(saldoDe(OFICINA, peca)).isEqualTo(10);
        assertThat(estoqueFisico(peca)).isEqualTo(10);
    }

    @Test
    @DisplayName("R1: falta de saldo devolve 409 com o saldo do item e nao grava nada")
    void r1SemSaldo() {
        long peca = novaPeca(OFICINA, "P-3", 10);
        criarReserva(OFICINA, "OS-3", peca, 8);

        var r = post("/api/v1/reservas", OFICINA, UUID.randomUUID().toString(), corpo("OS-4", peca, 5));

        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(texto(r.getBody(), "$.code")).isEqualTo("SALDO_INSUFICIENTE");
        assertThat(numero(r.getBody(), "$.itensFaltantes[0].disponivel")).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM reserva")).isEqualTo(1L);
    }

    @Test
    @DisplayName("R3: replay identico e 422 quando o corpo muda")
    void r3Idempotencia() {
        long peca = novaPeca(OFICINA, "P-4", 10);
        String chave = UUID.randomUUID().toString();

        var um = post("/api/v1/reservas", OFICINA, chave, corpo("OS-5", peca, 2));
        var dois = post("/api/v1/reservas", OFICINA, chave, corpo("OS-5", peca, 2));
        var tres = post("/api/v1/reservas", OFICINA, chave, corpo("OS-5", peca, 3));

        assertThat(um.getStatusCode().value()).isEqualTo(201);
        assertThat(dois.getStatusCode().value()).isEqualTo(201);
        assertThat(dois.getBody()).isEqualTo(um.getBody());
        assertThat(tres.getStatusCode().value()).isEqualTo(422);
        assertThat(texto(tres.getBody(), "$.code")).isEqualTo("IDEMPOTENCY_KEY_REUTILIZADA");
        assertThat(contar("SELECT count(*) FROM reserva")).isEqualTo(1L);
    }

    @Test
    @DisplayName("R4: reserva vencida libera o saldo, confirmar da 409 e vira EXPIRADA")
    void r4Expiracao() {
        long peca = novaPeca(OFICINA, "P-5", 10);
        long reserva = criarReserva(OFICINA, "OS-6", peca, 6);
        assertThat(saldoDe(OFICINA, peca)).isEqualTo(4);

        jdbc.update("UPDATE reserva SET criada_em = now() - interval '31 minutes', expira_em = now() - interval '1 minute' WHERE id = ?", reserva);

        assertThat(saldoDe(OFICINA, peca)).as("saldo liberado").isEqualTo(10);

        var r = post("/api/v1/reservas/" + reserva + "/confirmacao", OFICINA, null, null);
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(texto(r.getBody(), "$.code")).isEqualTo("RESERVA_EXPIRADA");
        assertThat(texto(get("/api/v1/reservas/" + reserva, OFICINA).getBody(), "$.status")).isEqualTo("EXPIRADA");
        assertThat(estoqueFisico(peca)).isEqualTo(10);
    }

    @Test
    @DisplayName("R4: job em lote marca as vencidas como EXPIRADA")
    void r4Job() {
        long peca = novaPeca(OFICINA, "P-6", 10);
        long reserva = criarReserva(OFICINA, "OS-7", peca, 1);
        jdbc.update("UPDATE reserva SET criada_em = now() - interval '31 minutes', expira_em = now() - interval '1 minute' WHERE id = ?", reserva);

        assertThat(expiracao.expirarVencidas()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM reserva WHERE id = ?", String.class, reserva))
                .isEqualTo("EXPIRADA");
        assertThat(saldoDe(OFICINA, peca)).isEqualTo(10);
    }

    @Test
    @DisplayName("R5: confirmar/cancelar duas vezes devolve 409 TRANSICAO_INVALIDA")
    void r5Transicoes() {
        long peca = novaPeca(OFICINA, "P-7", 10);
        long reserva = criarReserva(OFICINA, "OS-8", peca, 1);

        assertThat(post("/api/v1/reservas/" + reserva + "/confirmacao", OFICINA, null, null)
                .getStatusCode().value()).isEqualTo(200);

        var deNovo = post("/api/v1/reservas/" + reserva + "/confirmacao", OFICINA, null, null);
        assertThat(deNovo.getStatusCode().value()).isEqualTo(409);
        assertThat(texto(deNovo.getBody(), "$.code")).isEqualTo("TRANSICAO_INVALIDA");

        var cancelar = post("/api/v1/reservas/" + reserva + "/cancelamento", OFICINA, null, null);
        assertThat(cancelar.getStatusCode().value()).isEqualTo(409);
        assertThat(texto(cancelar.getBody(), "$.code")).isEqualTo("TRANSICAO_INVALIDA");
        assertThat(estoqueFisico(peca)).as("nao baixou duas vezes").isEqualTo(9);
    }

    @Test
    @DisplayName("R6: recurso de outra oficina devolve 404")
    void r6Isolamento() {
        long peca = novaPeca(OFICINA, "P-8", 10);
        long reserva = criarReserva(OFICINA, "OS-9", peca, 1);

        assertThat(get("/api/v1/pecas/" + peca + "/saldo", OUTRA_OFICINA).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/api/v1/reservas/" + reserva, OUTRA_OFICINA).getStatusCode().value()).isEqualTo(404);
        assertThat(post("/api/v1/reservas/" + reserva + "/confirmacao", OUTRA_OFICINA, null, null)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(post("/api/v1/reservas/" + reserva + "/cancelamento", OUTRA_OFICINA, null, null)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(post("/api/v1/reservas", OUTRA_OFICINA, UUID.randomUUID().toString(), corpo("OS-10", peca, 1))
                .getStatusCode().value()).isEqualTo(404);
        assertThat(contar("SELECT count(*) FROM reserva")).as("nada criado para outra oficina").isEqualTo(1L);
    }

    @Test
    @DisplayName("R7: erros em application/problem+json com code de negocio")
    void r7FormatoDoErro() {
        long peca = novaPeca(OFICINA, "P-9", 10);

        var semChave = post("/api/v1/reservas", OFICINA, null, corpo("OS-11", peca, 1));
        assertThat(semChave.getStatusCode().value()).isEqualTo(400);
        assertThat(semChave.getHeaders().getContentType().toString()).isEqualTo("application/problem+json");
        assertThat(texto(semChave.getBody(), "$.code")).isEqualTo("IDEMPOTENCY_KEY_AUSENTE");
        assertThat(semChave.getBody()).contains("\"title\"").contains("\"type\"").contains("\"status\"");

        var invalido = post("/api/v1/reservas", OFICINA, UUID.randomUUID().toString(),
                "{\"ordemServicoId\":\"\",\"itens\":[]}");
        assertThat(invalido.getStatusCode().value()).isEqualTo(422);
        assertThat(invalido.getHeaders().getContentType().toString()).isEqualTo("application/problem+json");
        assertThat(texto(invalido.getBody(), "$.code")).isEqualTo("VALIDACAO_INVALIDA");

        var inexistente = get("/api/v1/pecas/999999/saldo", OFICINA);
        assertThat(inexistente.getStatusCode().value()).isEqualTo(404);
        assertThat(texto(inexistente.getBody(), "$.code")).isEqualTo("PECA_NAO_ENCONTRADA");
    }
}
