# Everything runs in Docker. No Java or Maven on the host.
DC   := docker compose
DEV  := $(DC) --profile dev run --rm dev

.PHONY: help env up infra down clean logs ps build test shell psql rebuild gemini-check

help:            ## list targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-10s %s\n", $$1, $$2}'

env:             ## create .env from .env.example if missing
	@test -f .env || (cp .env.example .env && echo "created .env")

infra: env       ## start postgres + minio only
	$(DC) up -d postgres minio minio-init

up: env          ## start everything (infra + api + worker)
	$(DC) up -d --build

down:            ## stop all containers (keeps data volumes)
	$(DC) --profile dev down

clean:           ## stop and DELETE all volumes (database, files, maven cache)
	$(DC) --profile dev down -v

logs:            ## follow api + worker logs
	$(DC) logs -f api worker

ps:              ## container status
	$(DC) ps -a

build: env       ## mvn package inside the dev container
	$(DEV) mvn -B -ntp -DskipTests package

test: env        ## mvn verify inside the dev container (uses Testcontainers)
	$(DEV) mvn -B -ntp verify

shell: env       ## bash inside the dev container
	$(DEV) bash

psql: env        ## psql into the database
	$(DC) exec postgres sh -c 'psql -U $$POSTGRES_USER -d $$POSTGRES_DB'

rebuild: env     ## rebuild and restart api + worker after code changes
	$(DC) up -d --build api worker

gemini-check: env ## list the Gemini models your API key can use (runs in the dev container)
	$(DEV) sh -c 'curl -fsS -H "x-goog-api-key: $$GEMINI_API_KEY" "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200" | jq -r ".models[] | select(.supportedGenerationMethods | index(\"generateContent\")) | .name"'
