# Main project deployment of the monitoring demo.
#
# Build context: repository root.
#   docker build -t sasps-monitor .
#   docker run --rm -p 8080:8080 sasps-monitor
#   curl -sS http://127.0.0.1:8080/health
#
# Binds 0.0.0.0 so the published port is reachable; the default outside Docker
# stays 127.0.0.1. Host CPU, RAM and network readings describe the container's
# view of the machine, not the Docker host.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY scripts/build.sh scripts/build.sh
COPY src src
RUN bash scripts/build.sh

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY scripts/run.sh scripts/run.sh
COPY --from=build /src/build/resource-monitor.jar build/resource-monitor.jar
RUN useradd --system --no-create-home --shell /usr/sbin/nologin monitor \
    && chown -R monitor /app
USER monitor
EXPOSE 8080
CMD ["bash", "scripts/run.sh", "--port", "8080", "--bind", "0.0.0.0"]
