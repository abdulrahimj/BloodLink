# Multi-stage build: a full JDK + Maven is only needed to compile the app, not
# to run it. The final image ships just a JRE and the built jar, which is both
# smaller and has a much narrower attack surface than shipping the whole build
# toolchain to production.
#
# Stage 1 — build. eclipse-temurin:25-jdk matches the Java 25 this project is
# pinned to everywhere else (pom.xml <java.version>, CLAUDE.md). Maven itself
# comes from the project's own wrapper (mvnw), not a separately-versioned image,
# so the exact same Maven version used on every developer's machine is used here.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy just the wrapper + pom first so Docker can cache the dependency download
# layer independently of source changes — editing a .java file won't force a
# re-download of the whole Maven repository on every image rebuild.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
# The base configuration comes from the committed template, never from a local
# application.properties (which .dockerignore keeps out of the build context).
# That file is gitignored, so on a fresh clone it does not exist — and it holds
# more than credentials: token lifetime, CORS origins and the Hibernate/Flyway
# settings live only there. Without this step a fresh clone's image started, then
# died on "Could not resolve placeholder 'bloodlink.jwt.expiration-ms'".
# The template's datasource and JWT secret are placeholders; docker-compose.yml
# replaces them with real values through environment variables at run time.
RUN cp src/main/resources/application.properties.example src/main/resources/application.properties
# Skip tests here — this Dockerfile builds a runnable image, not a CI check;
# `mvnw test` still runs separately in the ordinary dev/CI workflow.
RUN ./mvnw -q -B clean package -DskipTests

# Stage 2 — run. -jre (not -jdk) is enough to execute a built jar.
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Run as a non-root user — a Spring Boot process has no legitimate reason to
# hold root inside its own container.
RUN useradd --system --create-home bloodlink
USER bloodlink

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# SPRING_PROFILES_ACTIVE defaults to "demo" here so that starting this image
# with no extra configuration (docker-compose.yml's normal path) brings up the
# demo seed data described in CLAUDE.md, not an empty dev schema pointed at a
# database that doesn't exist inside the container network.
ENV SPRING_PROFILES_ACTIVE=demo
ENTRYPOINT ["java", "-jar", "app.jar"]
