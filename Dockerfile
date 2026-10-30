# ---- build ----
FROM maven:3.10-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- runtime ----
FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 notifier \
 && useradd --system --uid 10001 --gid notifier --home-dir /app --shell /usr/sbin/nologin notifier \
 && mkdir -p /data /config /run/secrets \
 && chown notifier:notifier /data && chmod 700 /data
COPY --from=build /src/target/gitstatus.jar /app/gitstatus.jar

ENV GITSTATUS_CONFIG=/config/config.conf \
    TERM=xterm-256color \
    LANG=C.UTF-8

USER notifier
WORKDIR /app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q UP <&3"]
# Daemon heap is capped at 40% of the container limit, leaving room for an admin TUI JVM
# (started via docker compose exec with the JVM default of 25%). Set here rather than in
# JAVA_TOOL_OPTIONS so the JVM does not print a "Picked up" notice into the TUI terminal.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=40", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/gitstatus.jar"]
CMD ["run"]
