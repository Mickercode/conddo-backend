# ----------------------------- build stage -----------------------------
# Builds the runnable conddo-api jar. Tests are NOT run here (they need a
# Docker daemon for Testcontainers); CI runs them on every push.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Module descriptors + wrapper first, so dependency download is cached across
# source-only changes. All module poms are copied so the reactor can resolve the
# parent's <modules> list — conddo-studio is NOT built here (-pl conddo-api -am
# excludes it), but its pom must exist or Maven fails reading the aggregator.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY conddo-core/pom.xml conddo-core/pom.xml
COPY conddo-api/pom.xml conddo-api/pom.xml
COPY conddo-studio/pom.xml conddo-studio/pom.xml
COPY conddo-payments/pom.xml conddo-payments/pom.xml
RUN chmod +x mvnw && ./mvnw -B -pl conddo-api -am dependency:go-offline -DskipTests || true

# Sources, then build. (.dockerignore keeps target/ and the dev JWT keys out of
# the image, so no secret is ever baked into the jar.)
COPY conddo-core/src conddo-core/src
COPY conddo-api/src conddo-api/src
RUN ./mvnw -B -pl conddo-api -am clean package -DskipTests

# ----------------------------- run stage -------------------------------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /workspace/conddo-api/target/conddo-api-*.jar app.jar

# Respect the container's memory limit; the platform injects PORT.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
