# Multi-stage build for auth-service.
#
# Build context is the REPOSITORY ROOT, not this directory, because the module
# inherits from the root pom.xml:
#
#   docker build -f auth-service/Dockerfile -t bookmyseat/auth-service:dev .
#
# Only pom.xml and auth-service/ are copied, by explicit path, so no root
# .dockerignore is needed and nothing outside the module is pulled in.

# ---------- stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Install the parent POM on its own. -N (non-recursive) matters: the root pom
# declares all five modules, and without it Maven demands every sibling module
# be present in the build context just to read the reactor.
COPY pom.xml ./
RUN mvn -N -B install

# Poms before sources, so dependency resolution is only re-run when the pom
# changes rather than on every source edit.
WORKDIR /build/auth-service
COPY auth-service/pom.xml ./
RUN mvn -B dependency:go-offline

COPY auth-service/src ./src
RUN mvn -B clean package -DskipTests

# ---------- stage 2: runtime ----------
FROM eclipse-temurin:21-jre-alpine

# Non-root. -S creates a system user/group with no password and no home.
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build /build/auth-service/target/auth-service-*.jar app.jar
RUN chown app:app /app/app.jar

USER app

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
