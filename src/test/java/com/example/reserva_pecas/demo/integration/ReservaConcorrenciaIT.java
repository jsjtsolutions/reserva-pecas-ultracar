package com.example.reserva_pecas.demo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.jayway.jsonpath.JsonPath;

class ReservaConcorrenciaIT extends BaseIT {

    private static final long OFICINA = 42;
    private long pecaId;

    @BeforeEach
    void setup() {
        limpar();
        pecaId = inserirPeca(OFICINA, "P-DISPUTA", 5);
    }

    @Test
    @DisplayName("R2: 20 reservas paralelas de 1 unidade com estoque 5 -> 5x201, 15x409, saldo 0")
    void vinteReservasParalelas() throws Exception {
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Integer>> futuros = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            final String os = "OS-" + i;
            final String chave = UUID.randomUUID().toString();
            Callable<Integer> tarefa = () -> {
                largada.await();   // todas disparam juntas
                return post("/api/v1/reservas", OFICINA, chave, corpoReserva(os, pecaId, 1)).getStatusCode().value();
            };
            futuros.add(pool.submit(tarefa));
        }
        largada.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();

        Map<Integer, Long> porStatus = new ArrayList<Integer>() {{
            for (Future<Integer> f : futuros) {
                try { add(f.get()); } catch (Exception e) { throw new RuntimeException(e); }
            }
        }}.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertThat(porStatus.getOrDefault(201, 0L)).as("reservas criadas").isEqualTo(5L);
        assertThat(porStatus.getOrDefault(409, 0L)).as("reservas negadas").isEqualTo(15L);
        assertThat(porStatus.keySet()).containsExactlyInAnyOrder(201, 409);

        var saldo = get("/api/v1/pecas/" + pecaId + "/saldo", OFICINA);
        assertThat(saldo.getStatusCode().value()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(saldo.getBody(), "$.saldoDisponivel")).isZero();
        assertThat((Integer) JsonPath.read(saldo.getBody(), "$.reservadoAtivo")).isEqualTo(5);

        // reserva nao confirmada nao baixa estoque fisico
        assertThat(jdbc.queryForObject("SELECT estoque_fisico FROM peca WHERE id = ?", Integer.class, pecaId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reserva WHERE status = 'ATIVA'", Long.class)).isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(quantidade),0) FROM reserva_item", Integer.class)).isEqualTo(5);
    }

    @Test
    @DisplayName("R3: 10 requisicoes paralelas com a MESMA chave -> 1 reserva e respostas identicas")
    void mesmaChaveParalela() throws Exception {
        int n = 10;
        String chave = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<String>> futuros = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            futuros.add(pool.submit(() -> {
                largada.await();
                var r = post("/api/v1/reservas", OFICINA, chave, corpoReserva("OS-IGUAL", pecaId, 1));
                return r.getStatusCode().value() + "|" + r.getBody();
            }));
        }
        largada.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();

        List<String> respostas = new ArrayList<>();
        for (Future<String> f : futuros) {
            respostas.add(f.get());
        }
        assertThat(respostas).allSatisfy(r -> assertThat(r).startsWith("201|"));
        assertThat(respostas.stream().distinct().count()).as("corpo identico no replay").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reserva", Long.class)).isEqualTo(1L);
    }
}