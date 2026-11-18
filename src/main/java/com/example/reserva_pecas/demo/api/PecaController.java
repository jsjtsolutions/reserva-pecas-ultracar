package com.example.reserva_pecas.demo.api;

import com.example.reserva_pecas.demo.api.dto.PaginaResponse;
import com.example.reserva_pecas.demo.api.dto.PecaBuscaResponse;
import com.example.reserva_pecas.demo.api.dto.SaldoResponse;
import com.example.reserva_pecas.demo.servico.PecaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pecas")
public class PecaController {

    private final PecaService service;

    public PecaController(PecaService service) {
        this.service = service;
    }

    @GetMapping("/{id}/saldo")
    public ResponseEntity<SaldoResponse> saldo(
            @RequestHeader(value = "X-Oficina-Id", required = false) Long oficinaId,
            @PathVariable long id) {
        return ResponseEntity.ok(service.saldo(Contexto.leitura(oficinaId).oficinaId(), id));
    }

    @GetMapping
    public ResponseEntity<PaginaResponse<PecaBuscaResponse>> buscar(
            @RequestHeader(value = "X-Oficina-Id", required = false) Long oficinaId,
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok(service.buscar(Contexto.leitura(oficinaId).oficinaId(), busca, cursor));
    }
}