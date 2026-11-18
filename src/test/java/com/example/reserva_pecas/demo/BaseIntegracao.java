package com.example.reserva_pecas.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Sobe um Postgres real (Testcontainers) e da helpers de HTTP/SQL para os testes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class BaseIntegracao {

    static final long OFICINA = 42L;
    static final long OUTRA_OFICINA = 43L;

    private static final PostgreSQLContainer<?> BANCO =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        BANCO.start();   // class-load: antes do contexto Spring
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", BANCO::getJdbcUrl);
        r.add("spring.datasource.username", BANCO::getUsername);
        r.add("spring.datasource.password", BANCO::getPassword);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> 30);
        // job agendado nao interfere nos cenarios (expiracao e testada explicitamente)
        r.add("reservas.expiracao.intervalo-ms", () -> 3_600_000);
    }

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void limparBanco() {
        jdbc.execute("TRUNCATE reserva_item, reserva, idempotencia, peca RESTART IDENTITY CASCADE");
    }

    long novaPeca(long oficinaId, String codigo, int estoque) {
        return jdbc.queryForObject(
                "INSERT INTO peca (oficina_id, codigo, descricao, estoque_fisico) VALUES (?,?,?,?) RETURNING id",
                Long.class, oficinaId, codigo, "peca de teste " + codigo, estoque);
    }

    HttpHeaders headers(long oficinaId, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Oficina-Id", String.valueOf(oficinaId));
        h.set("X-User-Id", "1001");
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    ResponseEntity<String> post(String url, long oficinaId, String chave, String corpo) {
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(corpo, headers(oficinaId, chave)), String.class);
    }

    ResponseEntity<String> get(String url, long oficinaId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Oficina-Id", String.valueOf(oficinaId));
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    String corpo(String os, long pecaId, int quantidade) {
        return "{\"ordemServicoId\":\"" + os + "\",\"itens\":[{\"pecaId\":" + pecaId
                + ",\"quantidade\":" + quantidade + "}]}";
    }

    long criarReserva(long oficinaId, String os, long pecaId, int quantidade) {
        ResponseEntity<String> r = post("/api/v1/reservas", oficinaId, UUID.randomUUID().toString(),
                corpo(os, pecaId, quantidade));
        assertThat(r.getStatusCode().value()).as("criacao da reserva").isEqualTo(201);
        return numero(r.getBody(), "$.id");
    }

    int saldoDe(long oficinaId, long pecaId) {
        ResponseEntity<String> r = get("/api/v1/pecas/" + pecaId + "/saldo", oficinaId);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        return numero(r.getBody(), "$.saldoDisponivel");
    }

    int estoqueFisico(long pecaId) {
        return jdbc.queryForObject("SELECT estoque_fisico FROM peca WHERE id = ?", Integer.class, pecaId);
    }

    long contar(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    static int numero(String json, String caminho) {
        return ((Number) JsonPath.read(json, caminho)).intValue();
    }

    static String texto(String json, String caminho) {
        return JsonPath.read(json, caminho);
    }

    static List<?> lista(String json, String caminho) {
        return JsonPath.read(json, caminho);
    }
}
