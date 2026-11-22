package dev.belegagent.app;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(BelegAgentApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        modules.verify();
    }
}
