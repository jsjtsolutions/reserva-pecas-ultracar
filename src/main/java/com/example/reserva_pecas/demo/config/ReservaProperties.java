package com.example.reserva_pecas.demo.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "reservas")
public record ReservaProperties(@DefaultValue("30m") Duration ttl,
                                @DefaultValue("500") int loteExpiracao) {
}