package com.example.reserva_pecas.demo.servico;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.reserva_pecas.demo.api.dto.PaginaResponse;
import com.example.reserva_pecas.demo.api.dto.PecaBuscaResponse;
import com.example.reserva_pecas.demo.api.dto.SaldoResponse;
import com.example.reserva_pecas.demo.api.erro.Erros;
import com.example.reserva_pecas.demo.dominio.Peca;
import com.example.reserva_pecas.demo.infra.PecaRepository;
import com.example.reserva_pecas.demo.infra.ReservaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PecaService {

    private final PecaRepository pecas;
    private final ReservaRepository reservas;
    private final Clock clock;
    private final int tamanhoPagina;
    private final String saldoModo;

    public PecaService(PecaRepository pecas, ReservaRepository reservas, Clock clock,
                       @Value("${busca.tamanho-pagina:20}") int tamanhoPagina,
                       @Value("${busca.saldo-modo:LOTE}") String saldoModo) {
        this.pecas = pecas;
        this.reservas = reservas;
        this.clock = clock;
        this.tamanhoPagina = tamanhoPagina;
        this.saldoModo = saldoModo;
    }

    @Transactional(readOnly = true)
    public SaldoResponse saldo(long oficinaId, long pecaId) {
        Peca p = pecas.buscar(oficinaId, pecaId)
                .orElseThrow(() -> Erros.pecaNaoEncontrada(List.of(pecaId)));   // R6
        int reservado = reservas.reservadoAtivo(oficinaId, List.of(pecaId), clock.instant())
                .getOrDefault(pecaId, 0);
        return new SaldoResponse(p.id(), p.codigo(), p.estoqueFisico(), reservado, p.estoqueFisico() - reservado);
    }

    @Transactional(readOnly = true)
    public PaginaResponse<PecaBuscaResponse> buscar(long oficinaId, String busca, String cursor) {
        Long cursorId = Cursor.decodificar(cursor);
        // busca tamanho+1 para saber se ha proxima pagina sem COUNT(*)
        List<Peca> pagina = pecas.buscarPagina(oficinaId, busca, cursorId, tamanhoPagina + 1);
        boolean temMais = pagina.size() > tamanhoPagina;
        List<Peca> visiveis = temMais ? pagina.subList(0, tamanhoPagina) : pagina;

        List<Long> ids = visiveis.stream().map(Peca::id).toList();
        Instant agora = clock.instant();
        Map<Long, Integer> reservados = "N_PLUS_1".equalsIgnoreCase(saldoModo)
                ? saldoPorPeca(oficinaId, ids, agora)                       // baseline do DESEMPENHO.md
                : reservas.reservadoAtivo(oficinaId, ids, agora);           // otimizado

        List<PecaBuscaResponse> itens = new ArrayList<>(visiveis.size());
        for (Peca p : visiveis) {
            itens.add(new PecaBuscaResponse(p.id(), p.codigo(), p.descricao(), p.estoqueFisico(),
                    p.estoqueFisico() - reservados.getOrDefault(p.id(), 0)));
        }
        String proximo = temMais ? Cursor.codificar(visiveis.get(visiveis.size() - 1).id()) : null;
        return new PaginaResponse<>(itens, proximo, temMais);
    }

    private Map<Long, Integer> saldoPorPeca(long oficinaId, List<Long> ids, Instant agora) {
        Map<Long, Integer> acc = new HashMap<>();
        for (Long id : ids) {
            acc.putAll(reservas.reservadoAtivo(oficinaId, List.of(id), agora));
        }
        return acc;
    }
}
