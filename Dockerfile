# Dependencies are resolved in their own layer so that editing source code does
# not re-download the world on every build.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src ./src
RUN ./mvnw -B -q clean package -DskipTests

# JRE rather than JDK: no compiler, fewer packages, smaller attack surface.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S wallet && adduser -S -G wallet wallet
WORKDIR /app
COPY --from=build --chown=wallet:wallet /build/target/wallet-service-*.jar app.jar
USER wallet

EXPOSE 8080

# Checks the database connection too, because Actuator reports DOWN when the
# pool cannot reach Postgres. A container that answers HTTP but cannot serve a
# transfer is not healthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- "http://localhost:${PORT:-8080}/actuator/health" | grep -q '"status":"UP"' || exit 1

# MaxRAMPercentage matters on a 512 MB host: without it the JVM sizes its heap
# against the machine's total memory rather than the container limit and gets
# OOM-killed under load.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar"]