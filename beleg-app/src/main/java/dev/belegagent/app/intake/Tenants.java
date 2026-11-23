package dev.belegagent.app.intake;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the tenant of a request. Phase 1 has no authentication: the tenant comes from the
 * {@code X-Tenant-Id} header or the configured default. The id becomes part of object keys, so it is validated strictly.
 */
@Component
public class Tenants {

    public static final String HEADER = "X-Tenant-Id";

    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private final String defaultTenant;

    public Tenants(IntakeProperties properties) {
        this.defaultTenant = properties.defaultTenant();
    }

    public String resolve(String header) {
        String tenant = header == null || header.isBlank() ? defaultTenant : header.strip();
        if (!VALID.matcher(tenant).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid tenant id: use lowercase letters, digits and '-', max 40 characters");
        }
        return tenant;
    }
}
