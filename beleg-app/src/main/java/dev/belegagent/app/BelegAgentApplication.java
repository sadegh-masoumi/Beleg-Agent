package dev.belegagent.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * One codebase, two roles. Activate exactly one Spring profile:
 * {@code api} (upload, presigned URLs, SSE status, review) or
 * {@code worker} (routing, extraction, validation, self-correction, account suggestion).
 */
@Modulithic(systemName = "Beleg-Agent")
@SpringBootApplication
public class BelegAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(BelegAgentApplication.class, args);
    }
}
