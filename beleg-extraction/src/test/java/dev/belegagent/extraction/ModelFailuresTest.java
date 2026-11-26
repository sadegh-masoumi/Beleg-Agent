package dev.belegagent.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ModelFailuresTest {

    @Test
    void transientProviderErrorsAreRetryable() {
        assertThat(ModelFailures.isRetryable(new RateLimitException("429"))).isTrue();
        assertThat(ModelFailures.isRetryable(new InternalServerException("503 high demand"))).isTrue();
        assertThat(ModelFailures.isRetryable(new TimeoutException("timeout"))).isTrue();
        assertThat(ModelFailures.isRetryable(new HttpException(503, "unavailable"))).isTrue();
        assertThat(ModelFailures.isRetryable(new RuntimeException("wrapped", new IOException("reset")))).isTrue();
    }

    @Test
    void clientErrorsArePermanent() {
        assertThat(ModelFailures.isRetryable(new AuthenticationException("bad key"))).isFalse();
        assertThat(ModelFailures.isRetryable(new InvalidRequestException("bad request"))).isFalse();
        assertThat(ModelFailures.isRetryable(new HttpException(400, "bad request"))).isFalse();
    }

    @Test
    void simulatedModelFailsRetryablyAtTheConfiguredRate() {
        SimulatedExtractor alwaysFails = new SimulatedExtractor("sim", java.time.Duration.ZERO, 1.0);
        SimulatedExtractor neverFails = new SimulatedExtractor("sim", java.time.Duration.ZERO, 0.0);

        assertThat(neverFails.extract(new byte[0], FileKind.PNG).beleg().grossTotal()).isEqualByComparingTo("32.94");
        try {
            alwaysFails.extract(new byte[0], FileKind.PNG);
        } catch (ExtractionException e) {
            assertThat(e.retryable()).isTrue();
            return;
        }
        throw new AssertionError("expected a simulated failure");
    }
}
