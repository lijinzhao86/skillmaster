# The wrapper downloads its own Maven, so this stage needs no toolchain beyond a JDK —
# and CI, a laptop and this image all resolve the same Maven version from
# .mvn/wrapper/maven-wrapper.properties.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /build

COPY skillmaster-server/ ./skillmaster-server/

# pom.xml copies ${project.basedir}/../gateway into the jar's classpath, so the build stage
# has to hold gateway/ at the same relative path it has in the repository. Without it the
# jar builds and then the server refuses to start: GatewaySource reads the skill from the
# classpath, and nothing else puts it there.
COPY gateway/ ./gateway/

RUN cd skillmaster-server && ./mvnw -B -DskipTests package

# A JRE, not a JDK: nothing in the image compiles.
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /build/skillmaster-server/target/*.jar app.jar

RUN useradd --create-home --uid 10001 skill
USER skill

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
