# Runs the interpreter in a container so the benchmark measures both systems the
# same way (METODOLOGIA §2.4): with the application on the host, every request
# from the benchmark client reached LOCAL without crossing the Docker VM boundary
# while REFERENCE always paid that crossing, and conversely LOCAL paid it on every
# Cypher round trip. Measured at ~1 ms per crossing — enough to matter for queries
# in the single-digit millisecond range, with no clear direction. Containerising
# the application makes the topology symmetric: client -> app (one crossing),
# app -> database (inside the VM).
#
# Build the jar first: ./gradlew bootJar
FROM eclipse-temurin:25-jre

# The JRE image ships neither wget nor curl; the compose healthcheck needs one to
# probe a real endpoint rather than merely checking that the port is open.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Only the executable boot jar; the "-plain" artifact is the library jar.
COPY build/libs/processm-interpreter-*-SNAPSHOT.jar /app/application.jar

# The heap is sized by the same rule the reference implementation applies to itself
# (processm.launcher/src/main/docker/docker-start-processm.sh): half of the memory
# available to the container — the cgroup limit when one is set, otherwise total RAM.
# Matching their policy removes a fairness objection, since a fixed -Xmx here gave
# the reference roughly twice our ceiling.
#
# No -Xms on purpose: forcing an initial heap inflated the idle baseline by ~200 MiB
# (589 MiB with -Xms512m versus 389 MiB without) without affecting throughput, which
# would have distorted the Q3 memory comparison. The ceiling itself barely matters
# for measured usage (389 MiB at -Xmx2g versus 393 MiB at -Xmx3968m).
#
# JVM_HEAP_OPTS overrides the computed value; it is named so that EnvironmentProbe's
# memory-config filter records it in environment.json (it matches keys containing
# "heap"/"memory"), keeping the heap settings part of the documented environment.
ENV JVM_HEAP_OPTS=""

EXPOSE 8080

COPY docker/entrypoint.sh /app/entrypoint.sh
ENTRYPOINT ["sh", "/app/entrypoint.sh"]
