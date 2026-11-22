# syntax=docker/dockerfile:1
# One image for both roles (api / worker); the role is chosen with SPRING_PROFILES_ACTIVE.

FROM maven:3.10.0-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests -pl beleg-app -am package

FROM eclipse-temurin:25-jre
# OCR fallback (tess4j) needs native Tesseract + German data; add in Phase 1:
#   apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-deu
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/beleg-app/target/beleg-app.jar app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
