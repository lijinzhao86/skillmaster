# The wrapper downloads its own Maven, so this stage needs no toolchain beyond a JDK —
# and CI, a laptop and this image all resolve the same Maven version from
# .mvn/wrapper/maven-wrapper.properties.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /build

COPY skillmaster-server/ ./skillmaster-server/

# **No `gateway/` here any more** (ADR 0096): the startup journey is the platform's
# `skillmaster/startup` skill, so nothing is copied onto the classpath from the repository root.
# This stage used to copy that directory, and leaving the line behind made every image build fail
# once the directory was deleted — the build context is the repository root.
RUN cd skillmaster-server && ./mvnw -B -DskipTests package

# A JRE, not a JDK: nothing in the image compiles.
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /build/skillmaster-server/target/*.jar app.jar

RUN useradd --create-home --uid 10001 skill
USER skill

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
