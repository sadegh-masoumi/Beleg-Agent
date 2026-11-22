# ADR 0003: Fully containerized development environment

- Status: accepted
- Date: 2026-11-22

## Context
Nothing should be installed on the developer's machine except Docker: no JDK, no Maven, no PostgreSQL.
The application must still be built, tested (including Testcontainers) and run end to end.

## Decision
- `compose.yaml` defines the infrastructure (PostgreSQL 18, MinIO), the application (one image, `api` and `worker` roles via Spring profiles) and a `dev` toolbox (JDK 25 + Maven + Docker CLI).
- The `dev` container mounts the workspace, a named Maven cache volume, and the host Docker socket so Testcontainers can start sibling containers (`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`).
- VS Code opens the same `dev` service as a Dev Container (`.devcontainer/devcontainer.json`).
- `Makefile` wraps the common commands.
- MinIO uses the `pgsty/minio` fork because the official image is no longer published on Docker Hub.

- Host ports are shifted (API 18080, worker 18081, PostgreSQL 15432, MinIO 19000/19001) and configurable in `.env`, so the stack runs next to other local projects.

## Consequences
- No local model (ADR 0004), so the stack is light: ~2 GB of Docker memory is enough for PostgreSQL, MinIO and two JVMs.
