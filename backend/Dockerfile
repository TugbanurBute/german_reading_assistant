# syntax=docker/dockerfile:1

# --- Build: Maven wrapper on a JDK ---
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Dependencies first: this layer is reused as long as pom.xml and the wrapper don't change.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests run outside the image build (./mvnw verify needs PostgreSQL).
RUN ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/german-reader-*.jar extract --layers --launcher --destination /extracted

# --- Runtime: JRE only, non-root ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app

# Spring Boot layers, least to most frequently changed, so rebuilds only replace the small application layer.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

USER app
EXPOSE 8080
# No profile: production defaults from application.yml. All configuration and secrets come from the environment.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness > /dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
