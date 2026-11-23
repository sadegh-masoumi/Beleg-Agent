package dev.belegagent.app;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL and MinIO for integration tests (same images as compose.yaml). */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.7-alpine"));
    }

    @Bean
    MinIOContainer minio() {
        // The official minio/minio image is no longer published; pgsty/minio is a drop-in fork.
        return new MinIOContainer(DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
                .asCompatibleSubstituteFor("minio/minio"));
    }

    @Bean
    DynamicPropertyRegistrar minioProperties(MinIOContainer minio) {
        return registry -> {
            registry.add("beleg.storage.endpoint", minio::getS3URL);
            registry.add("beleg.storage.access-key", minio::getUserName);
            registry.add("beleg.storage.secret-key", minio::getPassword);
        };
    }
}
