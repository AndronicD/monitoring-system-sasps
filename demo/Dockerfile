# Demo-specific deployment: the same build plus the Python smoke-check tooling,
# so traffic/demo.py and the Java tests can run inside the container.
#
# Build context: repository root, because it builds the same sources.
#   docker build -f demo/Dockerfile -t sasps-monitor-demo .
#   docker run --rm -p 8080:8080 sasps-monitor-demo
#   docker run --rm sasps-monitor-demo python3 traffic/demo.py
#   docker run --rm sasps-monitor-demo bash scripts/test.sh
#
# Binds 0.0.0.0 so the published port is reachable; the default outside Docker
# stays 127.0.0.1. Host CPU, RAM and network readings describe the container's
# view of the machine, not the Docker host, and the smoke check measures the
# container loopback interface.

FROM eclipse-temurin:17-jdk
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*
COPY scripts scripts
COPY traffic traffic
COPY src src
RUN bash scripts/build.sh \
    && useradd --system --no-create-home --shell /usr/sbin/nologin monitor \
    && chown -R monitor /app
USER monitor
EXPOSE 8080
CMD ["bash", "scripts/run.sh", "--port", "8080", "--bind", "0.0.0.0"]
