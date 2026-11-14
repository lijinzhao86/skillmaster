# Runtime only: the jar is built by CI (`mvn verify`) before this image, so the image does not
# depend on a Maven base image tag existing — one fewer moving part on a host that cannot pull.
FROM eclipse-temurin:25-jre

# curl is the health check's tool. The JRE image carries neither curl nor wget, and a health
# check that cannot run makes the container permanently unhealthy.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system ops \
    && useradd --system --gid ops ops

WORKDIR /app
COPY target/skillmaster-ops-*.jar app.jar
USER ops
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8090/actuator/health | grep -q UP
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
