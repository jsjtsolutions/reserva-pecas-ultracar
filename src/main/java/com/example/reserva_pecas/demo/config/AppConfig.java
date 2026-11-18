package com.example.reserva_pecas.demo.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** Relogio injetavel: testes usam Clock.fixed para determinismo (R4/R5). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
