package com.example.reserva_pecas.demo.api;

import com.example.reserva_pecas.demo.api.dto.CriarReservaRequest;
import com.example.reserva_pecas.demo.api.dto.ReservaResponse;
import com.example.reserva_pecas.demo.servico.ReservaService;
import com.example.reserva_pecas.demo.servico.RespostaHttp;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reservas")
public class ReservaController {

    private final ReservaService service;

    public ReservaController(ReservaService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> criar(
            @RequestHeader(value = "X-Oficina-Id", required = false) Long oficinaId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CriarReservaRequest corpo) {
        Contexto ctx = Contexto.escrita(oficinaId, userId);
        return paraHttp(service.criar(ctx.oficinaId(), ctx.userId(), idempotencyKey, corpo));
    }

    @PostMapping("/{id}/confirmacao")
    public ResponseEntity<String> confirmar(
            @RequestHeader(value = "X-Oficina-Id", required = false) Long oficinaId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @PathVariable long id) {
        Contexto ctx = Contexto.escrita(oficinaId, userId);
        return paraHttp(service.confirmar(ctx.oficinaId(), id));
    }

    @PostMapping("/{id}/cancelamento")
    public ResponseEntity<String> cancelar(
            @RequestHeader(value = "X-Oficina-Id", required = false) Long oficinaId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @PathVariable long id) {
        Contexto ctx = Contexto.escrita(oficinaId, userId);
        return paraHttp(service.cancelar(ctx.oficinaId(), id));
    }

    /** Conveniencia para demonstrar R4/R5 (nao exigido no enunciado). */
    @GetMapping("/{id}")
    public ResponseEntity<ReservaResponse> buscar(
            @RequestHeader(value = "X-Oficina-Id", required = false) Long oficinaId,
            @PathVariable long id) {
        return ResponseEntity.ok(service.buscar(Contexto.leitura(oficinaId).oficinaId(), id));
    }

    private ResponseEntity<String> paraHttp(RespostaHttp r) {
        return ResponseEntity.status(r.status())
                .contentType(MediaType.parseMediaType(r.contentType()))
                .body(r.body());
    }
}
