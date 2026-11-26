package dev.belegagent.app.processing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(5), Duration.ofMinutes(1), new Random());

    @RepeatedTest(20)
    void delayIsFullJitterBelowTheExponentialCap() {
        assertThat(policy.delayAfter(1, null)).hasValueSatisfying(d -> assertThat(d).isBetween(Duration.ZERO, Duration.ofSeconds(5)));
        assertThat(policy.delayAfter(3, null)).hasValueSatisfying(d -> assertThat(d).isBetween(Duration.ZERO, Duration.ofSeconds(20)));
    }

    @Test
    void capIsBoundedByMaxDelay() {
        RetryPolicy many = new RetryPolicy(100, Duration.ofSeconds(5), Duration.ofMinutes(1), new Random());

        assertThat(many.delayAfter(60, null)).hasValueSatisfying(d -> assertThat(d).isLessThanOrEqualTo(Duration.ofMinutes(1)));
    }

    @Test
    void jitterActuallySpreadsRetries() {
        long distinct = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> policy.delayAfter(4, null).orElseThrow())
                .distinct().count();

        assertThat(distinct).isGreaterThan(10);
    }

    @Test
    void knownRetryAfterIsRespected() {
        assertThat(policy.delayAfter(1, Duration.ofSeconds(30))).hasValue(Duration.ofSeconds(30));
    }

    @Test
    void attemptLimitMeansDeadLetter() {
        assertThat(policy.delayAfter(4, null)).isPresent();
        assertThat(policy.delayAfter(5, null)).isEmpty();
    }
}
