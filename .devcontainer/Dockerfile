# Development toolbox: JDK 25 + Maven + Docker CLI (for Testcontainers via the host socket).
FROM docker:29-cli AS dockercli

FROM maven:3.10.0-eclipse-temurin-25
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl less jq postgresql-client \
    && rm -rf /var/lib/apt/lists/*
COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=dockercli /usr/local/libexec/docker/cli-plugins /usr/local/libexec/docker/cli-plugins
WORKDIR /workspace
