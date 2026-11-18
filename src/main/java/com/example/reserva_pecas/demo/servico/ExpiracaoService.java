package com.example.reserva_pecas.demo.servico;

import java.time.Clock;

import com.example.reserva_pecas.demo.config.ReservaProperties;
import com.example.reserva_pecas.demo.infra.ReservaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ExpiracaoService {

    private static final Logger log = LoggerFactory.getLogger(ExpiracaoService.class);

    private final ReservaRepository reservas;
    private final ReservaProperties props;
    private final Clock clock;

    public ExpiracaoService(ReservaRepository reservas, ReservaProperties props, Clock clock) {
        this.reservas = reservas;
        this.props = props;
        this.clock = clock;
    }

    /**
     * R4: o saldo ja e liberado preguicosamente (filtro expira_em > agora).
     * Este job apenas materializa o status. UPDATE unico = atomico por si,
     * SKIP LOCKED = seguro com N instancias, sem lock distribuido.
     */
    public int expirarVencidas() {
        int n = reservas.expirarLote(clock.instant(), props.loteExpiracao());
        if (n > 0) {
            log.info("expiracao: {} reserva(s) marcadas como EXPIRADA", n);
        }
        return n;
    }

    @Scheduled(fixedDelayString = "${reservas.expiracao.intervalo-ms:30000}")
    public void agendado() {
        try {
            expirarVencidas();
        } catch (Exception e) {
            log.warn("falha no job de expiracao (tentaremos novamente)", e);
        }
    }
}
