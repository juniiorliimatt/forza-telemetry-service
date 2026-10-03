package br.com.forza;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ForzaTelemetryServiceApplication {
    public static void main(final String[] args) {
        SpringApplication.run(ForzaTelemetryServiceApplication.class, args);
    }
}
